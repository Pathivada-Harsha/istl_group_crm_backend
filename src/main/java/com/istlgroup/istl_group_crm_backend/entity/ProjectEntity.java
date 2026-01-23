package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ProjectEntity - Enhanced with Dashboard Statistics Columns
 * Maps to the 'projects' table with all dashboard tracking columns
 */
@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "project_unique_id", unique = true, nullable = false, length = 50)
    private String projectUniqueId;
    
    @Column(name = "Lead_id")
    private String leadId;
    
    @Column(name = "customer_id", length = 225)
    private String customerId;
    
    @Column(name = "group_id", length = 50)
    private String groupId;
    
    @Column(name = "sub_group_name")
    private String subGroupName;
    
    @Column(name = "sub_group_id", nullable = false)
    private Long subGroupId;
    
    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "location", length = 200)
    private String location;
    
    @Column(name = "start_date")
    private LocalDate startDate;
    
    @Column(name = "end_date")
    private LocalDate endDate;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ProjectStatus status;
    
    @Column(name = "budget", precision = 15, scale = 2)
    private BigDecimal budget;
    
    @Column(name = "is_active")
    private Boolean isActive = true;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "assigned_to")
    private Long assignedTo;
    
    // ====================================================================
    // DASHBOARD STATISTICS COLUMNS (Auto-updated by triggers)
    // ====================================================================
    
    // Purchase Orders Tracking
    @Column(name = "total_po_value", precision = 15, scale = 2)
    private BigDecimal totalPoValue = BigDecimal.ZERO;
    
    @Column(name = "total_po_count")
    private Integer totalPoCount = 0;
    
    @Column(name = "delivered_po_value", precision = 15, scale = 2)
    private BigDecimal deliveredPoValue = BigDecimal.ZERO;
    
    @Column(name = "delivered_po_count")
    private Integer deliveredPoCount = 0;
    
    @Column(name = "pending_po_value", precision = 15, scale = 2)
    private BigDecimal pendingPoValue = BigDecimal.ZERO;
    
    @Column(name = "cancelled_po_value", precision = 15, scale = 2)
    private BigDecimal cancelledPoValue = BigDecimal.ZERO;
    
    // Quotations Tracking
    @Column(name = "total_quotation_value", precision = 15, scale = 2)
    private BigDecimal totalQuotationValue = BigDecimal.ZERO;
    
    @Column(name = "total_quotation_count")
    private Integer totalQuotationCount = 0;
    
    @Column(name = "approved_quotation_value", precision = 15, scale = 2)
    private BigDecimal approvedQuotationValue = BigDecimal.ZERO;
    
    @Column(name = "approved_quotation_count")
    private Integer approvedQuotationCount = 0;
    
    // Bills Tracking (Vendor Payments - ACTUAL SPEND)
    @Column(name = "total_bill_value", precision = 15, scale = 2)
    private BigDecimal totalBillValue = BigDecimal.ZERO;
    
    @Column(name = "total_bill_count")
    private Integer totalBillCount = 0;
    
    @Column(name = "paid_bill_value", precision = 15, scale = 2)
    private BigDecimal paidBillValue = BigDecimal.ZERO;
    
    @Column(name = "paid_bill_count")
    private Integer paidBillCount = 0;
    
    @Column(name = "pending_payment_value", precision = 15, scale = 2)
    private BigDecimal pendingPaymentValue = BigDecimal.ZERO;
    
    // ====================================================================
    // INVOICES TRACKING (Client Billing - NEW)
    // ====================================================================
    
    @Column(name = "total_invoice_value", precision = 15, scale = 2)
    private BigDecimal totalInvoiceValue = BigDecimal.ZERO;
    
    @Column(name = "total_invoice_count")
    private Integer totalInvoiceCount = 0;
    
    @Column(name = "paid_invoice_value", precision = 15, scale = 2)
    private BigDecimal paidInvoiceValue = BigDecimal.ZERO;
    
    @Column(name = "paid_invoice_count")
    private Integer paidInvoiceCount = 0;
    
    @Column(name = "pending_invoice_value", precision = 15, scale = 2)
    private BigDecimal pendingInvoiceValue = BigDecimal.ZERO;
    
    // ====================================================================
    // VENDORS & CUSTOMERS
    // ====================================================================
    
    @Column(name = "active_vendor_count")
    private Integer activeVendorCount = 0;
    
    @Column(name = "total_vendor_spend", precision = 15, scale = 2)
    private BigDecimal totalVendorSpend = BigDecimal.ZERO;
    
    @Column(name = "active_customer_count")
    private Integer activeCustomerCount = 0;
    
    // ====================================================================
    // FINANCIAL METRICS
    // ====================================================================
    
    @Column(name = "budget_utilized", precision = 15, scale = 2)
    private BigDecimal budgetUtilized = BigDecimal.ZERO;
    
    @Column(name = "budget_utilization_percent", precision = 5, scale = 2)
    private BigDecimal budgetUtilizationPercent = BigDecimal.ZERO;
    
    @Column(name = "projected_profit", precision = 15, scale = 2)
    private BigDecimal projectedProfit = BigDecimal.ZERO;
    
    @Column(name = "profit_margin_percent", precision = 5, scale = 2)
    private BigDecimal profitMarginPercent = BigDecimal.ZERO;
    
    // ====================================================================
    // TIMESTAMPS
    // ====================================================================
    
    @Column(name = "last_procurement_update")
    private LocalDateTime lastProcurementUpdate;
    
    @Column(name = "stats_calculated_at")
    private LocalDateTime statsCalculatedAt;
    
    // ====================================================================
    // TIMELINE COLUMNS
    // ====================================================================
    
    @Column(name = "timeline_milestones", columnDefinition = "JSON")
    private String timelineMilestones;
    
    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;
    
    @Column(name = "actual_end_date")
    private LocalDate actualEndDate;
    
    @Column(name = "progress_percentage", precision = 5, scale = 2)
    private BigDecimal progressPercentage = BigDecimal.ZERO;
    
    // ====================================================================
    // ENUMS
    // ====================================================================
    
    public enum ProjectStatus {
        PLANNING,
        IN_PROGRESS,
        COMPLETED,
        ON_HOLD,
        CANCELLED
    }
    
    // ====================================================================
    // LIFECYCLE HOOKS
    // ====================================================================
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        
        // Initialize dashboard columns if null
        if (totalPoValue == null) totalPoValue = BigDecimal.ZERO;
        if (totalPoCount == null) totalPoCount = 0;
        if (deliveredPoValue == null) deliveredPoValue = BigDecimal.ZERO;
        if (deliveredPoCount == null) deliveredPoCount = 0;
        if (pendingPoValue == null) pendingPoValue = BigDecimal.ZERO;
        if (cancelledPoValue == null) cancelledPoValue = BigDecimal.ZERO;
        
        if (totalQuotationValue == null) totalQuotationValue = BigDecimal.ZERO;
        if (totalQuotationCount == null) totalQuotationCount = 0;
        if (approvedQuotationValue == null) approvedQuotationValue = BigDecimal.ZERO;
        if (approvedQuotationCount == null) approvedQuotationCount = 0;
        
        if (totalBillValue == null) totalBillValue = BigDecimal.ZERO;
        if (totalBillCount == null) totalBillCount = 0;
        if (paidBillValue == null) paidBillValue = BigDecimal.ZERO;
        if (paidBillCount == null) paidBillCount = 0;
        if (pendingPaymentValue == null) pendingPaymentValue = BigDecimal.ZERO;
        
        // Initialize INVOICE columns (NEW)
        if (totalInvoiceValue == null) totalInvoiceValue = BigDecimal.ZERO;
        if (totalInvoiceCount == null) totalInvoiceCount = 0;
        if (paidInvoiceValue == null) paidInvoiceValue = BigDecimal.ZERO;
        if (paidInvoiceCount == null) paidInvoiceCount = 0;
        if (pendingInvoiceValue == null) pendingInvoiceValue = BigDecimal.ZERO;
        
        if (activeVendorCount == null) activeVendorCount = 0;
        if (totalVendorSpend == null) totalVendorSpend = BigDecimal.ZERO;
        if (activeCustomerCount == null) activeCustomerCount = 0;
        
        if (budgetUtilized == null) budgetUtilized = BigDecimal.ZERO;
        if (budgetUtilizationPercent == null) budgetUtilizationPercent = BigDecimal.ZERO;
        if (projectedProfit == null) projectedProfit = BigDecimal.ZERO;
        if (profitMarginPercent == null) profitMarginPercent = BigDecimal.ZERO;
        if (progressPercentage == null) progressPercentage = BigDecimal.ZERO;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}