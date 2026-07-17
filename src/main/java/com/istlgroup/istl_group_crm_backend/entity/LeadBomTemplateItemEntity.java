package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A standard BOM line on a {@link LeadScopeTemplateEntity} (many per template),
 * and the authority on HOW a material scales with capacity.
 *
 * {@code basis} + its companion columns turn a template into real quantities for
 * a given capacity, and also resolve the basis of a mined BOM line (matched by
 * {@code matchKey}). {@code scopeActivity} groups the material under a scope line.
 */
@Entity
@Table(name = "lead_bom_template_items")
@Data
public class LeadBomTemplateItemEntity {

    /** qty = basisValue (independent of capacity). */
    public static final String BASIS_FIXED = "FIXED";
    /** qty = basisValue × capacityKw. */
    public static final String BASIS_PER_KW = "PER_KW";
    /** qty = ceil(capacityKw / stepValue) — e.g. one 50 kW inverter per 50 kW. */
    public static final String BASIS_PER_STEP = "PER_STEP";
    /** qty parsed from the named site_visit column; blank → left for manual entry. */
    public static final String BASIS_FROM_SITE_VISIT = "FROM_SITE_VISIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "project_type", nullable = false, length = 150)
    private String projectType;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    /** Scope line this material sits under (by name); null = "General". */
    @Column(name = "scope_activity", length = 500)
    private String scopeActivity;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    /** Normalised item_name used to resolve a mined line's basis. */
    @Column(name = "match_key", length = 300)
    private String matchKey;

    @Column(name = "make", length = 150)
    private String make;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "basis", nullable = false, length = 20)
    private String basis = BASIS_PER_KW;

    @Column(name = "basis_value", precision = 18, scale = 4)
    private BigDecimal basisValue;

    @Column(name = "step_value", precision = 18, scale = 4)
    private BigDecimal stepValue;

    @Column(name = "site_visit_field", length = 60)
    private String siteVisitField;

    @Column(name = "default_unit_rate", precision = 18, scale = 2)
    private BigDecimal defaultUnitRate;

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
