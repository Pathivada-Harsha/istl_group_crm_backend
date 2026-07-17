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
        TemplateScopeLineRequest a = new TemplateScopeLineRequest(); a.setActivity("A");
        TemplateScopeLineRequest b = new TemplateScopeLineRequest(); b.setActivity("B");
        TemplateScopeLinesRequest two = new TemplateScopeLinesRequest(); two.setItems(List.of(a, b));
        service.saveScopeLines(id, two, USER);
        assertEquals(2, ((List<?>) service.getTemplate(id).get("scopeItems")).size());

        TemplateScopeLinesRequest one = new TemplateScopeLinesRequest(); one.setItems(List.of(a));
        service.saveScopeLines(id, one, USER);
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
}
