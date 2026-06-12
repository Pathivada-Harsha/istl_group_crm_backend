package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ProjectExpenseRequest {
    // ── Project assignment ────────────────────────────────────────────────────
    private String    projectId;
    private String    groupName;       // stored in project_expenses.group_name
    private String    subGroupName;    // stored in project_expenses.sub_group_name

    // ── Trip info ─────────────────────────────────────────────────────────────
    private LocalDate tripDate;
    private String    visitType;
    private String    tripReason;
    private String    tripOutcome;

    // ── People ────────────────────────────────────────────────────────────────
    private Long      paidByUserId;
    private String    paidByName;      // ← was missing
    private Long      approvedByUserId;
    private String    approvedByName;  // ← was missing

    // ── Status ────────────────────────────────────────────────────────────────
    private String    status;

    // ── Commission (only when category = Commission) ──────────────────────────
    private String     commissionType;
    private String     commissionGivenTo;
    private BigDecimal commissionPercentage;
    private BigDecimal commissionFixedAmount;
    private String     salesOrderRef;

    // ── Line items ────────────────────────────────────────────────────────────
    private List<ExpenseItemRequest> expenseItems;

    @Data
    public static class ExpenseItemRequest {
        private String     category;
        private BigDecimal amount;
        private String     paymentMode;
        private String     description;
    }
}