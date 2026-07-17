package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomSaveRequest;

/**
 * End-to-end suggestion tests against the real schema. @Transactional rolls back,
 * and a unique test-only project type keeps mining from finding real leads.
 * Acts as SUPERADMIN so LeadScopeService#authorize takes its level<=2 bypass.
 */
@SpringBootTest
@Transactional
class LeadSuggestionServiceTest {

    private static final Long USER_ID = 1L;
    private static final String ROLE = "SUPERADMIN";
    private static final String TYPE = "ZZ_TEST_ROOFTOP"; // unique — no real leads use it

    @Autowired private LeadScopeService leadScopeService;
    @Autowired private LeadsRepo leadsRepo;
    @Autowired private LeadScopeTemplateRepo templateRepo;
    @Autowired private LeadScopeTemplateItemRepo templateScopeRepo;
    @Autowired private LeadBomTemplateItemRepo templateBomRepo;

    // ── seeding helpers ─────────────────────────────────────────────────────────

    private Long newLead(String capacity, String unit) {
        LeadsEntity l = new LeadsEntity();
        l.setName("Suggest test lead");
        l.setSubGroupName(TYPE);
        l.setCapacity(capacity);
        l.setCapacityUnit(unit);
        l.setCreatedBy(USER_ID);
        return leadsRepo.save(l).getId();
    }

    private Long newTemplate() {
        LeadScopeTemplateEntity t = new LeadScopeTemplateEntity();
        t.setProjectType(TYPE);
        t.setName("Test rooftop template");
        t.setIsActive(true);
        t.setCreatedBy(USER_ID);
        return templateRepo.save(t).getId();
    }

    private void addTemplateScope(Long templateId, String activity) {
        LeadScopeTemplateItemEntity s = new LeadScopeTemplateItemEntity();
        s.setTemplateId(templateId);
        s.setProjectType(TYPE);
        s.setActivity(activity);
        templateScopeRepo.save(s);
    }

