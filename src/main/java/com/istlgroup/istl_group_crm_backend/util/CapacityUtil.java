package com.istlgroup.istl_group_crm_backend.util;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and normalises the free-text capacity values scattered across the CRM
 * (leads.capacity + leads.capacity_unit, site_visit cable lengths, "100 kWp"
 * scope strings) into a single numeric basis for scaling.
 *
 * Ports two existing helpers into one home so the suggestion engine doesn't grow
 * a third copy:
 *   • parseValueUnit  — from LeadSiteVisitTab.js (regex "^([\\d.,\\s]*)\\s*(.*)$")
 *   • normaliseUnit   — from OrderBookService.normaliseCapacityUnit
 *
 * Pure and stateless — no Spring, directly unit-testable.
 */
public final class CapacityUtil {

    private CapacityUtil() {}

    // Leading number (digits, dot, comma, spaces) then the rest as the unit.
    private static final Pattern VALUE_UNIT = Pattern.compile("^([\\d.,\\s]*)\\s*(.*)$");

    /** A number + its (raw) unit, as pulled out of a free-text string. */
    public record ValueUnit(BigDecimal value, String unit) {}

    /**
     * Split "5 kW" / "10kW" / "100 m" / "5" into a number and a unit. Returns a
     * null value when there is no parseable leading number (e.g. "" or "TBD").
     */
    public static ValueUnit parseValueUnit(String s) {
        if (s == null) return new ValueUnit(null, "");
        String str = s.trim();
        if (str.isEmpty()) return new ValueUnit(null, "");

        Matcher m = VALUE_UNIT.matcher(str);
        if (!m.matches()) return new ValueUnit(null, str);

        String numPart = m.group(1) == null ? "" : m.group(1).replaceAll("[,\\s]", "").trim();
        String unit = m.group(2) == null ? "" : m.group(2).trim();

        BigDecimal value = null;
        if (!numPart.isEmpty()) {
            try { value = new BigDecimal(numPart); } catch (NumberFormatException ignore) { /* leave null */ }
        }
        return new ValueUnit(value, unit);
    }

    /**
     * Canonicalise a unit for capacity. Mirrors
     * OrderBookService.normaliseCapacityUnit: IoT sub-groups are always "Units",
     * kwp/kw → kW, mwp/mw → MW, nos/units/pcs → Units, km → Km, kg → Kg.
     */
    public static String normaliseUnit(String subGroupName, String unit) {
        if (subGroupName != null) {
            String sg = subGroupName.toLowerCase();
            if (sg.contains("ccms") || sg.contains("mcms") || sg.contains("itms")) return "Units";
        }
        if (unit == null || unit.isBlank()) return "Units";
        String u = unit.trim().toLowerCase();
        if (u.equals("kwp") || u.equals("kw"))            return "kW";
        if (u.equals("mwp") || u.equals("mw"))            return "MW";
        if (u.equals("nos") || u.equals("no's") || u.equals("no")
                || u.equals("units") || u.equals("unit") || u.equals("pcs")) return "Units";
        if (u.equals("km"))                               return "Km";
        if (u.equals("kg") || u.equals("kgs"))            return "Kg";
        return unit.trim();
    }

    /**
     * The parsed capacity used to scale a BOM:
     *   value        — the numeric magnitude as entered
     *   unit         — the canonical unit
     *   scaleBase    — the magnitude in the scaling base (kW for power, or the raw
     *                  count for count-based IoT sub-groups); null if unparseable
     *   powerBased   — true for kW/MW capacities, false for count units (CCMS…)
     */
    public record CapacityInfo(BigDecimal value, String unit, BigDecimal scaleBase, boolean powerBased) {
        public boolean isUsable() { return scaleBase != null && scaleBase.signum() > 0; }
    }

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /**
     * Resolve a lead's capacity into a scaling base. Prefers an explicit
     * {@code capacityUnit} column; if the capacity string itself carries the unit
     * (e.g. "100 kWp"), that is used when the column is blank. Power units are
     * converted to kW (MW × 1000) so PER_KW rules are unit-consistent; count-based
     * sub-groups scale on the raw count.
     */
    public static CapacityInfo parse(String capacity, String capacityUnit, String subGroupName) {
        ValueUnit vu = parseValueUnit(capacity);
        BigDecimal value = vu.value();

        String rawUnit = (capacityUnit != null && !capacityUnit.isBlank())
                ? capacityUnit
                : vu.unit();
        String unit = normaliseUnit(subGroupName, rawUnit);

        boolean powerBased = "kW".equals(unit) || "MW".equals(unit);

        BigDecimal scaleBase = null;
        if (value != null) {
            if ("MW".equals(unit))      scaleBase = value.multiply(THOUSAND); // → kW
            else                        scaleBase = value;                    // kW or raw count
        }
        return new CapacityInfo(value, unit, scaleBase, powerBased);
    }

    /** True when two capacities share a scaling family (both power, or both count). */
    public static boolean sameFamily(CapacityInfo a, CapacityInfo b) {
        return a != null && b != null && a.powerBased() == b.powerBased();
    }
}
