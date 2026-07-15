package com.istlgroup.istl_group_crm_backend.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.service.ProjectDetailService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BudgetRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.ScopeRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.FinanceSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.SiteLocationRequest;

/**
 * Detail-view endpoints for a single project:
 *   • Technical Scope  (scope header + weekly EPC phase plan)
 *   • Commercial       (budget allocation + live procurement/spend actuals)
 *
 * Mounted under the same /projects base path as DropdownProjectController. Only the
 * deeper sub-paths ({projectUniqueId}/scope, /budget, /billing, …) live here — the
 * bare GET /projects, GET /projects/{projectUniqueId} and CRUD verbs already exist
 * in DropdownProjectController and are NOT redeclared to avoid mapping collisions.
 */
@RestController
@RequestMapping("/projects")
public class ProjectDetailController {

    @Autowired
    private ProjectDetailService detailService;

    // ── TECHNICAL SCOPE ──────────────────────────────────────────────────────

    @GetMapping("/{projectUniqueId}/scope")
    public ResponseEntity<Map<String, Object>> getScope(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("scope",  detailService.getScope(projectUniqueId));
            data.put("phases", phasesToMaps(detailService.getPhases(projectUniqueId)));
            data.put("progressPeriods", detailService.getProgressPeriods(projectUniqueId));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/{projectUniqueId}/scope")
    public ResponseEntity<Map<String, Object>> saveScope(
            @PathVariable String projectUniqueId,
            @RequestBody ScopeRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveScope(projectUniqueId, request, userId);
            Map<String, Object> data = new HashMap<>();
            data.put("scope",  detailService.getScope(projectUniqueId));
            data.put("phases", phasesToMaps(detailService.getPhases(projectUniqueId)));
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Technical scope saved successfully");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to save technical scope: " + e.getMessage(),
                       HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Budget-only update for scope items. Updates ONLY plannedBudget on existing phases
     * and sub-items — never touches scope metadata or other phase fields. Used by the
     * Commercial tab's Budget Allocation block so it can't clobber the Technical Scope.
     */
    @PutMapping("/{projectUniqueId}/scope/budgets")
    public ResponseEntity<Map<String, Object>> saveScopeBudgets(
            @PathVariable String projectUniqueId,
            @RequestBody Map<String, Object> request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
            detailService.saveScopeBudgets(projectUniqueId, items);
            Map<String, Object> data = new HashMap<>();
            data.put("phases", phasesToMaps(detailService.getPhases(projectUniqueId)));
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Budget allocation saved");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to save budget allocation: " + e.getMessage(),
                       HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Replace-all save of per-period planned/actual progress (DETAILED tracking). */
    @PutMapping("/{projectUniqueId}/scope/progress")
    public ResponseEntity<Map<String, Object>> saveProgress(
            @PathVariable String projectUniqueId,
            @RequestBody Map<String, Object> request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cells = (List<Map<String, Object>>) request.get("cells");
            detailService.saveProgressPeriods(projectUniqueId, cells);
            Map<String, Object> data = new HashMap<>();
            data.put("progressPeriods", detailService.getProgressPeriods(projectUniqueId));
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Progress saved");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to save progress: " + e.getMessage(),
                       HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Returns an unsaved default EPC plan the UI can edit then PUT back via /scope. */
    @GetMapping("/{projectUniqueId}/scope/default-plan")
    public ResponseEntity<Map<String, Object>> defaultPlan(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("phases", detailService.defaultEpcPlan(projectUniqueId));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── COMMERCIAL ───────────────────────────────────────────────────────────

    @GetMapping("/{projectUniqueId}/budget")
    public ResponseEntity<Map<String, Object>> getBudget(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBudgetLines(projectUniqueId));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/{projectUniqueId}/budget")
    public ResponseEntity<Map<String, Object>> saveBudget(
            @PathVariable String projectUniqueId,
            @RequestBody BudgetRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBudget(projectUniqueId, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Budget allocation saved successfully");
            resp.put("data", detailService.getCommercialSummary(projectUniqueId));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to save budget: " + e.getMessage(),
                       HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Allocated vs actual (procurement + spend) summary, pulled live by projectId. */
    @GetMapping("/{projectUniqueId}/commercial-summary")
    public ResponseEntity<Map<String, Object>> commercialSummary(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("summary", detailService.getCommercialSummary(projectUniqueId));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── COMMERCIAL v2: item-keyed billing + cost ────────────────────────────

    @GetMapping("/{projectUniqueId}/billing")
    public ResponseEntity<Map<String, Object>> getBilling(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBilling(projectUniqueId));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{projectUniqueId}/billing")
    public ResponseEntity<Map<String, Object>> saveBilling(
            @PathVariable String projectUniqueId,
            @RequestBody FinanceSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBilling(projectUniqueId, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Billing plan saved successfully");
            resp.put("data", detailService.getCommercialSummaryV2(projectUniqueId));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save billing: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @GetMapping("/{projectUniqueId}/cost")
    public ResponseEntity<Map<String, Object>> getCost(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getCost(projectUniqueId));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{projectUniqueId}/cost")
    public ResponseEntity<Map<String, Object>> saveCost(
            @PathVariable String projectUniqueId,
            @RequestBody FinanceSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveCost(projectUniqueId, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Cost plan saved successfully");
            resp.put("data", detailService.getCommercialSummaryV2(projectUniqueId));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save cost: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** v2: billing (invoices) + cost (PO/spend) planned-vs-actual, live by projectId. */
    @GetMapping("/{projectUniqueId}/commercial-summary-v2")
    public ResponseEntity<Map<String, Object>> commercialSummaryV2(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("summary", detailService.getCommercialSummaryV2(projectUniqueId));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── SITE LOCATION (Overview tab) ─────────────────────────────────────────
    @PatchMapping("/{projectUniqueId}/site-location")
    public ResponseEntity<Map<String, Object>> saveSiteLocation(
            @PathVariable String projectUniqueId,
            @RequestBody SiteLocationRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveSiteLocation(projectUniqueId, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Site location saved successfully");
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save site location: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── BOM / BOQ ──────────────────────────────────────────────────────────
    @GetMapping("/{projectUniqueId}/bom")
    public ResponseEntity<Map<String, Object>> getBom(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBom(projectUniqueId));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{projectUniqueId}/bom")
    public ResponseEntity<Map<String, Object>> saveBom(
            @PathVariable String projectUniqueId,
            @RequestBody BomSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBom(projectUniqueId, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "BOM / BOQ saved successfully");
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save BOM: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── ITEMS (order-line items) ─────────────────────────────────────────────
    @GetMapping("/{projectUniqueId}/items")
    public ResponseEntity<Map<String, Object>> getItems(
            @PathVariable String projectUniqueId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("items", detailService.getItems(projectUniqueId));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    /**
     * Converts phase entities to plain Maps so that the `subItems` JSON string
     * is returned as a proper array in the API response, not as an escaped string.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> phasesToMaps(List<ProjectPhaseEntity> phases) {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProjectPhaseEntity p : phases) {
            Map<String, Object> m = new HashMap<>();
            m.put("id",                p.getId());
            m.put("seqNo",             p.getSeqNo());
            m.put("phaseName",         p.getPhaseName());
            m.put("phaseDescription",  p.getPhaseDescription());
            m.put("startWeek",         p.getStartWeek());
            m.put("endWeek",           p.getEndWeek());
            m.put("status",            p.getStatus());
            m.put("progressPercent",   p.getProgressPercent());
            m.put("weightPct",         p.getWeightPct());
            m.put("plannedBudget",     p.getPlannedBudget());
            m.put("responsibleUserId", p.getResponsibleUserId());
            m.put("plannedStartDate",  p.getPlannedStartDate());
            m.put("plannedEndDate",    p.getPlannedEndDate());
            m.put("actualStartDate",   p.getActualStartDate());
            m.put("actualEndDate",     p.getActualEndDate());
            // Parse subItems JSON string → List so the API returns a real array
            List<Object> subItems = null;
            if (p.getSubItems() != null && !p.getSubItems().isBlank()) {
                try { subItems = mapper.readValue(p.getSubItems(), List.class); } catch (Exception ignored) {}
            }
            m.put("subItems", subItems != null ? subItems : List.of());
            result.add(m);
        }
        return result;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> err(String message, HttpStatus status) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("message", message);
        return ResponseEntity.status(status).body(resp);
    }
}
