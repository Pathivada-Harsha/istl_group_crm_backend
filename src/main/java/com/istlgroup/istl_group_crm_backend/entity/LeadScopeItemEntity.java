package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A single technical-scope activity/line item for a lead (many per lead).
 */
@Entity
@Table(name = "lead_scope_items")
@Data
public class LeadScopeItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "seq_no")
    private Integer seqNo = 1;

    @Column(name = "activity", nullable = false, length = 500)
    private String activity;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "quantity", precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Second-level breakdown under this activity, same JSON array shape as
     * {@code lead_scope_template_items.sub_items} and a subset of
     * {@code project_phases.sub_items} — {@code {name, description, unit, weightPct,
     * weightManual}}. Seeded from the template by Suggest, then editable on the lead's
     * Technical Scope tab independently of the template. NULL = never broken down.
     *
     * <p>Sub-item weights are a share of THIS line and total 100 within it, never a
     * share of the whole scope. See {@code service.scope.ScopeSubItems}.
     */
    @Column(name = "sub_items", columnDefinition = "JSON")
    private String subItems;

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
