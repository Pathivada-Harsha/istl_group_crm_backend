package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request wrapper for creating a new vendor
 * Used in POST /api/vendors endpoint
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateVendorRequest {
    
    // Optional - will be auto-generated if not provided
    private String vendorCode;
    
    // Project assignment (optional)
    private String projectId;
    private String groupName;
    private String subGroupName;
    
    // Required fields
    private String name;
    private String contactPerson;
    private String email;
    private String phone;
    
    // Optional vendor details
    private String website;
    private String gstNumber;
    private String address;
    private String city;
    private String state;
    private String pincode;
    
    // Vendor classification
    private Integer rating;  // 0-5
    private String status;   // Active, Inactive
    private String vendorType;  // Manufacturer, Distributor, Service Provider
    private String category;    // Solar Modules, Batteries, Inverters, Electrical, Structural
    
    // Additional info
    private String notes;
    private Long assignedTo;  // User ID to assign this vendor to
}