package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Quotation Entity - Matches existing quotations table
 * For Procurement quotations received from vendors
 */
@Entity
@Table(name = "quotations", indexes = {
    @Index(name = "idx_quote_no", columnList = "quote_no"),
    @Index(name = "idx_vendor_id", columnList = "vendor_id"),
    @Index(name = "idx_rfq_id", columnList = "rfq_id"),
    @Index(name = "idx_type", columnList = "type"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_group_name", columnList = "group_name"),
    @Index(name = "idx_valid_till", columnList = "valid_till")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "quote_no", unique = true, length = 80)
    private String quoteNo;
    
    @Column(name = "lead_id")
    private Long leadId;
    
    @Column(name = "proposal_id")
    private Long proposalId;
    
    @Column(name = "customer_id")
    private Long customerId;
    
    @Column(name = "vendor_id")
    private Long vendorId;
    
    @Column(name = "rfq_id", length = 80)
    private String rfqId;
    
    // Type: 'Sales' or 'Procurement'
    @Column(name = "type", length = 20)
    private String type;
    
    // Status: 'New', 'Under Review', 'Shortlisted', 'Approved', 'Rejected', 'Expired'
    @Column(name = "status", length = 50)
    private String status;
    
    @Column(name = "valid_till")
    private LocalDate validTill;
    
    @Column(name = "total_value", precision = 18, scale = 2)
    private BigDecimal totalValue;
    
    @Column(name = "prepared_by")
    private Long preparedBy;
    
    // Group: 'CCMS', 'Solar', 'EPC', 'IoT', 'Hybrid', 'Others'
    @Column(name = "group_name", length = 50)
    private String groupName;
    
    // Additional fields for filtering
    @Column(name = "sub_group_name", length = 100)
    private String subGroupName;
    
    @Column(name = "project_id", length = 100)
    private String projectId;
    
    // Additional quotation details
    @Column(name = "vendor_contact", length = 20)
    private String vendorContact;
    
    @Column(name = "vendor_rating")
    private Double vendorRating;
    
    @Column(name = "delivery_time", length = 100)
    private String deliveryTime;
    
    @Column(name = "payment_terms", length = 100)
    private String paymentTerms;
    
    @Column(name = "warranty", length = 100)
    private String warranty;
    
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    
    @Column(name = "category", length = 100)
    private String category;
    
    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Lob
    @Column(name = "quotation_file", columnDefinition = "LONGBLOB")
    private byte[] quotationFile;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "po_id")
    private Long poId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Relationships
    // NOTE: NO cascade - we manage items manually to avoid quotation_id = null issues
    @OneToMany(mappedBy = "quotation", fetch = FetchType.LAZY)
    @com.fasterxml.jackson.annotation.JsonManagedReference
    @Builder.Default
    private List<QuotationItemEntity> items = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "New";
        }
        if (type == null) {
            type = "Procurement";
        }
    }
    
    // Helper methods
    @Transient
    public boolean hasFile() {
        return quotationFile != null && quotationFile.length > 0;
    }
}