package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Purchase Order Item Entity - Line items in a PO with delivery tracking
 */
@Entity
@Table(name = "purchase_order_items", indexes = {
    @Index(name = "idx_po_id", columnList = "po_id"),
    @Index(name = "idx_item_sku", columnList = "item_sku"),
    @Index(name = "idx_po_id_line_no", columnList = "po_id, line_no")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "po_id", nullable = false)
    @JsonBackReference  // Prevents circular reference in JSON
    private PurchaseOrderEntity purchaseOrder;
    
    @Column(name = "line_no")
    private Integer lineNo;
    
    @Column(name = "item_sku", length = 120)
    private String itemSku;
    
    @Column(name = "item_name")
    private String itemName;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "hsn_code", length = 20)
    private String hsnCode;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "quantity", precision = 18, scale = 6, nullable = false)
    private BigDecimal quantity;
    
    @Column(name = "delivered_qty", precision = 18, scale = 6, nullable = false)
    private BigDecimal deliveredQty;
    
    @Column(name = "pending_qty", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal pendingQty;
    
    @Column(name = "unit_price", precision = 18, scale = 6, nullable = false)
    private BigDecimal unitPrice;
    
    @Column(name = "tax_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal taxPercent;
    
    @Column(name = "line_total", precision = 18, scale = 6, insertable = false, updatable = false)
    private BigDecimal lineTotal;
    
    @Column(name = "delivery_schedule")
    private String deliverySchedule;

    /** Make/brand carried over from the project BOM line. */
    @Column(name = "make", length = 255)
    private String make;

    // ── Project BOM linkage ──────────────────────────────────────────────────
    /**
     * {@code project_bom.id} this line consumes — the key every quantity cap is
     * computed against. Stable across BOM edits because
     * {@code ProjectDetailService.saveBom} merges by id. Null = unresolved.
     */
    @Column(name = "bom_line_id")
    private Long bomLineId;

    /** Catalogue snapshot ({@code bom_items_master.id}) — repair key if bomLineId dangles. */
    @Column(name = "bom_item_id")
    private Long bomItemId;

    /** Chosen make snapshot ({@code bom_item_variants.id}). */
    @Column(name = "variant_id")
    private Long variantId;

    /**
     * How this line was tied to the BOM: ID | VARIANT | NAME | NONE.
     * <p>NULL is a persisted fact, not a missing value: it means the row was written
     * BEFORE BOM enforcement existed, which is what grandfathers old purchase orders
     * so they stay editable. Every write path stamps this non-null from now on.
     */
    @Column(name = "bom_match", length = 12)
    private String bomMatch;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (deliveredQty == null) deliveredQty = BigDecimal.ZERO;
        // pending_qty and line_total are calculated by database
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // pending_qty and line_total are calculated by database
    }
}