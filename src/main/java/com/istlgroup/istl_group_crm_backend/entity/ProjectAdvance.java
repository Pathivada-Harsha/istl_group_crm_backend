package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "project_advances", indexes = {
    @Index(name = "idx_adv_project",  columnList = "projectId"),
    @Index(name = "idx_adv_group",    columnList = "groupName"),
    @Index(name = "idx_adv_subgroup", columnList = "subGroupName"),
    @Index(name = "idx_adv_status",   columnList = "status"),
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAdvance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_code", unique = true, nullable = false, length = 50)
    private String advanceCode;

    // ── Project / Group / SubGroup ────────────────────────────────────────────
    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    // ── Trip Info ─────────────────────────────────────────────────────────────
    @Column(name = "advance_date", nullable = false)
    private LocalDate advanceDate;

    @Column(name = "expected_trip_date")
    private LocalDate expectedTripDate;

    @Column(name = "trip_purpose", nullable = false, columnDefinition = "TEXT")
    private String tripPurpose;

    // ── People ────────────────────────────────────────────────────────────────
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_by_name", length = 200)
    private String requestedByName;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_by_name", length = 200)
    private String approvedByName;

    // ── Financials ────────────────────────────────────────────────────────────
    @Column(name = "total_advance_amount", precision = 15, scale = 2)
    private BigDecimal totalAdvanceAmount;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "related_expense_id")
    private Long relatedExpenseId;

    // ── Payments ──────────────────────────────────────────────────────────────
    @OneToMany(mappedBy = "advance", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ProjectAdvancePayment> payments;

    // ── Audit ─────────────────────────────────────────────────────────────────
    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}