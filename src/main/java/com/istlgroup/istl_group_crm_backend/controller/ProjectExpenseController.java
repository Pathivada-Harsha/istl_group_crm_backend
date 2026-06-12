package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import com.istlgroup.istl_group_crm_backend.entity.ProjectExpenseHistory;
import com.istlgroup.istl_group_crm_backend.service.ProjectExpenseService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/project-expenses")
@RequiredArgsConstructor
@CrossOrigin
public class ProjectExpenseController {

    private final ProjectExpenseService expenseService;
    private final ProjectAccessService projectAccessService;

    private Long getUserId(HttpServletRequest req) {
        String val = req.getHeader("X-User-Id");
        try { return val != null ? Long.parseLong(val) : 0L; } catch (Exception e) { return 0L; }
    }

    private String getUserName(HttpServletRequest req) {
        String val = req.getHeader("X-User-Name");
        return val != null ? val : "System";
    }

    // ─── EXPENSE ENDPOINTS ────────────────────────────────────────────────────

    /** POST /api/project-expenses */
    @PostMapping
    public ResponseEntity<List<ProjectExpenseResponse>> createExpenses(
            @RequestBody ProjectExpenseRequest request, HttpServletRequest req) {
        log.info("Creating expenses for project: {}", request.getProjectId());
        return ResponseEntity.ok(expenseService.createExpenses(request, getUserId(req), getUserName(req)));
    }

