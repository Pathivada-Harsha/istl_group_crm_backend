package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectExpenseResponse {
    private Long   id;
    private String expenseCode;
    private String projectId;
    private String projectName;
    private String groupName;
    private String subGroupName;

    // Header
    private LocalDate tripDate;
    private String    tripReason;
    private BigDecimal totalAmount;

    // People
    private Long   paidByUserId;
    private String paidByName;

    // Status
    private String status;
    private String receiptUrl;
    private Boolean hasBill;          // true when a bill BLOB is stored

    // ── Approval workflow ─────────────────────────────────────────────────────
    private String approvalStage;     // MANAGER | CFO | FINAL | COMPLETED | REJECTED
    private Long   reportingManagerId;
    private String reportingManagerName;

    private ApprovalStage stage1;     // Reporting Manager
    private ApprovalStage stage2;     // Chief Financial Officer
    private ApprovalStage stage3;     // Final Authorization

    // ── Payment ───────────────────────────────────────────────────────────────
    private String        paymentStatus;   // UNPAID | PAID
    private Long          paidByExecId;
    private String        paidByExecName;
    private LocalDateTime paidAt;

    // Line items (returned inline so frontend can show detail without extra call)
    private List<ExpenseItemResponse> expenseItems;

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class ApprovalStage {
        private String        status;    // WAITING | PENDING | APPROVED | REJECTED
        private Long          byId;
        private String        byName;
        private LocalDateTime at;
        private String        remarks;
    }

    @Data
    @Builder
    public static class ExpenseItemResponse {
        private Long   id;
        private String category;
        private BigDecimal amount;
        private String paymentMode;
        private String description;
    }
}