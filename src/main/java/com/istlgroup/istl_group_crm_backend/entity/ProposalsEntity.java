package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "proposals")
@Data
public class ProposalsEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "proposal_no", unique = true, length = 80)
    private String proposalNo;
    
    @Column(name = "lead_id")
    private Long leadId;
    
    @Column(name = "customer_id")
    private Long customerId;
    
    @Column(name = "title", length = 255)
    private String title;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "prepared_by")
    private Long preparedBy;
    
    @Column(name = "version")
    private Integer version = 1;
    
    @Column(name = "status", length = 50)
    private String status = "Draft"; // Draft, Sent, Approved, Rejected, On Hold
    
    @Column(name = "total_value", precision = 18, scale = 2)
    private BigDecimal totalValue = BigDecimal.ZERO;
    
    @Column(name = "group_name", length = 50)
    private String groupName; // CCMS, Solar, EPC, IoT, Hybrid, Others
    
    @Column(name = "sub_group_name", length = 50)
    private String subGroupName; // Category/Sub-group
    
    // Template fields
    @Column(name = "company_name", length = 255)
    private String companyName = "SESOLA POWER PROJECTS PROPOSAL PVT LTD";
    
    @Column(name = "about_us", columnDefinition = "TEXT")
    private String aboutUs;
    
    @Column(name = "about_system", columnDefinition = "TEXT")
    private String aboutSystem;
    
    @Column(name = "system_pricing", columnDefinition = "JSON")
    private String systemPricing; // JSON format for flexible pricing data
    
    @Column(name = "payment_terms", columnDefinition = "TEXT")
    private String paymentTerms;
    
    @Column(name = "defect_liability_period", columnDefinition = "TEXT")
    private String defectLiabilityPeriod;
    
    @Column(name = "bom_items", columnDefinition = "JSON")
    private String bomItems; // JSON format for Bill of Materials
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (version == null) {
            version = 1;
        }
        if (status == null || status.isEmpty()) {
            status = "Draft";
        }
        if (totalValue == null) {
            totalValue = BigDecimal.ZERO;
        }
        if (companyName == null || companyName.isEmpty()) {
            companyName = "SESOLA POWER PROJECTS PROPOSAL PVT LTD";
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}