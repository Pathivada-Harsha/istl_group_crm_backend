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
import com.istlgroup.istl_group_crm_backend.entity.OrderBookPhaseEntity;
import com.istlgroup.istl_group_crm_backend.service.OrderBookDetailService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BudgetRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.ScopeRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.FinanceSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookDetailWrapper.SiteLocationRequest;

/**
 * Detail-view endpoints for a single order book:
 *   • Technical Scope  (scope header + weekly EPC phase plan)
 *   • Commercial       (budget allocation + live procurement/spend actuals)
 *
 * Mounted under the same /order-book base path as OrderBookController, so the
 * existing frontend auth headers (User-Id / User-Role) apply unchanged.
 */
@RestController
@RequestMapping("/order-book")
public class OrderBookDetailController {

    @Autowired
    private OrderBookDetailService detailService;

    // ── TECHNICAL SCOPE ──────────────────────────────────────────────────────

    @GetMapping("/{id}/scope")
    public ResponseEntity<Map<String, Object>> getScope(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("scope",  detailService.getScope(id));
            data.put("phases", phasesToMaps(detailService.getPhases(id)));
            data.put("progressPeriods", detailService.getProgressPeriods(id));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/{id}/scope")
    public ResponseEntity<Map<String, Object>> saveScope(
            @PathVariable Long id,
            @RequestBody ScopeRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveScope(id, request, userId);
            Map<String, Object> data = new HashMap<>();
            data.put("scope",  detailService.getScope(id));
            data.put("phases", phasesToMaps(detailService.getPhases(id)));
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
    @PutMapping("/{id}/scope/budgets")
    public ResponseEntity<Map<String, Object>> saveScopeBudgets(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
            detailService.saveScopeBudgets(id, items);
            Map<String, Object> data = new HashMap<>();
            data.put("phases", phasesToMaps(detailService.getPhases(id)));
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
    @PutMapping("/{id}/scope/progress")
    public ResponseEntity<Map<String, Object>> saveProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cells = (List<Map<String, Object>>) request.get("cells");
            detailService.saveProgressPeriods(id, cells);
            Map<String, Object> data = new HashMap<>();
            data.put("progressPeriods", detailService.getProgressPeriods(id));
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
    @GetMapping("/{id}/scope/default-plan")
    public ResponseEntity<Map<String, Object>> defaultPlan(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("phases", detailService.defaultEpcPlan(id));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── COMMERCIAL ───────────────────────────────────────────────────────────

    @GetMapping("/{id}/budget")
    public ResponseEntity<Map<String, Object>> getBudget(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBudgetLines(id));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/{id}/budget")
    public ResponseEntity<Map<String, Object>> saveBudget(
            @PathVariable Long id,
            @RequestBody BudgetRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBudget(id, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Budget allocation saved successfully");
            resp.put("data", detailService.getCommercialSummary(id));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return err("Failed to save budget: " + e.getMessage(),
                       HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Allocated vs actual (procurement + spend) summary, pulled live by projectId. */
    @GetMapping("/{id}/commercial-summary")
    public ResponseEntity<Map<String, Object>> commercialSummary(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("summary", detailService.getCommercialSummary(id));
            return ok(data);
        } catch (CustomException e) {
            return err(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── COMMERCIAL v2: item-keyed billing + cost ────────────────────────────

    @GetMapping("/{id}/billing")
    public ResponseEntity<Map<String, Object>> getBilling(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBilling(id));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{id}/billing")
    public ResponseEntity<Map<String, Object>> saveBilling(
            @PathVariable Long id,
            @RequestBody FinanceSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBilling(id, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Billing plan saved successfully");
            resp.put("data", detailService.getCommercialSummaryV2(id));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save billing: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @GetMapping("/{id}/cost")
    public ResponseEntity<Map<String, Object>> getCost(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getCost(id));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{id}/cost")
    public ResponseEntity<Map<String, Object>> saveCost(
            @PathVariable Long id,
            @RequestBody FinanceSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveCost(id, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Cost plan saved successfully");
            resp.put("data", detailService.getCommercialSummaryV2(id));
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save cost: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** v2: billing (invoices) + cost (PO/spend) planned-vs-actual, live by projectId. */
    @GetMapping("/{id}/commercial-summary-v2")
    public ResponseEntity<Map<String, Object>> commercialSummaryV2(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("summary", detailService.getCommercialSummaryV2(id));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── SITE LOCATION (Overview tab) ─────────────────────────────────────────
    @PatchMapping("/{id}/site-location")
    public ResponseEntity<Map<String, Object>> saveSiteLocation(
            @PathVariable Long id,
            @RequestBody SiteLocationRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveSiteLocation(id, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "Site location saved successfully");
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save site location: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── BOM / BOQ ──────────────────────────────────────────────────────────
    @GetMapping("/{id}/bom")
    public ResponseEntity<Map<String, Object>> getBom(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("lines", detailService.getBom(id));
            return ok(data);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.NOT_FOUND); }
        catch (Exception e) { return err(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    @PutMapping("/{id}/bom")
    public ResponseEntity<Map<String, Object>> saveBom(
            @PathVariable Long id,
            @RequestBody BomSaveRequest request,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            detailService.saveBom(id, request, userId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "BOM / BOQ saved successfully");
            return ResponseEntity.ok(resp);
        } catch (CustomException e) { return err(e.getMessage(), HttpStatus.BAD_REQUEST); }
        catch (Exception e) { return err("Failed to save BOM: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    /**
     * Converts phase entities to plain Maps so that the `subItems` JSON string
     * is returned as a proper array in the API response, not as an escaped string.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> phasesToMaps(List<OrderBookPhaseEntity> phases) {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> result = new ArrayList<>();
        for (OrderBookPhaseEntity p : phases) {
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