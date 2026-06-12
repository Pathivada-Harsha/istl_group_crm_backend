

package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response wrapper for vendor statistics/KPIs
 * Used by the stats endpoint to return dashboard metrics
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorStatsResponse {
    
    /**
     * Total number of vendors (active only)
     */
    private long totalVendors;
    
    /**
     * Number of vendors with status = 'Active'
     */
    private long activeVendors;
    
    /**
     * Number of vendors with status = 'Inactive'
     */
    private long inactiveVendors;
    
    /**
     * Average rating across all vendors (0.0 to 5.0)
     */
    private Double averageRating;
    
    /**
     * Sum of total_purchase_value from all vendors
     */
    private BigDecimal totalPurchaseValue;
    
    /**
     * Number of pending quotations (TODO: implement when quotations table exists)
     */
    private int pendingQuotations;
}
