package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A standard scope/BOM template for a project type (= leads.sub_group_name).
 * One active template per project type drives the "Suggest scope / BOM" feature;
 * its scope lines live in {@link LeadScopeTemplateItemEntity} and its BOM lines
 * (with basis rules) in {@link LeadBomTemplateItemEntity}.
 */
@Entity
@Table(name = "lead_scope_template")
@Data
public class LeadScopeTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_type", nullable = false, length = 150)
    private String projectType;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

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
