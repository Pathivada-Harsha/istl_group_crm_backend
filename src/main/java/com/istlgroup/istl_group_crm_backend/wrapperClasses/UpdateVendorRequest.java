package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request wrapper for updating an existing vendor
 * Used in PUT /api/vendors/{id} endpoint
 * All fields are optional - only provided fields will be updated
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateVendorRequest {
    
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
    
    // Project assignment
    private String groupName;
    private String subGroupName;
    private String projectId;
    
    // Additional info
    private String notes;
    private Long assignedTo;
}