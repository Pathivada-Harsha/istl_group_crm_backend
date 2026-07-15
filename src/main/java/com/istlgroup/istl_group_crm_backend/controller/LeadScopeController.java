package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.LeadScopeService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetCategoryRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.SellingPriceRequest;

/**
 * Lead Technical Scope + Budget Estimation endpoints. Nested under /leads but
 * deeper than the CRUD paths owned by {@link LeadsController} — no collisions.
 */
@RestController
@RequestMapping("/leads")
public class LeadScopeController {

    @Autowired
    private LeadScopeService leadScopeService;

    // ── Scope ────────────────────────────────────────────────────────────────

    @GetMapping("/{leadId}/scope")
    public ResponseEntity<Map<String, Object>> getScope(@PathVariable Long leadId) {
        try {
            return ok(leadScopeService.getScope(leadId));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/{leadId}/scope")
    public ResponseEntity<Map<String, Object>> saveScopeHeader(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody ScopeHeaderRequest body) {
        try {
            return ok(leadScopeService.saveScopeHeader(leadId, body, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save scope: " + e.getMessage());
        }
    }

    @PostMapping("/{leadId}/scope/items")
    public ResponseEntity<Map<String, Object>> saveScopeItem(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody ScopeItemRequest body) {
        try {
            Map<String, Object> item = leadScopeService.saveScopeItem(leadId, body, userId, userRole);
            return ResponseEntity.status(HttpStatus.CREATED).body(wrap(item));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save scope item: " + e.getMessage());
        }
    }

    @DeleteMapping("/{leadId}/scope/items/{itemId}")
    public ResponseEntity<Map<String, Object>> deleteScopeItem(
            @PathVariable Long leadId,
            @PathVariable Long itemId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            leadScopeService.deleteScopeItem(leadId, itemId, userId, userRole);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete scope item: " + e.getMessage());
        }
    }

    // ── Budget ───────────────────────────────────────────────────────────────

    @GetMapping("/{leadId}/budget")
    public ResponseEntity<Map<String, Object>> getBudget(@PathVariable Long leadId) {
        try {
            List<Map<String, Object>> data = leadScopeService.getBudget(leadId);
            return ok(data);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/{leadId}/budget/category")
    public ResponseEntity<Map<String, Object>> saveBudgetCategory(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody BudgetCategoryRequest body) {
        try {
            Map<String, Object> cat = leadScopeService.saveBudgetCategory(leadId, body, userId, userRole);
            return ResponseEntity.status(HttpStatus.CREATED).body(wrap(cat));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save budget category: " + e.getMessage());
        }
    }

    @DeleteMapping("/{leadId}/budget/category/{catId}")
    public ResponseEntity<Map<String, Object>> deleteBudgetCategory(
            @PathVariable Long leadId,
            @PathVariable Long catId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            leadScopeService.deleteBudgetCategory(leadId, catId, userId, userRole);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete budget category: " + e.getMessage());
        }
    }

    @PostMapping("/{leadId}/budget/item")
    public ResponseEntity<Map<String, Object>> saveBudgetItem(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody BudgetItemRequest body) {
        try {
            Map<String, Object> data = leadScopeService.saveBudgetItem(leadId, body, userId, userRole);
            return ResponseEntity.status(HttpStatus.CREATED).body(wrap(data));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save budget item: " + e.getMessage());
        }
    }

    @DeleteMapping("/{leadId}/budget/item/{itemId}")
    public ResponseEntity<Map<String, Object>> deleteBudgetItem(
            @PathVariable Long leadId,
            @PathVariable Long itemId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            Map<String, Object> data = leadScopeService.deleteBudgetItem(leadId, itemId, userId, userRole);
            return ok(data);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete budget item: " + e.getMessage());
        }
    }

    // ── Estimation summary / selling price ───────────────────────────────────

    @GetMapping("/{leadId}/estimation-summary")
    public ResponseEntity<Map<String, Object>> getEstimationSummary(@PathVariable Long leadId) {
        try {
            return ok(leadScopeService.getEstimationSummary(leadId));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{leadId}/selling-price")
    public ResponseEntity<Map<String, Object>> updateSellingPrice(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody SellingPriceRequest body) {
        try {
            Map<String, Object> data =
                    leadScopeService.updateSellingPrice(leadId, body.getSellingPrice(), userId, userRole);
            return ok(data);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update selling price: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(wrap(data));
    }

    private Map<String, Object> wrap(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("data", data);
        return resp;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", message);
        return ResponseEntity.status(status).body(errorResponse);
    }
}
