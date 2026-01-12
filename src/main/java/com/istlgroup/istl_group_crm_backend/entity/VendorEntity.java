package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Vendor Entity - Only contains vendors we've purchased from
 * Linked to Purchase Orders and Quotations
 */
@Entity
@Table(name = "vendors", indexes = {
    @Index(name = "idx_vendor_code", columnList = "vendor_code"),
    @Index(name = "idx_name", columnList = "name"),
    @Index(name = "idx_group_subgroup", columnList = "group_name, sub_group_name"),
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "vendor_code", unique = true, length = 50)
    private String vendorCode;
    
    @Column(name = "name", nullable = false)
    private String name;
    
    @Column(name = "contact_person")
    private String contactPerson;
    
    @Column(name = "email")
    private String email;
    
    @Column(name = "phone", length = 20)
    private String phone;
    
    @Column(name = "website")
    private String website;
    
    @Column(name = "gst_number", length = 15)
    private String gstNumber;
    
    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
    
    @Column(name = "city")
    private String city;
    
    @Column(name = "state")
    private String state;
    
    @Column(name = "pincode", length = 10)
    private String pincode;
    
    @Column(name = "rating", columnDefinition = "TINYINT UNSIGNED")
    private Integer rating;
    
    @Column(name = "status", length = 20)
    private String status;
    
    // Project assignment - REQUIRED for filtering
    @Column(name = "group_name", nullable = false)
    private String groupName;
    
    @Column(name = "sub_group_name")
    private String subGroupName;
    
    @Column(name = "project_id", nullable = false)
    private String projectId;
    
    @Column(name = "vendor_type", length = 50)
    private String vendorType;
    
    @Column(name = "category", length = 100)
    private String category;
    
    // Purchase tracking
    @Column(name = "last_purchase_amount", precision = 15, scale = 2)
    private BigDecimal lastPurchaseAmount;
    
    @Column(name = "total_purchase_value", precision = 15, scale = 2)
    private BigDecimal totalPurchaseValue;
    
    @Column(name = "total_orders")
    private Integer totalOrders;
    
    @Column(name = "last_purchase_date")
    private LocalDateTime lastPurchaseDate;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    // Audit fields
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "assigned_to")
    private Long assignedTo;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (totalOrders == null) totalOrders = 0;
        if (totalPurchaseValue == null) totalPurchaseValue = BigDecimal.ZERO;
        if (lastPurchaseAmount == null) lastPurchaseAmount = BigDecimal.ZERO;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}