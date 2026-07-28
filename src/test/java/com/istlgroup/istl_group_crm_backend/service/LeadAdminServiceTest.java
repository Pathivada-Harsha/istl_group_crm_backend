package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateBomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateBomLinesRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeLinesRequest;

/** CRUD + two-step delete for the template admin, rolled back after each test. */
@SpringBootTest
@Transactional
class LeadAdminServiceTest {

    private static final Long USER = 1L;
    private static final String TYPE = "ZZ_TEST_ADMIN_TYPE";

    @Autowired private LeadAdminService service;

    private Long createTemplate() throws Exception {
        TemplateHeaderRequest h = new TemplateHeaderRequest();
        h.setProjectType(TYPE);
        h.setName("Test");
        h.setIsActive(true);
        return (Long) service.createTemplate(h, USER).get("id");
    }

    @Test
    void createGetAndReplaceLines() throws Exception {
        Long id = createTemplate();

        // scope lines
        TemplateScopeLineRequest s = new TemplateScopeLineRequest();
        s.setActivity("Procurement");
        TemplateScopeLinesRequest sReq = new TemplateScopeLinesRequest();
        sReq.setItems(List.of(s));
        service.saveScopeLines(id, sReq, USER);

        // bom lines
        TemplateBomLineRequest b = new TemplateBomLineRequest();
        b.setItemName("PV Modules");
        b.setScopeActivity("Procurement");
        b.setBasis(LeadBomTemplateItemEntity.BASIS_PER_KW);
        b.setBasisValue(new BigDecimal("1.7"));
        TemplateBomLinesRequest bReq = new TemplateBomLinesRequest();
        bReq.setLines(List.of(b));
        service.saveBomLines(id, bReq, USER);

        Map<String, Object> full = service.getTemplate(id);
        assertEquals(TYPE, full.get("projectType"));
        assertEquals(1, ((List<?>) full.get("scopeItems")).size());
        List<?> bom = (List<?>) full.get("bomItems");
        assertEquals(1, bom.size());
        // match_key is derived so mined lines can resolve against it later
        @SuppressWarnings("unchecked")
        Map<String, Object> bomLine = (Map<String, Object>) bom.get(0);
        assertEquals("Procurement", bomLine.get("scopeActivity"));
        assertEquals(LeadBomTemplateItemEntity.BASIS_PER_KW, bomLine.get("basis"));
    }

    @Test
    void replacingLinesSoftDeletesTheAbsent() throws Exception {
        Long id = createTemplate();
        service.saveScopeLines(id, lines(line("A", null), line("B", null)), USER);
        assertEquals(2, ((List<?>) service.getTemplate(id).get("scopeItems")).size());

        // Build the second request from fresh line objects. Saving normalises
        // weights onto the request in place, so a line carried over from the
        // first save would arrive holding its 50% share and be rejected for not
        // totalling 100 — which is the real API's behaviour, not this test's subject.
        service.saveScopeLines(id, lines(line("A", null)), USER);
        assertEquals(1, ((List<?>) service.getTemplate(id).get("scopeItems")).size());
    }

    @Test
    void invalidBasisRejected() throws Exception {
        Long id = createTemplate();
        TemplateBomLineRequest b = new TemplateBomLineRequest();
        b.setItemName("X"); b.setBasis("NONSENSE");
        TemplateBomLinesRequest req = new TemplateBomLinesRequest(); req.setLines(List.of(b));
        assertThrows(CustomException.class, () -> service.saveBomLines(id, req, USER));
    }

    @Test
    void twoStepDeleteDeactivatesThenRemoves() throws Exception {
        Long id = createTemplate();

        // First delete on an active template → deactivate, still present.
        service.deleteTemplate(id);
        Map<String, Object> after = service.getTemplate(id);
        assertFalse((Boolean) after.get("isActive"));

        // Second delete on the (now inactive) template → soft-delete, gone.
        service.deleteTemplate(id);
        assertThrows(CustomException.class, () -> service.getTemplate(id));
    }

    @Test
    void listExcludesDeleted() throws Exception {
        Long id = createTemplate();
        assertTrue(service.listTemplates().stream().anyMatch(t -> id.equals(t.get("id"))));
        service.deleteTemplate(id); // deactivate
        service.deleteTemplate(id); // soft-delete
        assertFalse(service.listTemplates().stream().anyMatch(t -> id.equals(t.get("id"))));
    }

    // ── Scope weights ──────────────────────────────────────────────────────────

    private static TemplateScopeLineRequest line(String activity, String weight) {
        TemplateScopeLineRequest r = new TemplateScopeLineRequest();
        r.setActivity(activity);
        if (weight != null) { r.setWeightPct(new BigDecimal(weight)); r.setWeightManual(true); }
        return r;
    }

    private static TemplateScopeLinesRequest lines(TemplateScopeLineRequest... items) {
        TemplateScopeLinesRequest req = new TemplateScopeLinesRequest();
        req.setItems(List.of(items));
        return req;
    }

