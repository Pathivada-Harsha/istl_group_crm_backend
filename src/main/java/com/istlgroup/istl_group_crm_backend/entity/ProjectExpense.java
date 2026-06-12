package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "project_expenses", indexes = {
    @Index(name = "idx_pe_project",  columnList = "projectId"),
    @Index(name = "idx_pe_group",    columnList = "groupName"),
    @Index(name = "idx_pe_subgroup", columnList = "subGroupName"),
    @Index(name = "idx_pe_date",     columnList = "tripDate"),
    @Index(name = "idx_pe_deleted",  columnList = "isDeleted"),
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expense_code", unique = true, nullable = false, length = 50)
    private String expenseCode;

    // ── Project / Group / SubGroup ────────────────────────────────────────────
    @Column(name = "project_id", length = 50)
    private String projectId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    // ── Expense Header ─────────────────────────────────────────────────────────
    // "tripDate" kept for DB compatibility; semantically it's the expense date
    @Column(name = "trip_date", nullable = false)
    private LocalDate tripDate;

    @Column(name = "trip_reason", columnDefinition = "TEXT")
    private String tripReason;

    // ── Aggregated total (sum of all child items) ─────────────────────────────
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    // ── People ────────────────────────────────────────────────────────────────
    @Column(name = "paid_by_user_id")
    private Long paidByUserId;

    @Column(name = "paid_by_name", length = 200)
    private String paidByName;

    // ── Status ────────────────────────────────────────────────────────────────
    // Overall pipeline status: Pending | Approved | Rejected
    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "receipt_url", length = 500)
    private String receiptUrl;

    // ══════════════════════════════════════════════════════════════════════════
    //  MULTI-STAGE APPROVAL WORKFLOW
    //  Stage 1 = Reporting Manager
    //  Stage 2 = Chief Financial Officer (role ACCOUNTS_CFO)
    //  Stage 3 = Final Authorization (Accounts Manager / Super Admin)
    //  Each stage status ∈ { WAITING, PENDING, APPROVED, REJECTED }
    //  approvalStage    ∈ { MANAGER, CFO, FINAL, COMPLETED, REJECTED }
    // ══════════════════════════════════════════════════════════════════════════
    @Column(name = "approval_stage", length = 20)
    private String approvalStage;

    @Column(name = "reporting_manager_id")
    private Long reportingManagerId;

    @Column(name = "reporting_manager_name", length = 200)
    private String reportingManagerName;

    // Stage 1 — Reporting Manager
    @Column(name = "stage1_status", length = 20)  private String  stage1Status;
    @Column(name = "stage1_by_id")                private Long    stage1ById;
    @Column(name = "stage1_by_name", length = 200) private String stage1ByName;
    @Column(name = "stage1_at")                   private LocalDateTime stage1At;
    @Column(name = "stage1_remarks", length = 500) private String stage1Remarks;

    // Stage 2 — Chief Financial Officer
    @Column(name = "stage2_status", length = 20)  private String  stage2Status;
    @Column(name = "stage2_by_id")                private Long    stage2ById;
    @Column(name = "stage2_by_name", length = 200) private String stage2ByName;
    @Column(name = "stage2_at")                   private LocalDateTime stage2At;
    @Column(name = "stage2_remarks", length = 500) private String stage2Remarks;

    // Stage 3 — Final Authorization
    @Column(name = "stage3_status", length = 20)  private String  stage3Status;
    @Column(name = "stage3_by_id")                private Long    stage3ById;
    @Column(name = "stage3_by_name", length = 200) private String stage3ByName;
    @Column(name = "stage3_at")                   private LocalDateTime stage3At;
    @Column(name = "stage3_remarks", length = 500) private String stage3Remarks;

    // ── Payment (Accounts Executive marks paid after final approval) ──────────
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;            // UNPAID | PAID

    @Column(name = "paid_by_exec_id")        private Long    paidByExecId;
    @Column(name = "paid_by_exec_name", length = 200) private String paidByExecName;
    @Column(name = "paid_at")                private LocalDateTime paidAt;

    // ── Line Items (child records) ────────────────────────────────────────────
    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    @Builder.Default
    private List<ProjectExpenseItem> expenseItems = new ArrayList<>();

    // ── Audit ─────────────────────────────────────────────────────────────────
    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;
}