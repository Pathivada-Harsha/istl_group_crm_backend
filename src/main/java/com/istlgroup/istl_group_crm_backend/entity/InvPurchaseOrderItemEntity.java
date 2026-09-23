package com.istlgroup.istl_group_crm_backend.entity;

import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
    name = "inv_purchase_order_items",
    indexes = {
        @Index(name = "idx_inv_poi_po",   columnList = "purchase_order_id"),
        @Index(name = "idx_inv_poi_item", columnList = "inventory_item_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvPurchaseOrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @JsonBackReference
    private InvPurchaseOrderEntity purchaseOrder;

    /**
     * Optional FK to inventory_items.id.
     * Null = item not yet in inventory (will be created on GRN).
     */
    @Column(name = "inventory_item_id")
    private Long inventoryItemId;

    @Column(name = "item_code", length = 80)
    private String itemCode;

    @Column(name = "item_name", length = 255)
    private String itemName;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "ordered_qty", precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal orderedQty = BigDecimal.ZERO;

    @Column(name = "received_qty", precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal receivedQty = BigDecimal.ZERO;

    @Column(name = "rate", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal rate = BigDecimal.ZERO;

    /** GST / tax percentage (e.g. 18 for 18 %). */
    @Column(name = "tax_pct", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxPct = BigDecimal.ZERO;

    @Column(name = "notes", length = 500)
    private String notes;

    // ── Derived helpers ──────────────────────────────────────────────────────

    @Transient
    public BigDecimal getSubtotal() {
        return MoneyRounding.money((orderedQty == null ? BigDecimal.ZERO : orderedQty)
            .multiply(rate == null ? BigDecimal.ZERO : rate));
    }

    @Transient
    public BigDecimal getTaxAmount() {
        // percentOf, not a bare divide(new BigDecimal("100")). The bare form did
        // not throw — the quotient always terminates — but it returned a value
        // carried to the sum of the operand scales, which made the pre-round total
        // an over-precise number for the round-off to be measured against.
        return MoneyRounding.percentOf(getSubtotal(), taxPct);
    }

    @Transient
    public BigDecimal getLineTotal() {
        return getSubtotal().add(getTaxAmount());
    }
}