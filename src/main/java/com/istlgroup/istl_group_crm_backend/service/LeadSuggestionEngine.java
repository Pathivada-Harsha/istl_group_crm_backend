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
            out.add(m);
        }
        return out;
    }

    /**
     * Template BOM lines → suggestion BOM lines with quantities computed from each
     * line's basis and the lead's capacity. {@code warnings} is appended to.
     */
    public List<Map<String, Object>> expandTemplateBom(List<LeadBomTemplateItemEntity> bomLines,
                                                       CapacityInfo cap,
                                                       Map<String, String> siteVisit,
                                                       List<Map<String, Object>> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean capacityMissingFlagged = false;

        for (LeadBomTemplateItemEntity t : bomLines) {
            List<String> flags = new ArrayList<>();
            BigDecimal qty = quantityForTemplateLine(t, cap, siteVisit, flags);
            if (flags.contains(W_NEEDS_CAPACITY)) capacityMissingFlagged = true;

            Map<String, Object> m = bomLineMap(
                    t.getScopeActivity(), t.getCategory(), t.getItemName(), t.getMake(),
                    t.getSpecification(), t.getUnit(), qty, t.getDefaultUnitRate(), flags);
            out.add(m);
        }
        if (capacityMissingFlagged) {
            addWarning(warnings, W_NEEDS_CAPACITY,
                    "This lead has no usable capacity, so per-kW quantities were left blank — fill them in.");
        }
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

    /** Quantity for a TEMPLATE line (no mined source qty to lean on). */
    private BigDecimal quantityForTemplateLine(LeadBomTemplateItemEntity t, CapacityInfo cap,
                                               Map<String, String> siteVisit, List<String> flags) {
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
}
