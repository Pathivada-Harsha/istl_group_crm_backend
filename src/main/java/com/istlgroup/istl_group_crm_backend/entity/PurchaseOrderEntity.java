package com.istlgroup.istl_group_crm_backend.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Purchase Order Entity - Matches existing purchase_orders table
 */
@Entity
@Table(name = "purchase_orders", indexes = {
    @Index(name = "idx_po_no", columnList = "po_no"),
    @Index(name = "idx_vendor_id", columnList = "vendor_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_group_name", columnList = "group_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "po_no", unique = true, length = 80, nullable = false)
    private String poNo;
    
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;
    
    @Column(name = "quotation_id")
    private Long quotationId;
    
    @Column(name = "rfq_id", length = 80)
    private String rfqId;
    
    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;
    
    @Column(name = "expected_delivery")
    private LocalDateTime expectedDelivery;
    
    @Column(name = "status", length = 20)
    private String status;
    
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;
    
    @Column(name = "total_value", precision = 18, scale = 2)
    private BigDecimal totalValue;
    
    @Column(name = "total_items_ordered")
    private Integer totalItemsOrdered;
    
    @Column(name = "total_items_delivered")
    private Integer totalItemsDelivered;
    
    @Column(name = "total_items_pending", insertable = false, updatable = false)
    private Integer totalItemsPending;
    
    @Column(name = "group_name", length = 50)
    private String groupName;
    
    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;
    
    @Column(name = "project_id", length = 100)
    private String projectId;
    
    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;
    
    @Column(name = "delivery_terms", length = 255)
    private String deliveryTerms;
    
    @Column(name = "payment_terms", length = 100)
    private String paymentTerms;
    
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "category", length = 100)
    private String category;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "approved_by")
    private Long approvedBy;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    // Relationships - NO CASCADE to avoid circular reference issues
    @OneToMany(mappedBy = "purchaseOrder", fetch = FetchType.LAZY)
    @JsonManagedReference  // Prevents circular reference in JSON
    @Builder.Default
    private List<PurchaseOrderItemEntity> items = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (orderDate == null) orderDate = now;
        if (status == null) status = "Draft";
        if (paymentStatus == null) paymentStatus = "Pending";
        if (totalItemsOrdered == null) totalItemsOrdered = 0;
        if (totalItemsDelivered == null) totalItemsDelivered = 0;
        // total_items_pending is calculated by database
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // total_items_pending is calculated by database
    }
}