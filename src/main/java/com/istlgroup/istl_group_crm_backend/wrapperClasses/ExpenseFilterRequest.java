package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ExpenseFilterRequest {
    // ── Group/Project filters ─────────────────────────────────────────────────
    private String    groupName;
    private String    subGroupName;
    private String    projectId;

    // ── Expense filters ───────────────────────────────────────────────────────
    private String    category;
    private String    status;
    private String    paymentMode;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String    search;

    // ── Sorting ───────────────────────────────────────────────────────────────
    private String    sortBy  = "tripDate";
    private String    sortDir = "desc";

    // ── Pagination ────────────────────────────────────────────────────────────
    private int       page = 0;
    private int       size = 10;

    // ── Role-based visibility (populated by controller, applied in JPQL) ──────
    /**
     * null  → no user restriction (SUPERADMIN / ADMIN sees everything)
     * non-null → restrict to records where createdBy=userId OR paidByUserId=userId
     *            OR (if manager) createdBy/paidBy IN teamMemberIds
     */
    private Long        viewerUserId;

    /**
     * For managers: IDs of all direct reports (manager_id = viewerUserId).
     * null or empty → no team expansion (regular user or admin).
     */
    private List<Long>  teamMemberIds;

    /**
     * Non-null when the user has explicit project grants (project_access table).
     * When set, data is scoped to these project IDs instead of using viewerUserId.
     * null → bypass (admin) or fallback (viewerUserId used instead).
     */
    private List<String> accessibleProjectIds;
}