    /** Saved weights, in seq order. */
    @SuppressWarnings("unchecked")
    private List<BigDecimal> savedWeights(Long templateId) throws Exception {
        return ((List<Map<String, Object>>) service.getTemplate(templateId).get("scopeItems"))
                .stream().map(m -> (BigDecimal) m.get("weightPct")).toList();
    }

    @Test
    void weightsTotallingOneHundredAreSavedVerbatim() throws Exception {
        Long id = createTemplate();
        service.saveScopeLines(id, lines(line("Engineering", "20"), line("Procurement", "55"),
                line("Commissioning", "25")), USER);

        List<BigDecimal> w = savedWeights(id);
        assertEquals(0, w.get(0).compareTo(new BigDecimal("20")));
        assertEquals(0, w.get(1).compareTo(new BigDecimal("55")));
        assertEquals(0, w.get(2).compareTo(new BigDecimal("25")));
    }

    @Test
    void aTotalThatIsGenuinelyWrongIsRejectedAndStatesTheTotal() throws Exception {
        Long id = createTemplate();
        TemplateScopeLinesRequest req = lines(line("Engineering", "40"), line("Procurement", "50"));

        CustomException e = assertThrows(CustomException.class, () -> service.saveScopeLines(id, req, USER));
        assertTrue(e.getMessage().contains("90.00"), e.getMessage());
    }

    @Test
    void aZeroWeightIsRejectedAndNamesTheLine() throws Exception {
        Long id = createTemplate();
        TemplateScopeLinesRequest req = lines(line("Engineering", "100"), line("Commissioning", "0"));

        CustomException e = assertThrows(CustomException.class, () -> service.saveScopeLines(id, req, USER));
        assertTrue(e.getMessage().contains("Commissioning"), e.getMessage());
    }

    /**
     * Retyping the displayed two-decimal values shifts the true total slightly.
     * That much is absorbed silently, onto the largest line.
     */
    @Test
    void displayRoundingIsAbsorbedOntoTheLargestLine() throws Exception {
        Long id = createTemplate();
        // 99.99 — off by 0.01, within the 3 × 0.005 that two-decimal display allows.
        service.saveScopeLines(id, lines(line("Engineering", "24.99"), line("Procurement", "50"),
                line("Commissioning", "25")), USER);

        List<BigDecimal> w = savedWeights(id);
        assertEquals(0, w.get(0).compareTo(new BigDecimal("24.99")));
        assertEquals(0, w.get(1).compareTo(new BigDecimal("50.01")), "delta belongs on the largest line");
        assertEquals(0, w.get(2).compareTo(new BigDecimal("25")));
        assertEquals(0, w.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("100")));
    }

    /** A template that predates weights saves as an even split rather than failing. */
    @Test
    void missingWeightsBecomeAnEvenSplit() throws Exception {
        Long id = createTemplate();
        service.saveScopeLines(id, lines(line("A", null), line("B", null),
                line("C", null), line("D", null)), USER);

        List<BigDecimal> w = savedWeights(id);
        for (BigDecimal x : w) assertEquals(0, x.compareTo(new BigDecimal("25")));
    }

    /** Three-way split: the remainder lands on the last line so the total is exact. */
    @Test
    void evenSplitRemainderKeepsTheTotalExact() throws Exception {
        Long id = createTemplate();
        service.saveScopeLines(id, lines(line("A", null), line("B", null), line("C", null)), USER);

        List<BigDecimal> w = savedWeights(id);
        assertEquals(0, w.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("100")));
    }

    // ── One active template per project type ───────────────────────────────────

    @Test
    void aSecondActiveTemplateForTheSameTypeIsRejected() throws Exception {
        createTemplate();
        TemplateHeaderRequest h = new TemplateHeaderRequest();
        h.setProjectType(TYPE);
        h.setName("Duplicate");
        h.setIsActive(true);

        assertThrows(CustomException.class, () -> service.createTemplate(h, USER));
    }

    @Test
    void anInactiveDuplicateIsAllowedUntilItIsActivated() throws Exception {
        createTemplate();
        TemplateHeaderRequest h = new TemplateHeaderRequest();
        h.setProjectType(TYPE);
        h.setName("Spare");
        h.setIsActive(false);
        Long spareId = (Long) service.createTemplate(h, USER).get("id");

        TemplateHeaderRequest activate = new TemplateHeaderRequest();
        activate.setProjectType(TYPE);
        activate.setName("Spare");
        activate.setIsActive(true);
        assertThrows(CustomException.class, () -> service.updateTemplate(spareId, activate));
    }

    /** Saving a template's own header must not trip the guard against itself. */
    @Test
    void anActiveTemplateCanStillSaveItsOwnHeader() throws Exception {
        Long id = createTemplate();
        TemplateHeaderRequest h = new TemplateHeaderRequest();
        h.setProjectType(TYPE);
        h.setName("Renamed");
        h.setIsActive(true);

        assertEquals("Renamed", service.updateTemplate(id, h).get("name"));
    }
}
