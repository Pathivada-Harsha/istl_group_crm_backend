package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request wrapper for vendor filtering
 * Used to pass filter parameters from controller to service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorFilterRequest {
    
    // Hierarchical filters
    private String groupName;
    private String subGroupName;
    private String projectId;
    
    // Search filter
    private String searchTerm;  // Searches in name, email, contact person, phone
    
    // Classification filters
    private String category;
    private String vendorType;
    private Integer rating;      // Minimum rating
    private String status;
    
    // Pagination parameters
    @Builder.Default
    private int page = 0;
    
    @Builder.Default
    private int size = 10;
    
    // Sorting parameters
    @Builder.Default
    private String sortBy = "createdAt";
    
    @Builder.Default
    private String sortDirection = "DESC";  // ASC or DESC
}