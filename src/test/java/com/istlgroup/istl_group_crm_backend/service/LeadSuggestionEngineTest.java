package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.LeadBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;

/**
 * Pure unit tests for the scaling / basis-resolution math — no Spring, no DB.
 */
class LeadSuggestionEngineTest {

    private final LeadSuggestionEngine engine = new LeadSuggestionEngine();

    private static LeadBomTemplateItemEntity tpl(String item, String category, String basis,
                                                 String basisValue, String stepValue, String scopeActivity) {
        LeadBomTemplateItemEntity t = new LeadBomTemplateItemEntity();
        t.setItemName(item);
        t.setMatchKey(item.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim());
        t.setCategory(category);
        t.setBasis(basis);
        if (basisValue != null) t.setBasisValue(new BigDecimal(basisValue));
        if (stepValue != null) t.setStepValue(new BigDecimal(stepValue));
        t.setScopeActivity(scopeActivity);
        return t;
    }

    private static LeadBomEntity mined(String item, String category, String qty, String unit, String rate) {
        LeadBomEntity b = new LeadBomEntity();
        b.setItemName(item);
        b.setCategory(category);
        b.setQuantity(new BigDecimal(qty));
        b.setUnit(unit);
        b.setUnitRate(new BigDecimal(rate));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<String> flags(Map<String, Object> line) {
        return (List<String>) line.get("flags");
    }

    private static BigDecimal qty(Map<String, Object> line) {
        Object q = line.get("quantity");
        return q == null ? null : (BigDecimal) q;
    }

    // ── Template expansion ─────────────────────────────────────────────────────

    @Test
    void templateBasisComputesQuantitiesFromCapacity() {
        CapacityInfo cap = CapacityUtil.parse("50", "kW", "Solar_Rooftop"); // 50 kW
        List<LeadBomTemplateItemEntity> t = List.of(
                tpl("PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, "Procurement"),
                tpl("Inverter", "Inverter", LeadBomTemplateItemEntity.BASIS_PER_STEP, null, "50", "Procurement"),
                tpl("Lightning Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, "Installation")
        );
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> out = engine.expandTemplateBom(t, cap, Map.of(), warnings);

        assertEquals(0, qty(out.get(0)).compareTo(new BigDecimal("85.000"))); // 1.7 × 50
        assertEquals(0, qty(out.get(1)).compareTo(new BigDecimal("1.000")));  // ceil(50/50)
        assertEquals(0, qty(out.get(2)).compareTo(new BigDecimal("1.000")));  // fixed
        assertTrue(warnings.isEmpty());
    }

    @Test
    void perStepRoundsUp() {
        CapacityInfo cap = CapacityUtil.parse("120", "kW", "Solar_Rooftop");
        List<Map<String, Object>> out = engine.expandTemplateBom(
                List.of(tpl("Inverter", "Inverter", LeadBomTemplateItemEntity.BASIS_PER_STEP, null, "50", null)),
                cap, Map.of(), new ArrayList<>());
        assertEquals(0, qty(out.get(0)).compareTo(new BigDecimal("3.000"))); // ceil(120/50) = 3
    }

    @Test
    void templateWithoutCapacityBlanksPerKwAndWarns() {
        CapacityInfo cap = CapacityUtil.parse("", "", "Solar_Rooftop"); // unusable
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> out = engine.expandTemplateBom(
                List.of(
                    tpl("PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, null),
                    tpl("Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, null)
                ), cap, Map.of(), warnings);

        assertNull(qty(out.get(0)));                                  // PER_KW blanked
        assertEquals(0, qty(out.get(1)).compareTo(new BigDecimal("1.000"))); // FIXED still computes
        assertTrue(warnings.stream().anyMatch(w -> LeadSuggestionEngine.W_NEEDS_CAPACITY.equals(w.get("code"))));
    }

    // ── Mined scaling — the hard case ───────────────────────────────────────────

    @Test
    void minedScalingHoldsFixedAndScalesPerKw() {
        // Source job at 10 kW → target 50 kW (factor 5).
        List<LeadBomEntity> minedRows = List.of(
                mined("PV Modules", "Modules", "17", "Nos", "5000"),        // template says PER_KW → scale
                mined("Lightning Arrestor", "Safety", "1", "Nos", "1200")   // template says FIXED  → hold
        );
        List<LeadBomTemplateItemEntity> template = List.of(
                tpl("PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, "Procurement"),
                tpl("Lightning Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, "Installation")
        );
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> out = engine.scaleMinedBom(
                minedRows, new BigDecimal("10"), new BigDecimal("50"),
                template, Map.of(), Map.of(), warnings);

        assertEquals(0, qty(out.get(0)).compareTo(new BigDecimal("85.000"))); // 17 × 5
        assertEquals(0, qty(out.get(1)).compareTo(new BigDecimal("1.000")));  // held, NOT 5
        // matched lines carry the template's rate/scope but keep the mined make/rate
        assertEquals("Procurement", out.get(0).get("scopeActivity"));
    }

    @Test
    void unmatchedSmallCountDefaultsToFixed() {
        // No template at all → inferDefaultBasis. A single "1 Nos" item must hold.
        List<LeadBomEntity> minedRows = List.of(
                mined("ACDB Box", "ACDB", "1", "Nos", "3000"),
                mined("DC Cable", "Cable", "150", "Meters", "45")
        );
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> out = engine.scaleMinedBom(
                minedRows, new BigDecimal("10"), new BigDecimal("50"),
                List.of(), Map.of(), Map.of(), warnings);

        assertEquals(0, qty(out.get(0)).compareTo(new BigDecimal("1.000")));    // held
        assertEquals(0, qty(out.get(1)).compareTo(new BigDecimal("750.000")));  // 150 × 5
        assertTrue(flags(out.get(0)).contains(LeadSuggestionEngine.W_BASIS_UNRESOLVED));
    }

    @Test
    void largeScaleIsFlaggedForReview() {
        List<Map<String, Object>> out = engine.scaleMinedBom(
                List.of(mined("PV Modules", "Modules", "2", "Nos", "5000")),
                new BigDecimal("2"), new BigDecimal("100"),  // factor 50
                List.of(tpl("PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, null)),
                Map.of(), Map.of(), new ArrayList<>());
        assertTrue(flags(out.get(0)).contains(LeadSuggestionEngine.W_LARGE_SCALE_CHECK));
    }

    @Test
    void inferDefaultBasisHeuristic() {
        assertEquals(LeadBomTemplateItemEntity.BASIS_FIXED,
                engine.inferDefaultBasis(mined("Arrestor", "Safety", "1", "Nos", "0")));
        assertEquals(LeadBomTemplateItemEntity.BASIS_FIXED,
                engine.inferDefaultBasis(mined("Structure", "Str", "1", "Lot", "0")));
        assertEquals(LeadBomTemplateItemEntity.BASIS_PER_KW,
                engine.inferDefaultBasis(mined("Modules", "Mod", "85", "Nos", "0")));
        assertEquals(LeadBomTemplateItemEntity.BASIS_PER_KW,
                engine.inferDefaultBasis(mined("Cable", "Cbl", "150", "Meters", "0")));
    }

    @Test
    void normalizeStripsBracketsAndPunctuation() {
        // Bracketed make/spec asides are dropped; punctuation collapses to spaces.
        assertEquals("pv modules", engine.normalize("PV Modules (590Wp N-Type)"));
        assertEquals("dc cable 6 sq mm", engine.normalize("DC Cable, 6 sq.mm"));
        assertEquals("inverter", engine.normalize("Inverter (50kW, 3-phase)"));
    }
}
