package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A single Bill of Materials / Bill of Quantities line for a project.
 * amount is normally quantity * unitRate but is persisted as supplied so that
 * lump-sum lines (quantity/rate left blank) still work.
 *
 * <p>Mirrors {@link LeadBomEntity}: BOM lines hang off the technical scope via
 * {@code scopeItemId} → {@link ProjectPhaseEntity} (the parent phase). Unlike the
 * lead side — whose scope is a flat list — the project scope is two-level (parent
 * phase + {@code sub_items} JSON), so {@code scopeSubItemKey} optionally pins a line
 * below a named sub-item. The catalog/basis snapshot ({@code basis}, {@code driverAttr},
 * …) lets a reloaded BOM recompute quantities live, exactly like the lead BOM.
 */
@Entity
@Table(name = "project_bom")
@Data
public class ProjectBomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
     * {@link ProjectPhaseEntity#getId()} — the scope line (parent phase) this material
     * sits under. Nullable (a line can stand alone in the "General" bucket) and
     * deliberately not a hard FK because scope is rebuilt on save (soft deletes).
     */
    @Column(name = "scope_item_id")
    private Long scopeItemId;

    /** Optional sub-item name within the parent phase (project scope is two-level). */
    @Column(name = "scope_sub_item_key")
    private String scopeSubItemKey;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "category")
    private String category;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "make")
    private String make;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    /** Catalog item + chosen make (pick-a-make). Nullable = free-text/legacy line. */
    @Column(name = "bom_item_id")
    private Long bomItemId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "unit")
    private String unit;

    @Column(name = "quantity", precision = 18, scale = 3, nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_rate", precision = 18, scale = 2, nullable = false)
    private BigDecimal unitRate = BigDecimal.ZERO;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    /**
     * GST rate for this line, COPIED from the catalogue default
     * ({@code bom_items_master.default_tax_percent}) when the line is created, exactly
     * as the unit rate is. Never re-read live: a later catalogue change would otherwise
     * retroactively alter a past project's planned GST.
     *
     * <p>Null is meaningful — a line with no catalogue reference has no default rate,
     * and reporting it as 0% would understate planned GST.
     */
    @Column(name = "gst_percent", precision = 5, scale = 2)
    private BigDecimal gstPercent;

    @Column(name = "notes")
    private String notes;

    // ── Auto-sizing: quantity basis snapshot (kept so a reloaded BOM stays live) ──
    /** FIXED | PER_KW | PER_STEP | FROM_SITE_VISIT | PER_WATT_PEAK | PER_INVERTER_KW | PER_MODULE | PER_INVERTER. Null = legacy static line. */
    @Column(name = "basis", length = 20)
    private String basis;

    @Column(name = "basis_value", precision = 18, scale = 4)
    private BigDecimal basisValue;

    @Column(name = "step_value", precision = 18, scale = 4)
    private BigDecimal stepValue;

    @Column(name = "site_visit_field", length = 60)
    private String siteVisitField;

    /** Numeric driver snapshot of the chosen variant (module Wp / inverter kW). */
    @Column(name = "driver_attr", precision = 18, scale = 4)
    private BigDecimal driverAttr;

    /** true = quantity auto-derives from the basis; false = user typed a manual quantity. */
    @Column(name = "auto_qty")
    private Boolean autoQty = Boolean.TRUE;

    // ── Audit ────────────────────────────────────────────────────────────────
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Soft delete — {@code deleted_at IS NULL} is the live filter on every read. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
