package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.service.BorrowerService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SanctionCompareSummaryWrapper;

/**
 * Borrower Comparison REST API — a standalone, read-only module. It never
 * writes anything and introduces no new authorization model: both endpoints
 * below thread the same {@code User-Id}/{@code User-Role} headers into
 * {@link BorrowerService}'s existing role-hierarchy/team scope checks (the
 * same ones {@code /borrower/getAll} and {@code /borrower/{id}} already use),
 * so a sanction is comparable here iff it's already visible on the registry
 * or detail page. Same {@code {success, message, data}} envelope and
 * SessionFilter protection as {@link BorrowerController}.
 */
@RestController
@RequestMapping("/borrower/compare")
public class BorrowerComparisonController {

    private static final Logger log = LoggerFactory.getLogger(BorrowerComparisonController.class);

    @Autowired
    private BorrowerService borrowerService;

    /** The picker's selection list — every sanction this caller may see, optionally narrowed by search/filters. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "lenderName", required = false) String lenderName,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "projectName", required = false) String projectName,
            @RequestParam(value = "groupId", required = false) Long groupId) {
        try {
            List<SanctionCompareSummaryWrapper> data = borrowerService.getSanctionsCompareSummary(
                    userId, userRole, search, lenderName, status, projectName, groupId);
            return ok(data, null);
        } catch (Exception e) {
            log.error("Failed to load comparison summary", e);
            return error("Failed to load sanctions to compare: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Full detail (limits/tranches/derived values included) for the caller's selected sanction ids, in one batch. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> compare(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody CompareRequest body) {
        try {
            List<BorrowerSanctionWrapper> data =
                    borrowerService.getSanctionsForComparison(userId, userRole, body.getSanctionIds());
            return ok(data, null);
        } catch (Exception e) {
            log.error("Failed to load comparison detail", e);
            return error("Failed to load comparison: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public static class CompareRequest {
        private List<Long> sanctionIds;
        public List<Long> getSanctionIds() { return sanctionIds; }
        public void setSanctionIds(List<Long> sanctionIds) { this.sanctionIds = sanctionIds; }
    }

    // ── envelope helpers (same shape as BorrowerController) ──────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        if (message != null) res.put("message", message);
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    private ResponseEntity<Map<String, Object>> error(String message, HttpStatus status) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", message);
        return ResponseEntity.status(status).body(res);
    }
}
