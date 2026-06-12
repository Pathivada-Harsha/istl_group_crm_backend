package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response wrapper for paginated vendor list
 * Used by GET /api/vendors endpoint to return paginated results
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorPageResponse {
    
    /**
     * List of vendors for the current page
     */
    private List<VendorResponse> vendors;
    
    /**
     * Total number of vendors matching the filter criteria
     */
    private long totalElements;
    
    /**
     * Total number of pages available
     */
    private int totalPages;
    
    /**
     * Current page number (0-indexed)
     */
    private int currentPage;
    
    /**
     * Number of items per page
     */
    private int pageSize;
    
    /**
     * Whether there is a next page available
     */
    private boolean hasNext;
    
    /**
     * Whether there is a previous page available
     */
    private boolean hasPrevious;
}