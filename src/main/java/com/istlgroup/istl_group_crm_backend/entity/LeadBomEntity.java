package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A single Bill of Materials line for a lead (many per lead).
 *
 * Prepared from the lead's technical scope: {@code scopeItemId} points at the
 * {@link LeadScopeItemEntity} activity that needs this material. It is nullable
 * so a material can stand alone, and is deliberately not a hard FK because
 * scope items are soft-deleted.
 *
 * {@code amount} is normally quantity * unitRate but is persisted as supplied so
 * lump-sum lines (quantity/rate left blank) still work — same rule as
 * {@link ProjectBomEntity}.
 */
@Entity
@Table(name = "lead_bom")
@Data
public class LeadBomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "scope_item_id")
    private Long scopeItemId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo = 1;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    @Column(name = "make", length = 150)
    private String make;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    /** Catalog item + chosen make (pick-a-make). Nullable = free-text/legacy line. */
    @Column(name = "bom_item_id")
    private Long bomItemId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "quantity", precision = 18, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_rate", precision = 18, scale = 2)
    private BigDecimal unitRate = BigDecimal.ZERO;

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
