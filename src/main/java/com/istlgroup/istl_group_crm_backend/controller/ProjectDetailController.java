package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
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
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard;
import com.istlgroup.istl_group_crm_backend.service.ProjectDetailService;
import com.istlgroup.istl_group_crm_backend.service.ProjectScopeSuggestionService;
import com.istlgroup.istl_group_crm_backend.service.ProjectStatsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BudgetRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.ScopeRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.FinanceSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDetailWrapper.SiteLocationRequest;
import com.istlgroup.istl_group_crm_backend.util.BomRateVisibility;

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

    @Autowired
    private ProjectScopeSuggestionService suggestionService;

    @Autowired
    private ProjectStatsService projectStatsService;

    @Autowired
    private BomProcurementGuard bomGuard;

    @Autowired
    private com.istlgroup.istl_group_crm_backend.service.ProjectBomActualsService actualsService;

    // ── TECHNICAL SCOPE ──────────────────────────────────────────────────────

    @GetMapping("/{projectUniqueId}/scope")
    public ResponseEntity<Map<String, Object>> getScope(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            detailService.saveScope(projectUniqueId, request, userId);
            // saveScope has committed — recompute physical + the blended overall %
            // (and status) now, in its own transaction, so the headline updates
            // immediately. Non-fatal: a stats hiccup must not fail the scope save.
            try { projectStatsService.recalculateProjectStats(projectUniqueId); }
            catch (Exception statsEx) { /* logged inside; scope save already persisted */ }
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
     * Clears the scope lines and the scope-origin marker, so a bad lead import can be
     * undone and Suggest becomes available again. BOM lines survive, unlinked.
     */
    @DeleteMapping("/{projectUniqueId}/scope/items")
    public ResponseEntity<Map<String, Object>> resetScopeItems(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            detailService.resetScopeItems(projectUniqueId);
            try { projectStatsService.recalculateProjectStats(projectUniqueId); }
            catch (Exception statsEx) { /* logged inside; the reset already persisted */ }
            Map<String, Object> data = new HashMap<>();
            data.put("scope",  detailService.getScope(projectUniqueId));
            data.put("phases", phasesToMaps(detailService.getPhases(projectUniqueId)));
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Scope cleared");
            resp.put("data", data);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to clear scope: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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

    /**
     * Template-driven scope + BOM suggestion for the project's sub-group — the
     * project analog of {@code GET /leads/{leadId}/scope/suggest}. Same response
     * shape ({@code scopeItems}, {@code bomLines}, {@code warnings[]}). Never 500s
     * when no template exists — returns {@code W_NO_TEMPLATE_NO_HISTORY} + empty.
     * Supersedes {@code /scope/default-plan} (kept routed for backward compat).
     */
    @GetMapping("/{projectUniqueId}/scope/suggest")
    public ResponseEntity<Map<String, Object>> suggestScope(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestParam(value = "target", required = false, defaultValue = "both") String target,
            @RequestParam(value = "source", required = false, defaultValue = "TEMPLATE") String source) {
        try {
            return ok(suggestionService.suggestScopeAndBom(projectUniqueId, target, source));
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to build suggestion: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Whether a template exists for this project's sub-group (Suggest button label). */
    @GetMapping("/{projectUniqueId}/scope/template-info")
    public ResponseEntity<Map<String, Object>> templateInfo(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            return ok(suggestionService.templateInfo(projectUniqueId));
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── COMMERCIAL ───────────────────────────────────────────────────────────

    @GetMapping("/{projectUniqueId}/budget")
    public ResponseEntity<Map<String, Object>> getBudget(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            return ok(detailService.getBom(projectUniqueId, canSeeRates(userRole)));
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /**
     * Rate gating (§6.5): field-staff access levels see quantities/specs but NOT
     * pricing (unit_rate / amount / total).
     *
     * <p>Delegates to {@link BomRateVisibility} — the procurement BOM picker applies
     * the same rule, and they must never drift.
     */
    private boolean canSeeRates(String userRole) {
        return BomRateVisibility.canSeeRates(userRole);
    }

    /**
     * Live BOM lines for the procurement item picker, each with how much has already
     * been ordered on live purchase orders and how much remains.
     *
     * <p>Lives on this controller, not on PurchaseOrderController, for two reasons:
     * the BOM is a project concern, and this controller reads the {@code User-Role}
     * header whereas the PO controller reads {@code X-User-Role} with a "USER"
     * fallback that would hand rate visibility to an unauthenticated caller.
     *
     * @param excludePoId when editing an existing PO, that PO's own quantities must
     *                    not count against itself — pass its id.
     */
    @GetMapping("/{projectUniqueId}/bom/procurement-availability")
    public ResponseEntity<Map<String, Object>> getBomProcurementAvailability(
            @PathVariable String projectUniqueId,
            @RequestParam(required = false) Long excludePoId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            boolean rates = canSeeRates(userRole);
            Map<String, Object> data = new HashMap<>();
            data.put("lines", bomGuard.availability(projectUniqueId, excludePoId, rates));
            data.put("canSeeRates", rates);
            return ok(data);
        } catch (Exception e) {
            return err("Failed to load project BOM: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Planned vs Procured for this project's BOM — what the Comparison and Procured
     * sub-tabs render.
     *
     * <p>"Procured" is purchase-order value only, never project expenses: this screen
     * measures procurement, not spend, and its total will not equal the project
     * financials total.
     */
    @GetMapping("/{projectUniqueId}/bom/planned-vs-actual")
    public ResponseEntity<Map<String, Object>> getBomPlannedVsActual(
            @PathVariable String projectUniqueId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            return ok(actualsService.plannedVsActual(projectUniqueId, canSeeRates(userRole)));
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) {
            return err("Failed to load planned vs actual: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Dry-run the BOM check for a set of purchase-order lines, WITHOUT writing anything.
     *
     * <p>Exists so the PO modal can put two things in front of the buyer before the
     * save: the violations that would block it, and — the point of §A2 — the lines the
     * guard could only tie to the BOM by inference (item name + make + unit) rather
     * than by a picked catalogue reference. An inferred match that is wrong consumes
     * the wrong BOM line's budget, which is worse than no match at all, so it is
     * confirmable and correctable while the modal is still open.
     *
     * <p>On this controller rather than PurchaseOrderController for the same two
     * reasons as the availability endpoint: the BOM is a project concern, and the PO
     * controller reads {@code X-User-Role} with a "USER" fallback that would hand rate
     * visibility to an unauthenticated caller.
     */
    @PostMapping("/{projectUniqueId}/bom/po-precheck")
    public ResponseEntity<Map<String, Object>> precheckPoAgainstBom(
            @PathVariable String projectUniqueId,
            @RequestBody Map<String, Object> body,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = body.get("items") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            Long excludePoId = body.get("excludePoId") instanceof Number n ? n.longValue() : null;

            BomProcurementGuard.CheckResult r = bomGuard.check(
                    projectUniqueId, BomProcurementGuard.fromPoItemMaps(items),
                    excludePoId, BomProcurementGuard.Mode.WARN);

            boolean rates = canSeeRates(userRole);
            Map<String, Object> data = new HashMap<>();
            data.put("violations", r.violations());
            data.put("fallbackMatches", r.fallbackMatches());
            // The picker's own list, so the correction dropdown can offer every BOM line
            // with its remaining quantity without a second round trip.
            data.put("bomLines", bomGuard.availability(projectUniqueId, excludePoId, rates));
            data.put("scopes", scopeOptions(projectUniqueId));
            data.put("canSeeRates", rates);
            return ok(data);
        } catch (Exception e) {
            return err("Failed to check the project BOM: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Scope line id → name, so a BOM line can be shown under the phase it belongs to. */
    private List<Map<String, Object>> scopeOptions(String projectUniqueId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (ProjectPhaseEntity p : detailService.getPhases(projectUniqueId)) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.getId());
                m.put("name", p.getPhaseName());
                out.add(m);
            }
        } catch (Exception ignored) {
            // Scope names are a nicety on this response; never fail the check for them.
        }
        return out;
    }

    @PutMapping("/{projectUniqueId}/bom")
    public ResponseEntity<Map<String, Object>> saveBom(
            @PathVariable String projectUniqueId,
            @RequestBody BomSaveRequest request,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            m.put("plannedProgressPct", p.getPlannedProgressPct());
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
