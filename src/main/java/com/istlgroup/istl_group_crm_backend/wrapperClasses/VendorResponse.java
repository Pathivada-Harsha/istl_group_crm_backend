package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response wrapper for vendor data
 * Used in all GET endpoints to return vendor information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorResponse {
    
    // Primary identification
    private Long id;
    private String vendorCode;
    
    // Project assignment
    private String projectId;
    private String groupName;
    private String subGroupName;
    
    // Basic information
    private String name;
    private String contactPerson;
    private String email;
    private String phone;
    private String website;
    private String gstNumber;
    
    // Address information
    private String address;
    private String city;
    private String state;
    private String pincode;
    
    // Classification
    private Integer rating;
    private String status;
    private String vendorType;
    private String category;
    
    // Financial information
    private BigDecimal lastPurchaseAmount;
    private BigDecimal totalPurchaseValue;
    
    // Additional info
    private String notes;
    
    // Audit information
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private String createdByName;  // Populated from Users table
    private Long assignedTo;
    private String assignedToName;  // Populated from Users table
}