    private void addTemplateBom(Long templateId, String item, String category, String basis,
                                String basisValue, String stepValue, String scopeActivity) {
        LeadBomTemplateItemEntity b = new LeadBomTemplateItemEntity();
        b.setTemplateId(templateId);
        b.setProjectType(TYPE);
        b.setItemName(item);
        b.setMatchKey(item.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim());
        b.setCategory(category);
        b.setBasis(basis);
        if (basisValue != null) b.setBasisValue(new BigDecimal(basisValue));
        if (stepValue != null) b.setStepValue(new BigDecimal(stepValue));
        b.setScopeActivity(scopeActivity);
        templateBomRepo.save(b);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bomLines(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("bomLines");
    }

    private static BigDecimal qty(Map<String, Object> line) {
        Object q = line.get("quantity");
        return q == null ? null : new BigDecimal(String.valueOf(q));
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scopeItems(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("scopeItems");
    }

    @Test
    void templatePathWhenNoHistory() throws Exception {
        Long tpl = newTemplate();
        addTemplateScope(tpl, "Procurement");
        addTemplateBom(tpl, "PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, "Procurement");
        addTemplateBom(tpl, "Inverter", "Inverter", LeadBomTemplateItemEntity.BASIS_PER_STEP, null, "50", "Procurement");
        addTemplateBom(tpl, "Lightning Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, "Installation");

        Long lead = newLead("50", "kW");
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "both", USER_ID, ROLE);

        assertEquals("TEMPLATE", data.get("source"));
        List<Map<String, Object>> bom = bomLines(data);
        assertEquals(0, qty(bom.get(0)).compareTo(new BigDecimal("85.000"))); // 1.7 × 50
        assertEquals(0, qty(bom.get(1)).compareTo(new BigDecimal("1.000")));  // ceil(50/50)
        assertEquals(0, qty(bom.get(2)).compareTo(new BigDecimal("1.000")));  // fixed
    }

    /**
     * A template with only BOM lines (each tagged to a scope activity) and NO
     * explicit scope lines must still yield scope items — derived from the
     * distinct scope activities on the BOM. This was the "Suggest scope shows
     * nothing while BOM works" bug.
     */
    @Test
    void scopeDerivedFromBomActivitiesWhenNoScopeLines() throws Exception {
        Long tpl = newTemplate();
        // No addTemplateScope() calls — only BOM lines, tagged by activity.
        addTemplateBom(tpl, "PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, "Procurement");
        addTemplateBom(tpl, "Lightning Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, "Installation");
        addTemplateBom(tpl, "Cable", "Cable", LeadBomTemplateItemEntity.BASIS_PER_KW, "5", null, "Procurement");

        Long lead = newLead("50", "kW");
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "scope", USER_ID, ROLE);

        assertEquals("TEMPLATE", data.get("source"));
        List<Map<String, Object>> scope = scopeItems(data);
        // Distinct activities, first-seen order: Procurement, Installation.
        assertEquals(2, scope.size());
        assertEquals("Procurement", scope.get(0).get("activity"));
        assertEquals("Installation", scope.get(1).get("activity"));
    }

    /**
     * With NO template for the project type, mining a similar past job kicks in.
     * Quantities scale by the heuristic (bulk scales, a small discrete count
     * holds) and the real historical rate is carried through.
     */
    @Test
    void minedPathUsedWhenNoTemplate() throws Exception {
        Long past = newLead("10", "kW");
        BomLineRequest m = new BomLineRequest();
        m.setItemName("PV Modules"); m.setQuantity(new BigDecimal("17")); m.setUnitRate(new BigDecimal("5000")); m.setUnit("Nos");
        BomLineRequest a = new BomLineRequest();
        a.setItemName("Lightning Arrestor"); a.setQuantity(new BigDecimal("1")); a.setUnitRate(new BigDecimal("1200")); a.setUnit("Nos");
        BomSaveRequest req = new BomSaveRequest(); req.setLines(List.of(m, a));
        leadScopeService.saveBom(past, req, USER_ID, ROLE);

        Long lead = newLead("50", "kW"); // target — factor 5, no template exists
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "bom", USER_ID, ROLE);

        assertEquals("MINED", data.get("source"));
        assertEquals(past, data.get("sourceLeadId"));
        List<Map<String, Object>> bom = bomLines(data);
        Map<String, Object> modules = bom.stream().filter(b -> "PV Modules".equals(b.get("itemName"))).findFirst().orElseThrow();
        Map<String, Object> arrestor = bom.stream().filter(b -> "Lightning Arrestor".equals(b.get("itemName"))).findFirst().orElseThrow();
        assertEquals(0, qty(modules).compareTo(new BigDecimal("85.000")));  // 17 × 5 (bulk scales)
        assertEquals(0, qty(arrestor).compareTo(new BigDecimal("1.000")));  // small count holds
        assertEquals(0, new BigDecimal(String.valueOf(modules.get("unitRate"))).compareTo(new BigDecimal("5000.00")));
    }

    /** The template is the standard: it wins even when a similar past job exists. */
    @Test
    void templateWinsOverMining() throws Exception {
        // A past job of the same type at the same capacity.
        Long past = newLead("50", "kW");
        BomLineRequest m = new BomLineRequest();
        m.setItemName("Old Panel"); m.setQuantity(new BigDecimal("90")); m.setUnitRate(new BigDecimal("4000")); m.setUnit("Nos");
        BomSaveRequest req = new BomSaveRequest(); req.setLines(List.of(m));
        leadScopeService.saveBom(past, req, USER_ID, ROLE);

        // A template for the same project type.
        Long tpl = newTemplate();
        addTemplateScope(tpl, "Detailed Engineering & Design");
        addTemplateBom(tpl, "PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, "Detailed Engineering & Design");

        Long lead = newLead("50", "kW");
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "both", USER_ID, ROLE);

        assertEquals("TEMPLATE", data.get("source"));           // template, not the past job
        assertEquals(Boolean.TRUE, data.get("hasTemplate"));
        List<Map<String, Object>> bom = bomLines(data);
        assertEquals(1, bom.size());
        assertEquals("PV Modules", bom.get(0).get("itemName"));  // from the template
        assertEquals(0, qty(bom.get(0)).compareTo(new BigDecimal("85.000"))); // 1.7 × 50 (kW-scaled)
    }

    @Test
    void templateInfoReportsWhetherATemplateExists() throws Exception {
        Long lead = newLead("50", "kW");
        assertEquals(Boolean.FALSE, leadScopeService.templateInfo(lead).get("hasTemplate"));
        Long tpl = newTemplate();
        addTemplateScope(tpl, "Procurement");
        assertEquals(Boolean.TRUE, leadScopeService.templateInfo(lead).get("hasTemplate"));
    }

    @Test
    void noCapacityUsesTemplateAndBlanksPerKw() throws Exception {
        Long tpl = newTemplate();
        addTemplateBom(tpl, "PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, null);
        addTemplateBom(tpl, "Arrestor", "Safety", LeadBomTemplateItemEntity.BASIS_FIXED, "1", null, null);

        Long lead = newLead("", ""); // no parseable capacity
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "bom", USER_ID, ROLE);

        assertEquals("TEMPLATE", data.get("source"));
        List<Map<String, Object>> bom = bomLines(data);
        assertNull(qty(bom.get(0)));                                   // PER_KW blanked
        assertEquals(0, qty(bom.get(1)).compareTo(new BigDecimal("1.000"))); // FIXED computes
        assertTrue(((List<?>) data.get("warnings")).stream()
                .anyMatch(w -> LeadSuggestionEngine.W_NEEDS_CAPACITY.equals(((Map<?, ?>) w).get("code"))));
    }

    @Test
    void noTemplateAndNoHistoryReturnsNone() throws Exception {
        Long lead = newLead("50", "kW");
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "both", USER_ID, ROLE);
        assertEquals("NONE", data.get("source"));
        assertTrue(bomLines(data).isEmpty());
        assertTrue(((List<?>) data.get("warnings")).stream()
                .anyMatch(w -> LeadSuggestionEngine.W_NO_TEMPLATE_NO_HISTORY.equals(((Map<?, ?>) w).get("code"))));
    }

    @Test
    void mwNormalisesToKwForScaling() throws Exception {
        Long tpl = newTemplate();
        addTemplateBom(tpl, "PV Modules", "Modules", LeadBomTemplateItemEntity.BASIS_PER_KW, "1.7", null, null);
        Long lead = newLead("1", "MW"); // = 1000 kW
        Map<String, Object> data = leadScopeService.suggestScopeAndBom(lead, "bom", USER_ID, ROLE);
        assertEquals("TEMPLATE", data.get("source"));
        assertEquals(0, qty(bomLines(data).get(0)).compareTo(new BigDecimal("1700.000"))); // 1.7 × 1000
    }

    @Test
    void unauthorizedCallerRejected() {
        Long lead = newLead("50", "kW");
        // A non-privileged role that is neither creator nor assignee, with no grant.
        assertThrows(CustomException.class,
                () -> leadScopeService.suggestScopeAndBom(lead, "both", 999999L, "BD_EXECUTIVE"));
    }
}
