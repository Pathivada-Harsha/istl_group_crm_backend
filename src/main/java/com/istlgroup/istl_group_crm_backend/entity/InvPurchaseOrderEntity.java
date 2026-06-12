package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventory-specific Purchase Order.
 * Stored in inv_purchase_orders — deliberately separate from the CRM
 * purchase_orders table to avoid coupling inventory and sales procurement.
 */
@Entity
@Table(
    name = "inv_purchase_orders",
    indexes = {
        @Index(name = "idx_inv_po_no",          columnList = "po_no"),
        @Index(name = "idx_inv_po_vendor",       columnList = "vendor_id"),
        @Index(name = "idx_inv_po_warehouse",    columnList = "warehouse_id"),
        @Index(name = "idx_inv_po_group",        columnList = "group_name"),
        @Index(name = "idx_inv_po_project",      columnList = "project_id"),
        @Index(name = "idx_inv_po_status",       columnList = "status")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvPurchaseOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Auto-generated: INV-PO-YYYY-NNNNN — set after first persist. */
    @Column(name = "po_no", unique = true, length = 80)
    private String poNo;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_name", length = 255)
    private String vendorName;

    @Column(name = "vendor_contact", length = 150)
    private String vendorContact;

    /** FK to warehouses.id — optional but recommended for stock tracking. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;

    @Column(name = "project_id", length = 225)
    private String projectId;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "expected_delivery")
    private LocalDate expectedDelivery;

    /**
     * DRAFT → SENT → APPROVED → PARTIAL → RECEIVED | CANCELLED
     */
    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "payment_status", length = 30)
    @Builder.Default
    private String paymentStatus = "PENDING";

    @Column(name = "total_value", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalValue = BigDecimal.ZERO;

    @Column(name = "total_items_ordered")
    @Builder.Default
    private Integer totalItemsOrdered = 0;

    @Column(name = "total_items_received")
    @Builder.Default
    private Integer totalItemsReceived = 0;

    @Column(name = "payment_terms", length = 255)
    private String paymentTerms;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(
        mappedBy = "purchaseOrder",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<InvPurchaseOrderItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (orderDate == null) orderDate = LocalDate.now();
    }
}