package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An extra budget allocation on a lead's estimate — a cost not tied to a BOM
 * material: freight, contingency, site overheads, insurance, and so on.
 *
 * basis = FIXED   → {@code amount} is {@code rateValue} rupees, entered directly.
 * basis = PERCENT → {@code amount} is recomputed as {@code rateValue}% of the
 *                   current BOM subtotal, so the figure stays right when the BOM
 *                   changes. The stored amount is a cache, never trusted as input.
 */
@Entity
@Table(name = "lead_budget_extra")
@Data
public class LeadBudgetExtraEntity {

    public static final String BASIS_FIXED = "FIXED";
    public static final String BASIS_PERCENT = "PERCENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "basis", nullable = false, length = 20)
    private String basis = BASIS_FIXED;

    @Column(name = "rate_value", precision = 18, scale = 4)
    private BigDecimal rateValue = BigDecimal.ZERO;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "notes", length = 500)
    private String notes;

    // ── Audit ────────────────────────────────────────────────────────────────
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
