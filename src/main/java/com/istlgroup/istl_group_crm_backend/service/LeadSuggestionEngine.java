package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;

/**
 * The scope/BOM suggestion math — pure, Spring-free apart from being a bean, so
 * every branch is unit-testable without a database. It never touches repos: the
 * caller (LeadScopeService) gathers the template rows, the mined rows, and the
 * capacity, then delegates the transformation here.
 *
 * Two paths:
 *   • expandTemplateBom  — compute quantities from each template line's BASIS.
 *   • scaleMinedBom      — rescale a past lead's real BOM to the target capacity,
 *                          resolving each line's basis from the template (the
 *                          mined job supplies WHAT, the template supplies HOW).
 */
@Component
public class LeadSuggestionEngine {

    // ── Warning codes (mirrored to labels on the frontend) ─────────────────────
    public static final String W_NEEDS_CAPACITY      = "NEEDS_CAPACITY";
    public static final String W_LARGE_SCALE_CHECK   = "LARGE_SCALE_CHECK";
    public static final String W_AMBIGUOUS_MATCH     = "AMBIGUOUS_MATCH";
    public static final String W_BASIS_UNRESOLVED    = "BASIS_UNRESOLVED";
    public static final String W_NEEDS_SITE_VISIT    = "NEEDS_SITE_VISIT";
    public static final String W_NO_TEMPLATE_NO_HISTORY = "NO_TEMPLATE_NO_HISTORY";
    // Auto-sizing (make-driven) codes
    public static final String W_NEEDS_MODULE_WATT     = "NEEDS_MODULE_WATT";
    public static final String W_NEEDS_INVERTER_KW     = "NEEDS_INVERTER_KW";
    public static final String W_NEEDS_MODULE_DRIVER   = "NEEDS_MODULE_DRIVER";
    public static final String W_NEEDS_INVERTER_DRIVER = "NEEDS_INVERTER_DRIVER";
    public static final String W_MULTIPLE_DRIVERS      = "MULTIPLE_DRIVERS";