    @GetMapping
    public ResponseEntity<Page<ProjectExpenseResponse>> getExpenses(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentMode,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "tripDate") String sortBy,
            @RequestParam(required = false, defaultValue = "desc")     String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest req) {

        Long   userId   = getUserId(req);
        String userRole = req.getHeader("X-User-Role");
        if (userRole == null) userRole = req.getHeader("User-Role");

        if (projectId != null && !projectId.isBlank()
                && userId != null && userId > 0
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
            return ResponseEntity.status(403).build();
        }

        ExpenseFilterRequest filter = new ExpenseFilterRequest();
        filter.setGroupName(nullIfBlank(groupName));
        filter.setSubGroupName(nullIfBlank(subGroupName));
        filter.setProjectId(nullIfAll(projectId));
        filter.setCategory(nullIfAll(category));
        filter.setStatus(nullIfAll(status));
        filter.setPaymentMode(nullIfAll(paymentMode));
        filter.setSearch(nullIfBlank(search));
        filter.setSortBy(sortBy);
        filter.setSortDir(sortDir);
        filter.setPage(page);
        filter.setSize(size);
        if (dateFrom != null && !dateFrom.isBlank()) filter.setDateFrom(LocalDate.parse(dateFrom));
        if (dateTo   != null && !dateTo.isBlank())   filter.setDateTo(LocalDate.parse(dateTo));

        // Apply project-access-based visibility — same pattern as InvoiceService
        if (userId != null && userId > 0) {
            expenseService.applyVisibilityToFilter(filter, userId, userRole);
        }

        return ResponseEntity.ok(expenseService.getExpenses(filter));
    }

    /** GET /api/project-expenses/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectExpenseResponse> getExpense(@PathVariable Long id) {
        return ResponseEntity.ok(expenseService.getExpenseById(id));
    }

    /** PUT /api/project-expenses/{id} */
    @PutMapping("/{id}")
    public ResponseEntity<ProjectExpenseResponse> updateExpense(
            @PathVariable Long id,
            @RequestBody ProjectExpenseRequest request,
            HttpServletRequest req) {
        return ResponseEntity.ok(expenseService.updateExpense(id, request, getUserId(req), getUserName(req)));
    }

    /** PATCH /api/project-expenses/{id}/status  body: { "status": "Approved" }  (admin override) */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ProjectExpenseResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        return ResponseEntity.ok(
            expenseService.updateExpenseStatus(id, body.get("status"), getUserId(req), getUserName(req)));
    }

    // ─── APPROVAL WORKFLOW ────────────────────────────────────────────────────

    /**
     * PATCH /api/project-expenses/{id}/approve  body: { "remarks": "ok" } (optional)
     * Approves the CURRENT stage and advances the pipeline.
     */
    @PatchMapping("/{id}/approve")
    public ResponseEntity<ProjectExpenseResponse> approveStage(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest req) {
        String remarks = body != null ? body.get("remarks") : null;
        return ResponseEntity.ok(
            expenseService.actOnApproval(id, "approve", remarks, getUserId(req), getUserName(req)));
    }

    /**
     * PATCH /api/project-expenses/{id}/reject  body: { "remarks": "reason" }
     * Rejects the expense at the current stage.
     */
    @PatchMapping("/{id}/reject")
    public ResponseEntity<ProjectExpenseResponse> rejectStage(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest req) {
        String remarks = body != null ? body.get("remarks") : null;
        return ResponseEntity.ok(
            expenseService.actOnApproval(id, "reject", remarks, getUserId(req), getUserName(req)));
    }

    /**
     * PATCH /api/project-expenses/{id}/pay
     * Accounts Executive marks a fully-approved expense as Paid.
     */
    @PatchMapping("/{id}/pay")
    public ResponseEntity<ProjectExpenseResponse> markPaid(
            @PathVariable Long id, HttpServletRequest req) {
        return ResponseEntity.ok(
            expenseService.markPaid(id, getUserId(req), getUserName(req)));
    }

    /** DELETE /api/project-expenses/{id}  (soft delete) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long id, HttpServletRequest req) {
        expenseService.deleteExpense(id, getUserId(req), getUserName(req));
        return ResponseEntity.noContent().build();
    }

    // ─── BILL / RECEIPT (stored as BLOB) ───────────────────────────────────────

    /** POST /api/project-expenses/{id}/receipt  — stores the bill bytes in the DB */
    @PostMapping("/{id}/receipt")
    public ResponseEntity<Map<String, String>> uploadReceipt(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest req) {
        String url = expenseService.uploadReceipt(id, file, getUserId(req), getUserName(req));
        return ResponseEntity.ok(Map.of("receiptUrl", url));
    }

    /** GET /api/project-expenses/{id}/bill  — streams the stored bill bytes */
    @GetMapping("/{id}/bill")
    public void getBill(@PathVariable Long id, HttpServletResponse response) {
        expenseService.streamBill(id, response);
    }

    // ─── ADVANCE ENDPOINTS ────────────────────────────────────────────────────

    @PostMapping("/advances")
    public ResponseEntity<ProjectAdvanceResponse> createAdvance(
            @RequestBody ProjectAdvanceRequest request, HttpServletRequest req) {
        return ResponseEntity.ok(expenseService.createAdvance(request, getUserId(req), getUserName(req)));
    }

    @GetMapping("/advances")
    public ResponseEntity<Page<ProjectAdvanceResponse>> getAdvances(
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(expenseService.getAdvances(nullIfBlank(projectId), page, size));
    }

    @GetMapping("/advances/{id}")
    public ResponseEntity<ProjectAdvanceResponse> getAdvance(@PathVariable Long id) {
        return ResponseEntity.ok(expenseService.getAdvanceById(id));
    }

    @PatchMapping("/advances/{id}/status")
    public ResponseEntity<ProjectAdvanceResponse> updateAdvanceStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        return ResponseEntity.ok(
            expenseService.updateAdvanceStatus(id, body.get("status"), getUserId(req), getUserName(req)));
    }

    // ─── STATS ENDPOINTS ─────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<ProjectExpenseStatsResponse> getStats(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String paymentMode,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long   createdBy,
            HttpServletRequest req) {

        // ── Resolve caller identity from headers (same as getExpenses) ─────
        Long   userId   = getUserId(req);
        String userRole = req.getHeader("X-User-Role");
        if (userRole == null) userRole = req.getHeader("User-Role");

        // ── Build a scratch filter and apply the same visibility rules ──────
        ExpenseFilterRequest vis = new ExpenseFilterRequest();
        if (userId != null && userId > 0) {
            expenseService.applyVisibilityToFilter(vis, userId, userRole);
        }

        List<String> accessibleProjectIds = vis.getAccessibleProjectIds();
        Long effectiveCreatedBy = vis.getViewerUserId();
        List<Long> teamIds = vis.getTeamMemberIds();

        java.time.LocalDate from = (dateFrom != null && !dateFrom.isBlank()) ? java.time.LocalDate.parse(dateFrom) : null;
        java.time.LocalDate to   = (dateTo   != null && !dateTo.isBlank())   ? java.time.LocalDate.parse(dateTo)   : null;

        // Simple project-only fast path only for full-access users with no extra filters
        if (accessibleProjectIds == null && effectiveCreatedBy == null
                && projectId != null && !projectId.isBlank()
                && (status == null || status.isBlank())
                && (category == null || category.isBlank())
                && (paymentMode == null || paymentMode.isBlank())
                && (dateFrom == null || dateFrom.isBlank())
                && (dateTo == null || dateTo.isBlank())
                && (search == null || search.isBlank())) {
            return ResponseEntity.ok(expenseService.getExpenseStats(projectId));
        }

        // If user is scoped to specific projects and a projectId filter is also specified,
        // intersect them (only show data if the requested project is in their accessible list)
        if (accessibleProjectIds != null && projectId != null && !projectId.isBlank()) {
            if (!accessibleProjectIds.contains(projectId)) {
                return ResponseEntity.ok(expenseService.emptyStats());
            }
            accessibleProjectIds = java.util.List.of(projectId);
        }

        return ResponseEntity.ok(expenseService.getGlobalStatsForAccess(
            nullIfBlank(groupName), nullIfBlank(subGroupName),
            nullIfBlank(projectId),
            nullIfAll(status), nullIfAll(paymentMode),
            from, to,
            nullIfBlank(search), effectiveCreatedBy, teamIds, accessibleProjectIds
        ));
    }

    @GetMapping("/stats/global")
    public ResponseEntity<ProjectExpenseStatsResponse> getGlobalStats(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        return ResponseEntity.ok(
            expenseService.getGlobalStats(nullIfBlank(groupName), nullIfBlank(subGroupName)));
    }

    // ─── HISTORY ENDPOINTS ────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<Page<ProjectExpenseHistory>> getHistory(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            expenseService.getHistory(nullIfBlank(projectId), nullIfBlank(groupName),
                nullIfBlank(subGroupName), page, size));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<ProjectExpenseHistory>> getExpenseHistory(@PathVariable Long id) {
        return ResponseEntity.ok(expenseService.getExpenseHistory(id));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private String nullIfBlank(String v) { return (v == null || v.isBlank()) ? null : v; }
    private String nullIfAll(String v)   { return (v == null || v.isBlank() || "all".equalsIgnoreCase(v)) ? null : v; }
}