package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectScopeEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectScopeRepo;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;

/**
 * Project-side scope + BOM suggestion. The project analog of
 * {@link LeadScopeService#suggestScopeAndBom} — it resolves the project's
 * standardised template (keyed by {@code sub_group_name}) and expands it via the
 * shared {@link ScopeTemplateExpander}, so both flows reuse the one
 * {@link LeadSuggestionEngine} for the eight basis rules. Returns the SAME response
 * shape as the lead endpoint so the frontend can share rendering code.
 *
 * <p>Capacity comes from the scope header's free-text {@code system_capacity} (the
 * project analog of {@code lead.capacity}) — this deliberately does NOT depend on
 * the deferred {@code projects.capacity_value} auto-derivation. When it is absent
 * (today's norm) the engine emits {@code W_NEEDS_CAPACITY}, BOM lines come back
 * with {@code quantity=null} and the basis preserved, and the scope suggestion
 * still works fully.
 */
@Service
public class ProjectScopeSuggestionService {

    @Autowired private DropdownProjectRepository projectRepo;
    @Autowired private ProjectScopeRepo scopeRepo;
    @Autowired private ScopeTemplateExpander expander;

    /**
     * @param target      "scope" | "bom" | "both" (default both)
     * @param scopeSource "TEMPLATE" now; reserved seam for a future "LEAD_COPY"
     *                    (§2.6) — no import path is built yet.
     */
    public Map<String, Object> suggestScopeAndBom(String projectUniqueId, String target, String scopeSource)
            throws CustomException {
        DropdownProjectEntity project = projectRepo.findByProjectUniqueId(projectUniqueId)
                .orElseThrow(() -> new CustomException("Project not found: " + projectUniqueId));
        String projectType = project.getSubGroupName();

        ProjectScopeEntity scope = scopeRepo.findByProjectId(project.getId()).orElse(null);
        String capacityStr = scope != null ? scope.getSystemCapacity() : null;
        CapacityInfo cap = CapacityUtil.parse(capacityStr, null, projectType);

        boolean wantScope = !"bom".equalsIgnoreCase(target);
        boolean wantBom = !"scope".equalsIgnoreCase(target);

        List<Map<String, Object>> warnings = new ArrayList<>();
        // Projects have no linked site visit yet; a FROM_SITE_VISIT basis will emit
        // W_NEEDS_SITE_VISIT, which is correct (nothing to pull from).
        Map<String, String> siteVisit = Map.of();

        // Only TEMPLATE is wired today; scopeSource is the reserved LEAD_COPY seam.
        ScopeTemplateExpander.TemplateExpansion ex =
                expander.expand(projectType, cap, siteVisit, wantScope, wantBom, warnings);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ex.source);
        data.put("hasTemplate", ex.hasTemplate);
        data.put("projectType", projectType);
        data.put("targetCapacity", displayCapacity(cap));
        data.put("capacityKw", cap.isUsable() ? cap.scaleBase() : null);
        data.put("scopeItems", ex.scopeItems);
        data.put("bomLines", ex.bomLines);
        data.put("warnings", warnings);
        data.put("scopeSource", scopeSource == null ? "TEMPLATE" : scopeSource);
        // Provenance echoed back so the scope-save path can pin source_template_id / version.
        data.put("templateId", ex.templateId);
        data.put("templateVersion", ex.templateVersion);
        return data;
    }

    /** Whether a template exists for this project's sub-group (Suggest button label). */
    public Map<String, Object> templateInfo(String projectUniqueId) throws CustomException {
        DropdownProjectEntity project = projectRepo.findByProjectUniqueId(projectUniqueId)
                .orElseThrow(() -> new CustomException("Project not found: " + projectUniqueId));
        return expander.templateInfo(project.getSubGroupName());
    }

    private String displayCapacity(CapacityInfo cap) {
        if (cap == null || cap.value() == null) return null;
        return cap.value().stripTrailingZeros().toPlainString() + (cap.unit() != null ? " " + cap.unit() : "");
    }
}