    private static final Set<String> LUMP_UNITS = Set.of("job", "lot", "set", "sets", "ls", "lumpsum", "lump sum");

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Template scope lines → suggestion scope items (order preserved). */
    public List<Map<String, Object>> expandTemplateScope(List<LeadScopeTemplateItemEntity> scopeLines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LeadScopeTemplateItemEntity s : scopeLines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", s.getActivity());
            m.put("specification", s.getSpecification());
            m.put("unit", s.getUnit());
            m.put("quantity", null); // scope quantities are optional/manual
            m.put("category", s.getCategory());
            // Share of overall project progress this line represents. Passed
            // through verbatim; callers that need a fallback for missing weights
            // apply it themselves (ScopeTemplateExpander.ensureWeights).
            m.put("weightPct", s.getWeightPct());
            out.add(m);
        }
        return out;
    }

    /** Backward-compatible entry point (no make-driven drivers). */
    public List<Map<String, Object>> expandTemplateBom(List<LeadBomTemplateItemEntity> bomLines,
                                                       CapacityInfo cap,
                                                       Map<String, String> siteVisit,
                                                       List<Map<String, Object>> warnings) {
        return expandTemplateBom(bomLines, cap, siteVisit, Map.of(), warnings);
    }

    /**
     * Template BOM lines → suggestion BOM lines with quantities computed from each
     * line's basis and the lead's capacity. {@code warnings} is appended to.
     *
     * Two passes so template ordering is irrelevant:
     *   1. driver lines (PER_WATT_PEAK → module count, PER_INVERTER_KW → inverter count),
     *      each reading the chosen make's numeric attribute from
     *      {@code driverAttrByTemplateItemId} (keyed by template-line id);
     *   2. everything else, with PER_MODULE / PER_INVERTER scaling off those counts.
     * Each output line also carries its basis metadata so the client can recompute live.
     */
    public List<Map<String, Object>> expandTemplateBom(List<LeadBomTemplateItemEntity> bomLines,
                                                       CapacityInfo cap,
                                                       Map<String, String> siteVisit,
                                                       Map<Long, BigDecimal> driverAttrByTemplateItemId,
                                                       List<Map<String, Object>> warnings) {
        Map<Long, BigDecimal> driverAttrs = driverAttrByTemplateItemId == null ? Map.of() : driverAttrByTemplateItemId;
        int n = bomLines.size();
        BigDecimal[] qtys = new BigDecimal[n];
        List<List<String>> flagsByLine = new ArrayList<>();
        for (int i = 0; i < n; i++) flagsByLine.add(new ArrayList<>());

        // Pass 1 — driver lines accumulate the module and inverter counts.
        BigDecimal moduleCount = null, inverterCount = null;
        int moduleDrivers = 0, inverterDrivers = 0;
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity t = bomLines.get(i);
            String basis = t.getBasis();
            if (!LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)
                    && !LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)) continue;
            BigDecimal qty = quantityForTemplateLine(t, cap, siteVisit, null, null,
                    driverAttrFor(driverAttrs, t), flagsByLine.get(i));
            qtys[i] = qty;
            if (qty != null) {
                if (LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)) {
                    moduleCount = nz(moduleCount).add(qty); moduleDrivers++;
                } else {
                    inverterCount = nz(inverterCount).add(qty); inverterDrivers++;
                }
            }
        }

        // Pass 2 — dependents (and all remaining bases).
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity t = bomLines.get(i);
            String basis = t.getBasis();
            if (LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)
                    || LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)) continue;
            qtys[i] = quantityForTemplateLine(t, cap, siteVisit, moduleCount, inverterCount,
                    driverAttrFor(driverAttrs, t), flagsByLine.get(i));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity t = bomLines.get(i);
            List<String> flags = flagsByLine.get(i);
            Map<String, Object> m = bomLineMap(
                    t.getScopeActivity(), t.getCategory(), t.getItemName(), t.getMake(),
                    t.getSpecification(), t.getUnit(), qtys[i], t.getDefaultUnitRate(), flags);
            // Basis metadata for live client-side recompute (LeadScopeService persists it on save).
            m.put("basis", t.getBasis());
            m.put("basisValue", t.getBasisValue());
            m.put("stepValue", t.getStepValue());
            m.put("siteVisitField", t.getSiteVisitField());
            m.put("driverAttr", driverAttrFor(driverAttrs, t));
            out.add(m);
        }
        if (moduleDrivers > 1 || inverterDrivers > 1) {
            addWarning(warnings, W_MULTIPLE_DRIVERS, defaultMessage(W_MULTIPLE_DRIVERS));
        }
        summarize(out, warnings); // surface per-line flags (capacity/site-visit/driver) as banner warnings
        return out;
    }

    /**
     * Rescale a past lead's BOM to the target capacity. Each mined row's basis is
     * resolved from the template (exact match_key, else fuzzy within category,
     * else inferred); the basis then decides whether the quantity holds, scales,
     * or is recomputed.
     */
    public List<Map<String, Object>> scaleMinedBom(List<LeadBomEntity> minedRows,
                                                   BigDecimal sourceKw,
                                                   BigDecimal targetKw,
                                                   List<LeadBomTemplateItemEntity> templateLines,
                                                   Map<String, String> minedScopeByItemKey,
                                                   Map<String, String> siteVisit,
                                                   List<Map<String, Object>> warnings) {
        // Index the template by match_key and by category for resolution.
        Map<String, LeadBomTemplateItemEntity> byKey = new LinkedHashMap<>();
        Map<String, List<LeadBomTemplateItemEntity>> byCategory = new LinkedHashMap<>();
        for (LeadBomTemplateItemEntity t : templateLines) {
            String key = t.getMatchKey() != null && !t.getMatchKey().isBlank()
                    ? t.getMatchKey() : normalize(t.getItemName());
            byKey.putIfAbsent(key, t);
            byCategory.computeIfAbsent(cat(t.getCategory()), k -> new ArrayList<>()).add(t);
        }

        BigDecimal factor = (sourceKw != null && sourceKw.signum() > 0 && targetKw != null)
                ? targetKw.divide(sourceKw, 6, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
        boolean farScale = factor.compareTo(BigDecimal.valueOf(3)) > 0
                || factor.compareTo(new BigDecimal("0.33")) < 0;

        List<Map<String, Object>> out = new ArrayList<>();
        for (LeadBomEntity row : minedRows) {
            List<String> flags = new ArrayList<>();
            String norm = normalize(row.getItemName());

            LeadBomTemplateItemEntity tpl = byKey.get(norm);
            String matchKind = tpl != null ? "EXACT" : null;
            if (tpl == null) {
                tpl = bestFuzzy(norm, cat(row.getCategory()), byCategory);
                matchKind = tpl != null ? "FUZZY" : "NONE";
            }

            String basis = tpl != null ? tpl.getBasis() : inferDefaultBasis(row);
            BigDecimal qty = applyBasisToMined(basis, row, tpl, factor, targetKw, siteVisit, flags);

            if ("FUZZY".equals(matchKind)) flags.add(W_AMBIGUOUS_MATCH);
            if ("NONE".equals(matchKind))  flags.add(W_BASIS_UNRESOLVED);
            if (isScaling(basis) && farScale && qty != null) flags.add(W_LARGE_SCALE_CHECK);

            String scopeActivity = tpl != null && tpl.getScopeActivity() != null
                    ? tpl.getScopeActivity()
                    : minedScopeByItemKey.get(norm); // the source line's own scope link

            Map<String, Object> m = bomLineMap(
                    scopeActivity, row.getCategory(), row.getItemName(), row.getMake(),
                    row.getSpecification(), row.getUnit(), qty, row.getUnitRate(), flags);
            out.add(m);
        }

        summarize(out, warnings);
        return out;
    }

    // ── Basis math ───────────────────────────────────────────────────────────

    /**
     * Quantity for a TEMPLATE line (no mined source qty to lean on).
     * {@code driverAttr} = this line's selected make numeric attribute (module Wp /
     * inverter kW) for the driver bases; {@code moduleCount}/{@code inverterCount} =
     * the counts resolved from the driver lines, for the dependent bases.
     */
    private BigDecimal quantityForTemplateLine(LeadBomTemplateItemEntity t, CapacityInfo cap,
                                               Map<String, String> siteVisit,
                                               BigDecimal moduleCount, BigDecimal inverterCount,
                                               BigDecimal driverAttr, List<String> flags) {
        String basis = t.getBasis() == null ? LeadBomTemplateItemEntity.BASIS_FIXED : t.getBasis();
        BigDecimal value = nz(t.getBasisValue());
        BigDecimal kw = cap != null && cap.isUsable() ? cap.scaleBase() : null;

        switch (basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:
                return round3(value);
            case LeadBomTemplateItemEntity.BASIS_PER_KW:
                if (kw == null) { flags.add(W_NEEDS_CAPACITY); return null; }
                return round3(value.multiply(kw));
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:
                if (kw == null) { flags.add(W_NEEDS_CAPACITY); return null; }
                BigDecimal step = nz(t.getStepValue());
                if (step.signum() <= 0) { flags.add(W_BASIS_UNRESOLVED); return null; }
                return ceilDiv(kw, step);
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT:
                BigDecimal fromVisit = fromSiteVisit(t.getSiteVisitField(), siteVisit);
                if (fromVisit == null) { flags.add(W_NEEDS_SITE_VISIT); return null; }
                return round3(fromVisit);
            case LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK:
                if (kw == null) { flags.add(W_NEEDS_CAPACITY); return null; }
                if (driverAttr == null || driverAttr.signum() <= 0) { flags.add(W_NEEDS_MODULE_WATT); return null; }
                return ceilDiv(kw.multiply(BigDecimal.valueOf(1000)), driverAttr); // ceil(kW*1000 / Wp)
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW:
                if (kw == null) { flags.add(W_NEEDS_CAPACITY); return null; }
                if (driverAttr == null || driverAttr.signum() <= 0) { flags.add(W_NEEDS_INVERTER_KW); return null; }
                return ceilDiv(kw, driverAttr); // ceil(kW / invKw)
            case LeadBomTemplateItemEntity.BASIS_PER_MODULE:
                if (moduleCount == null) { flags.add(W_NEEDS_MODULE_DRIVER); return null; }
                return ceilMul(value, moduleCount); // ceil(factor * module count)
            case LeadBomTemplateItemEntity.BASIS_PER_INVERTER:
                if (inverterCount == null) { flags.add(W_NEEDS_INVERTER_DRIVER); return null; }
                return ceilMul(value, inverterCount); // ceil(factor * inverter count)
            default:
                flags.add(W_BASIS_UNRESOLVED);
                return round3(value);
        }
    }

    /** Apply a resolved basis to a MINED row (which already carries a real qty). */
    private BigDecimal applyBasisToMined(String basis, LeadBomEntity row, LeadBomTemplateItemEntity tpl,
                                         BigDecimal factor, BigDecimal targetKw,
                                         Map<String, String> siteVisit, List<String> flags) {
        BigDecimal minedQty = nz(row.getQuantity());
        if (basis == null) basis = inferDefaultBasis(row);

        switch (basis) {
            case LeadBomTemplateItemEntity.BASIS_FIXED:
                return round3(minedQty);                       // hold
            case LeadBomTemplateItemEntity.BASIS_PER_KW:
                return round3(minedQty.multiply(factor));      // scale
            case LeadBomTemplateItemEntity.BASIS_PER_STEP:
                BigDecimal step = tpl != null ? nz(tpl.getStepValue()) : BigDecimal.ZERO;
                if (step.signum() <= 0) {
                    // Infer step from the source: sourceKw / minedQty.
                    if (minedQty.signum() > 0 && factor.signum() > 0) {
                        // targetKw / factor = sourceKw; step = sourceKw / minedQty
                        BigDecimal sourceKw = targetKw.divide(factor, 6, RoundingMode.HALF_UP);
                        step = sourceKw.divide(minedQty, 6, RoundingMode.HALF_UP);
                    }
                    flags.add(W_BASIS_UNRESOLVED);
                }
                if (step.signum() <= 0 || targetKw == null) return round3(minedQty);
                return ceilDiv(targetKw, step);
            case LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT:
                BigDecimal fromVisit = fromSiteVisit(tpl != null ? tpl.getSiteVisitField() : null, siteVisit);
                if (fromVisit == null) { flags.add(W_NEEDS_SITE_VISIT); return null; }
                return round3(fromVisit);
            default:
                return round3(minedQty.multiply(factor));
        }
    }

    /**
     * Basis when a mined line matched no template line. Discrete one-offs
     * (a lump/lot/set, or a small integer count) HOLD — this is what stops one
     * lightning arrestor from becoming five. Everything else scales.
     */
    public String inferDefaultBasis(LeadBomEntity row) {
        String unit = row.getUnit() == null ? "" : row.getUnit().trim().toLowerCase();
        BigDecimal qty = nz(row.getQuantity());

        if (LUMP_UNITS.contains(unit)) return LeadBomTemplateItemEntity.BASIS_FIXED;

        boolean countUnit = unit.isEmpty()
                || unit.startsWith("no") || unit.equals("nos") || unit.equals("pcs")
                || unit.equals("pc") || unit.equals("piece") || unit.equals("pieces")
                || unit.equals("set") || unit.equals("unit") || unit.equals("units");
        boolean smallInteger = qty.stripTrailingZeros().scale() <= 0
                && qty.compareTo(BigDecimal.valueOf(2)) <= 0
                && qty.signum() > 0;
        if (countUnit && smallInteger) return LeadBomTemplateItemEntity.BASIS_FIXED;

        return LeadBomTemplateItemEntity.BASIS_PER_KW; // proportional
    }

    // ── Matching ───────────────────────────────────────────────────────────────

    /** Lowercase, strip punctuation, collapse whitespace, drop bracketed make tokens. */
    public String normalize(String itemName) {
        if (itemName == null) return "";
        String s = itemName.toLowerCase();
        s = s.replaceAll("\\(.*?\\)", " ");        // drop "(...)" make/spec asides
        s = s.replaceAll("[^a-z0-9 ]", " ");        // punctuation → space
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** Best token-overlap match within the same category, above the threshold. */
    private LeadBomTemplateItemEntity bestFuzzy(String norm, String category,
                                                Map<String, List<LeadBomTemplateItemEntity>> byCategory) {
        List<LeadBomTemplateItemEntity> candidates = byCategory.get(category);
        if (candidates == null || candidates.isEmpty()) return null;

        Set<String> a = tokens(norm);
        if (a.isEmpty()) return null;

        LeadBomTemplateItemEntity best = null;
        double bestScore = 0.6; // threshold
        for (LeadBomTemplateItemEntity t : candidates) {
            String key = t.getMatchKey() != null && !t.getMatchKey().isBlank()
                    ? t.getMatchKey() : normalize(t.getItemName());
            double score = jaccard(a, tokens(key));
            if (score >= bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    private Set<String> tokens(String s) {
        Set<String> out = new HashSet<>(Arrays.asList(s.split(" ")));
        out.remove("");
        return out;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isScaling(String basis) {
        return LeadBomTemplateItemEntity.BASIS_PER_KW.equals(basis);
    }

    /** Null-safe driver-attribute lookup (template ids can be null in unit tests; Map.of() rejects null keys). */
    private static BigDecimal driverAttrFor(Map<Long, BigDecimal> driverAttrs, LeadBomTemplateItemEntity t) {
        if (driverAttrs == null || t.getId() == null) return null;
        return driverAttrs.get(t.getId());
    }

    private BigDecimal fromSiteVisit(String field, Map<String, String> siteVisit) {
        if (field == null || field.isBlank() || siteVisit == null) return null;
        String raw = siteVisit.get(field);
        if (raw == null || raw.isBlank()) return null;
        // Reuse the same parsing rule as capacity: leading number wins.
        var vu = com.istlgroup.istl_group_crm_backend.util.CapacityUtil.parseValueUnit(raw);
        return vu.value();
    }

    private Map<String, Object> bomLineMap(String scopeActivity, String category, String itemName,
                                           String make, String specification, String unit,
                                           BigDecimal quantity, BigDecimal unitRate, List<String> flags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scopeActivity", scopeActivity);
        m.put("category", category);
        m.put("itemName", itemName);
        m.put("make", make);
        m.put("specification", specification);
        m.put("unit", unit);
        m.put("quantity", quantity);
        m.put("unitRate", unitRate);
        m.put("flags", flags);
        m.put("review", !flags.isEmpty());
        return m;
    }

    @SuppressWarnings("unchecked")
    private void summarize(List<Map<String, Object>> lines, List<Map<String, Object>> warnings) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> l : lines) {
            for (String f : (List<String>) l.getOrDefault("flags", List.of())) {
                if (seen.add(f)) addWarning(warnings, f, defaultMessage(f));
            }
        }
    }

    private void addWarning(List<Map<String, Object>> warnings, String code, String message) {
        for (Map<String, Object> w : warnings) if (code.equals(w.get("code"))) return; // dedupe
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code);
        w.put("message", message);
        warnings.add(w);
    }

    private String defaultMessage(String code) {
        switch (code) {
            case W_LARGE_SCALE_CHECK: return "Some quantities were scaled from a job of a very different size — review them.";
            case W_AMBIGUOUS_MATCH:   return "Some items matched the template only loosely — check their quantities.";
            case W_BASIS_UNRESOLVED:  return "Some items had no matching template rule — a sensible default was used; verify.";
            case W_NEEDS_SITE_VISIT:  return "Some quantities come from the site visit and were left blank — fill them in.";
            case W_NEEDS_CAPACITY:    return "This lead has no usable capacity, so per-kW quantities were left blank.";
            case W_NEEDS_MODULE_WATT: return "Pick a module make with a wattage so the module count can be computed.";
            case W_NEEDS_INVERTER_KW: return "Pick an inverter make with a kW rating so the inverter count can be computed.";
            case W_NEEDS_MODULE_DRIVER:   return "A per-module item couldn't size — there is no module (watt-peak) line to scale from.";
            case W_NEEDS_INVERTER_DRIVER: return "A per-inverter item couldn't size — there is no inverter line to scale from.";
            case W_MULTIPLE_DRIVERS:  return "More than one module/inverter driver line was found — counts were summed; verify.";
            default: return code;
        }
    }

    private static String cat(String c) { return c == null ? "" : c.trim().toLowerCase(); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal round3(BigDecimal v) { return v == null ? null : v.setScale(3, RoundingMode.HALF_UP); }

    /** ceil(a / b) as a whole number, scale 3 for schema consistency. */
    private static BigDecimal ceilDiv(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) return null;
        BigDecimal q = a.divide(b, 0, RoundingMode.CEILING);
        return q.setScale(3, RoundingMode.HALF_UP);
    }

    /** ceil(factor × count) as a whole number, scale 3 for schema consistency. */
    private static BigDecimal ceilMul(BigDecimal factor, BigDecimal count) {
        if (factor == null || count == null) return null;
        BigDecimal q = factor.multiply(count).setScale(0, RoundingMode.CEILING);
        return q.setScale(3, RoundingMode.HALF_UP);
    }
}
