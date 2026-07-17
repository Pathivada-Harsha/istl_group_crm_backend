package com.istlgroup.istl_group_crm_backend.entity;

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
