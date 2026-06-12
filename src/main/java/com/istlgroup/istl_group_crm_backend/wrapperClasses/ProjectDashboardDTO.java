package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDashboardDTO {
    
    // Project basic info
    private String projectId;
    private String projectName;
    private String uniqueId;
    private String location;
    private String status;
    private String groupId;
    private String subGroupName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String manager;
    private BigDecimal budget;
    private BigDecimal progressPercentage;  // Manual override or auto-calculated
    
    // Data sections
    private FinancialData financialData;
    private ProcurementData procurementData;
    private List<ActivityDTO> recentActivities;
    private List<VendorSummaryDTO> topVendors;
    private List<SpendingTrendDTO> spendingTrend;
    private List<TimelineMilestoneDTO> projectTimeline;
    
    // PAYMENT ANALYTICS (NEW) ← ADD THESE
    private List<ChartDataDTO> paymentMethodDistribution;
    private List<PaymentTrendDTO> paymentTimeline;
    private ExpenseDashboardBlock expenseData;
    // Warehouse issuance summary
    private WarehouseIssuanceData warehouseIssuanceData;
    // Metadata
    private LocalDateTime lastUpdate;
    private LocalDateTime statsCalculatedAt;
    
    // ========================================================================
    // NESTED CLASSES
    // ========================================================================
    
   // ============================================================================
// UPDATE YOUR FinancialData CLASS IN ProjectDashboardDTO.java
// ============================================================================

// ============================================================================
// UPDATE YOUR FinancialData CLASS IN ProjectDashboardDTO.java
// ============================================================================

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FinancialData {
	    private BigDecimal totalProjectValue;
	    private BigDecimal totalSpent;           // Amount paid to vendors
	    private BigDecimal totalCommitted;
	    private BigDecimal remaining;
	    
	    // Client billing
	    private BigDecimal amountToBeReceived;
	    private BigDecimal amountReceived;       // Total cash received = advances + invoice payments (no double-count)
	    private BigDecimal advanceAmount;         // Cash received as advances (ADVANCE type receipts)
	    private BigDecimal invoicePaymentAmount;  // Cash received as direct invoice payments (INVOICE_PAYMENT type)
	    private BigDecimal pendingReceipts;
	    private Double billingPercentage;
	    
	    // Vendor payments
	    private BigDecimal totalPayable;         // Total procurement cost (all bills)
	    private BigDecimal amountPaid;           // Paid to vendors (same as totalSpent)
	    private BigDecimal pendingPayments;
	    private Double paymentPercentage;
	    
	    // Profit
	    private BigDecimal projectedProfit;
	    private Double profitMargin;
	    private Boolean isCompleted;             // ← ADD THIS NEW FIELD
	    private BigDecimal totalEmployeeExpenses; // Total employee cost + expenses for this project

	    // GST
	    private BigDecimal invoiceGSTCollected;        // GST collected from client (invoiced)
	    private BigDecimal procurementGSTPaid;           // GST paid to vendors (procurement bills)
	    private BigDecimal netGST;                       // invoiceGSTCollected - procurementGSTPaid
	    private BigDecimal amountToBeReceivedExclGST;    // Total invoiced excl. GST (basis for profit)
	    private BigDecimal totalPayableExclGST;           // Total procurement excl. GST
	    
	    // Budget
	    private BigDecimal budgetUtilized;
	    private Double budgetUtilizationPercent;
	    
	    // Cash flow
	    private BigDecimal cashInHand;
	    private BigDecimal cashDeficit;
	    private Double burnRate;
	}
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcurementData {
        // PO metrics
        private Integer totalPOs;
        private BigDecimal totalPOValue;
        private BigDecimal avgPOValue;
        
        // PO status breakdown
        private Integer draftPOs;
        private Integer approvedPOs;
        private Integer orderedPOs;
        private Integer inTransitPOs;
        private Integer deliveredPOs;
        private Integer cancelledPOs;
        
        // Delivery metrics
        private Integer totalItemsOrdered;
        private Integer totalItemsDelivered;
        private Integer pendingDeliveries;
        private Double deliveryRate;
        
        // Quotation metrics
        private Integer totalQuotations;
        private BigDecimal totalQuotationValue;
        private Integer newQuotations;
        private Integer underReviewQuotations;
        private Integer approvedQuotations;
        private Integer rejectedQuotations;
        
        // Vendor metrics
        private Integer totalVendors;
        private Integer activeVendors;
        private BigDecimal totalVendorSpend;
        private Double avgVendorRating;
        
        // Payment metrics
        private Integer pendingPayments;
        private Integer partialPayments;
        private Integer paidPOs;
        
        // Chart data
        private List<ChartDataDTO> posByStatus;
        private List<ChartDataDTO> quotationsByStatus;
        private List<ChartDataDTO> paymentDistribution;
        private List<ChartDataDTO> categoryDistribution;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityDTO {
        private String type;
        private String action;
        private String status;
        private BigDecimal amount;
        private LocalDateTime date;
        private String color;
        private String reference;  // ← Make sure this exists
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VendorSummaryDTO {
        private Long id;
        private String name;
        private Integer totalOrders;
        private Double rating;
        private BigDecimal totalPurchaseValue;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpendingTrendDTO {
        private String month;
        private BigDecimal spending;
        private Integer orders;
        private BigDecimal avgOrderValue;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineMilestoneDTO {
        private LocalDate date;
        private String title;
        private String description;
        private String type;
        private String status;
        private BigDecimal amount;    // ← Make sure this exists
        private String reference;     // ← Make sure this exists
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChartDataDTO {
        private String name;
        private Object value;
        private Integer count;  // ← ADD THIS
    }
    
    // NEW CLASS ← ADD THIS
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentTrendDTO {
        private String month;
        private BigDecimal totalAmount;
        private Integer paymentCount;
    }

    /** Summary of items received FROM site INTO warehouse (INWARD from site txns) */
    private SiteReturnData siteReturnData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteReturnData {
        private Integer totalReturns;
        private BigDecimal totalQtyReturned;
        private List<SiteReturnLine> lines;

        @Data @Builder @NoArgsConstructor @AllArgsConstructor
        public static class SiteReturnLine {
            private String itemCode;
            private String itemName;
            private String unit;
            private BigDecimal qty;
            private String warehouseName;
            private String txnNo;
            private String txnDate;
        }
    }

    // ── Warehouse Issuance Summary (items sent from warehouse to project) ──────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WarehouseIssuanceData {
        /** Total distinct items ever issued to this project from warehouse */
        private Integer totalItemsIssued;
        /** Total quantity issued (sum across all items) */
        private BigDecimal totalQtyIssued;
        /** Total value of warehouse issuances (qty × unitCost) */
        private BigDecimal totalIssuanceValue;
        /** Number of INWARD transactions from site to warehouse under this project */
        private Integer totalItemsReturned;
        /** Value of items returned from site to warehouse */
        private BigDecimal totalReturnValue;
        /** Per-item breakdown */
        private List<IssuanceLine> issuanceLines;
        /** Auto-generated warehouse bills count */
        private Integer warehouseBillCount;

        @Data @Builder @NoArgsConstructor @AllArgsConstructor
        public static class IssuanceLine {
            private String itemCode;
            private String itemName;
            private String unit;
            private BigDecimal qtyIssued;
            private BigDecimal unitCost;
            private BigDecimal lineValue;
            private String warehouseName;
            private String txnDate;
            private String txnNo;
        }
    }
}