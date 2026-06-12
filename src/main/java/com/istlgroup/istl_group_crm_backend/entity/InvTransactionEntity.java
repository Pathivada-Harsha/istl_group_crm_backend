package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Every stock movement in the inventory — inward, outward, adjustment,
 * transfer, or return — is recorded as an immutable row here.
 *
 * stored in inv_transactions (separate from any existing CRM transaction tables).
 * The service also updates inventory_items.current_qty when a transaction is saved.
 */
@Entity
@Table(
    name = "inv_transactions",
    indexes = {
        @Index(name = "idx_inv_txn_no",       columnList = "txn_no"),
        @Index(name = "idx_inv_txn_item",      columnList = "inventory_item_id"),
        @Index(name = "idx_inv_txn_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_inv_txn_type",      columnList = "type"),
        @Index(name = "idx_inv_txn_project",   columnList = "project_id"),
        @Index(name = "idx_inv_txn_date",      columnList = "transaction_date")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Auto-generated: INV-TXN-YYYY-NNNNN */
    @Column(name = "txn_no", unique = true, length = 80)
    private String txnNo;

    /**
     * INWARD | OUTWARD | TRANSFER | ADJUSTMENT | RETURN
     */
    @Column(name = "type", length = 20, nullable = false)
    private String type;

    /** FK to inventory_items.id */
    @Column(name = "inventory_item_id")
    private Long inventoryItemId;

    /** Denormalised for history readability even if item is deleted */
    @Column(name = "item_code", length = 80)
    private String itemCode;

    @Column(name = "item_name", length = 255)
    private String itemName;

    @Column(name = "unit", length = 30)
    private String unit;

    /**
     * Positive for INWARD / positive ADJUSTMENT; negative for OUTWARD / TRANSFER / RETURN.
     * The service applies this delta to inventory_items.current_qty.
     */
    @Column(name = "qty", precision = 15, scale = 3, nullable = false)
    private BigDecimal qty;

    /** Warehouse where stock moved in/out. For TRANSFER this is the destination. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /** Source warehouse for TRANSFER transactions (null otherwise). */
    @Column(name = "from_warehouse_id")
    private Long fromWarehouseId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "project_id", length = 225)
    private String projectId;

    /** Reference: PO number, GRN number, Issue slip, etc. */
    @Column(name = "ref_no", length = 150)
    private String refNo;

    // ── Vendor / PO linkage (INWARD from PO delivery) ─────────────────────
    /** Vendor who supplied the items — only set for INWARD from PO */
    @Column(name = "vendor_id")
    private Long vendorId;

    /** Denormalised vendor name for history readability */
    @Column(name = "vendor_name", length = 255)
    private String vendorName;

    /** FK to inv_purchase_orders.id — only set for INWARD from PO */
    @Column(name = "po_id")
    private Long poId;

    /** Denormalised PO number for history */
    @Column(name = "po_no", length = 100)
    private String poNo;

    /** Unit cost at time of transaction — for value calculations */
    @Column(name = "unit_cost", precision = 15, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (transactionDate == null) transactionDate = LocalDate.now();
    }
}