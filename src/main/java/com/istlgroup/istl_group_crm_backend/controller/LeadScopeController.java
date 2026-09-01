package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.BomPurchaseHistoryService;
import com.istlgroup.istl_group_crm_backend.service.LeadScopeService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetCategoryRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BudgetItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.CapacityRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ExtrasSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.MarginRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeHeaderRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemsBulkRequest;
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

    @Autowired
    private BomPurchaseHistoryService bomPurchaseHistoryService;

    /** Ceiling on how many (item, make) pairs one hint request may ask about. */
    @Value("${bom.purchase-hint.max-pairs:200}")
    private int maxHintPairs = 200;

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
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
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

    /**
     * Whole-list replace of the lead's scope lines — what the Technical Scope tab
     * saves with. The per-item POST/DELETE below remain for older callers.
     */
    @PutMapping("/{leadId}/scope/items")
    public ResponseEntity<Map<String, Object>> saveScopeItems(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody ScopeItemsBulkRequest body) {
        try {
            return ok(leadScopeService.saveScopeItems(leadId, body, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save scope items: " + e.getMessage());
        }
    }

    @DeleteMapping("/{leadId}/scope/items/{itemId}")
    public ResponseEntity<Map<String, Object>> deleteScopeItem(
            @PathVariable Long leadId,
            @PathVariable Long itemId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            Map<String, Object> data = leadScopeService.deleteBudgetItem(leadId, itemId, userId, userRole);
            return ok(data);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete budget item: " + e.getMessage());
        }
    }

    // ── BOM ──────────────────────────────────────────────────────────────────

    @GetMapping("/{leadId}/bom")
    public ResponseEntity<Map<String, Object>> getBom(@PathVariable Long leadId) {
        try {
            return ok(leadScopeService.getBom(leadId));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{leadId}/bom")
    public ResponseEntity<Map<String, Object>> saveBom(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody BomSaveRequest body) {
        try {
            return ok(leadScopeService.saveBom(leadId, body, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save BOM: " + e.getMessage());
        }
    }

    /**
     * Last procured cost per (catalog item, catalog make), for the price hint beside
     * each BOM line's unit-rate field. Read-only and purely advisory — nothing here
     * writes a rate; the estimator clicks to apply.
     *
     * <p>{@code pairs} is {@code itemId:variantId} comma-separated. The lookup is
     * GLOBAL — history comes from every lead and project, not this one — so {@code leadId}
     * is unused by the query and present only to keep the BOM tab's endpoints on one
     * route, and to leave somewhere for per-lead authz to hang later.
     *
     * <p>The role is the caller's real one, from the session. It used to be an optional
     * header, and a missing one meant "unrestricted" — so a role barred from seeing rates
     * could see them simply by dropping the header. A role that may not see rates still
     * gets an empty map rather than a 403: the tab renders, just without hints.
     */
    @GetMapping("/{leadId}/bom/purchase-hints")
    public ResponseEntity<Map<String, Object>> bomPurchaseHints(
            @PathVariable Long leadId,
            @ActingUserRole String userRole,
            @RequestParam(value = "pairs", required = false) String pairs) {
        try {
            List<BomPurchaseHistoryService.ItemVariantPair> parsed =
                    BomPurchaseHistoryService.parsePairs(pairs);
            if (parsed.size() > maxHintPairs) {
                return error(HttpStatus.BAD_REQUEST,
                        "Too many items requested (" + parsed.size() + " > " + maxHintPairs + ")");
            }
            return ok(bomPurchaseHistoryService.hints(parsed, userRole));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load price hints: " + e.getMessage());
        }
    }

    /**
     * Set the plant capacity from the BOM tab, where its absence is what makes
     * auto-sizing fail. Its own endpoint so the estimator doesn't have to leave
     * for the lead form to fix it.
     */
    @PutMapping("/{leadId}/capacity")
    public ResponseEntity<Map<String, Object>> updateCapacity(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody CapacityRequest body) {
        try {
            return ok(leadScopeService.updateCapacity(leadId, body, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update capacity: " + e.getMessage());
        }
    }

    // ── Extra allocations ────────────────────────────────────────────────────

    @GetMapping("/{leadId}/budget/extras")
    public ResponseEntity<Map<String, Object>> getExtras(@PathVariable Long leadId) {
        try {
            return ok(leadScopeService.getExtras(leadId));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/{leadId}/budget/extras")
    public ResponseEntity<Map<String, Object>> saveExtras(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody ExtrasSaveRequest body) {
        try {
            return ok(leadScopeService.saveExtras(leadId, body, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save extra allocations: " + e.getMessage());
        }
    }

    // ── Suggestion (scope + BOM from capacity & project type) ────────────────

    @GetMapping("/{leadId}/scope/suggest")
    public ResponseEntity<Map<String, Object>> suggest(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestParam(value = "target", required = false, defaultValue = "both") String target) {
        try {
            return ok(leadScopeService.suggestScopeAndBom(leadId, target, userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build suggestion: " + e.getMessage());
        }
    }

    /** Whether a template exists for this lead's project type (for the Load button). */
    @GetMapping("/{leadId}/scope/template-info")
    public ResponseEntity<Map<String, Object>> templateInfo(@PathVariable Long leadId) {
        try {
            return ok(leadScopeService.templateInfo(leadId));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
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

    /** Set the profit markup % — the proposal price is derived from cost. */
    @PutMapping("/{leadId}/margin")
    public ResponseEntity<Map<String, Object>> updateMargin(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody MarginRequest body) {
        try {
            return ok(leadScopeService.updateMargin(leadId, body.getMarginPercent(), userId, userRole));
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update margin: " + e.getMessage());
        }
    }

    /** Set a negotiated target price — the markup % is back-computed from cost. */
    @PutMapping("/{leadId}/selling-price")
    public ResponseEntity<Map<String, Object>> updateSellingPrice(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
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
