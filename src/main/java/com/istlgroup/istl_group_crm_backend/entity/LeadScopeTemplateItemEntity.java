package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A standard scope line on a {@link LeadScopeTemplateEntity} (many per template).
 * Mirrors {@link LeadScopeItemEntity}; the denormalized {@code projectType} lets
 * the suggestion engine query by type without a join.
 */
@Entity
@Table(name = "lead_scope_template_items")
@Data
public class LeadScopeTemplateItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "project_type", nullable = false, length = 150)
    private String projectType;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "activity", nullable = false, length = 500)
    private String activity;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "unit", length = 50)
    private String unit;

    /**
     * This line's share of the template's 100%. A project generated from the
     * template copies it verbatim onto {@code project_phases.weight_pct} (same
     * precision, so no rounding on the way across). NULL means "never set" and
     * generation falls back to an even split.
     */
    @Column(name = "weight_pct", precision = 9, scale = 6)
    private BigDecimal weightPct;

    /**
     * TRUE when the user typed this weight in (it is "pinned" and holds its
     * value); FALSE when it is auto-balanced against the pinned lines so the
     * template still totals 100%. Persisted because otherwise reopening a
     * template would re-even-split and wipe the weights just saved.
     */
    @Column(name = "weight_manual", nullable = false)
    private Boolean weightManual = false;

    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Second-level breakdown under this activity: {@code {name, description, unit,
     * weightPct, weightManual}} — a subset of {@code project_phases.sub_items}, so a
     * lead scope item or a project phase seeded from it needs no translation.
     *
     * <p>Edited on the Scope Lines tab of the templates admin page, and carried onto a
     * lead or project by Suggest. Sub-item weights are a share of THIS line and total
     * 100 within it. Read/written via {@code service.scope.ScopeSubItems}.
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
