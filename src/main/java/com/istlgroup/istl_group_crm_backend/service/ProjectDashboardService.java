package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO.*;
import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDashboardService {
    
    private final ProjectRepository projectRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final QuotationRepository quotationRepository;
    private final VendorRepository vendorRepository;
    private final BillRepository billRepository;
    private final PaymentHistoryRepository paymentHistoryRepository; // FIXED TYPO
    private final InvoiceRepository invoiceRepository;
    
    @Transactional(readOnly = true)
    public ProjectDashboardDTO getDashboardData(String projectUniqueId) {
        log.info("Fetching dashboard data for project: {}", projectUniqueId);
        
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));
        
        ProjectDashboardDTO dashboard = ProjectDashboardDTO.builder()
            .projectId(project.getProjectUniqueId())
            .projectName(project.getProjectName())
            .uniqueId(project.getProjectUniqueId())
            .location(project.getLocation())
            .status(project.getStatus() != null ? project.getStatus().name() : "UNKNOWN")
            .startDate(project.getStartDate())
            .endDate(project.getEndDate())
            .manager(getProjectManager(project))
            .budget(project.getBudget())
            .financialData(buildFinancialData(project))
            .procurementData(buildProcurementData(project, projectUniqueId))
            .recentActivities(getRecentActivities(projectUniqueId))
            .topVendors(getTopVendors(projectUniqueId))
            .spendingTrend(getSpendingTrend(projectUniqueId))
            .projectTimeline(buildProjectTimeline(project, projectUniqueId)) // FIXED: Pass both parameters
            .paymentMethodDistribution(getPaymentMethodDistribution(projectUniqueId)) // NEW
            .paymentTimeline(getPaymentTimeline(projectUniqueId)) // NEW
            .lastUpdate(project.getUpdatedAt())
            .statsCalculatedAt(project.getStatsCalculatedAt())
            .build();
        
        log.info("Dashboard data built successfully for project: {}", projectUniqueId);
        return dashboard;
    }
    
 
	/**
 * Build financial data from project stats
 * CORRECTED FORMULA:
 * - Projected Profit = Budget - Amount Spent (not Total Bills)
 * - Amount Spent = Amount Paid to Vendors
 */
private FinancialData buildFinancialData(ProjectEntity project) {
    // TOTAL PROJECT VALUE = BUDGET
    BigDecimal totalProjectValue = project.getBudget() != null ? project.getBudget() : BigDecimal.ZERO;
    
    // PROJECT STATUS
    boolean isCompleted = project.getStatus() == ProjectEntity.ProjectStatus.COMPLETED;
    
    // CLIENT BILLING (from invoices)
    BigDecimal totalInvoiceValue = project.getTotalInvoiceValue() != null ? project.getTotalInvoiceValue() : BigDecimal.ZERO;
    BigDecimal paidInvoiceValue = project.getPaidInvoiceValue() != null ? project.getPaidInvoiceValue() : BigDecimal.ZERO;
    BigDecimal pendingInvoiceValue = project.getPendingInvoiceValue() != null ? project.getPendingInvoiceValue() : BigDecimal.ZERO;
    
    // VENDOR PAYMENTS (from bills)
    BigDecimal totalBillValue = project.getTotalBillValue() != null ? project.getTotalBillValue() : BigDecimal.ZERO;
    BigDecimal paidBillValue = project.getPaidBillValue() != null ? project.getPaidBillValue() : BigDecimal.ZERO;
    BigDecimal pendingPaymentValue = project.getPendingPaymentValue() != null ? project.getPendingPaymentValue() : BigDecimal.ZERO;
    
    // AMOUNT SPENT = AMOUNT PAID TO VENDORS (actual money out)
    BigDecimal amountSpent = paidBillValue;
    
    // TOTAL PROCUREMENT COST = ALL BILLS (including unpaid)
    BigDecimal totalProcurementCost = totalBillValue;
    
    // BUDGET UTILIZATION PERCENTAGE (based on amount spent)
    Double budgetUtilizationPercent = totalProjectValue.compareTo(BigDecimal.ZERO) > 0
        ? amountSpent.multiply(new BigDecimal("100"))
            .divide(totalProjectValue, 2, RoundingMode.HALF_UP)
            .doubleValue()
        : 0.0;
    
    // PROFIT CALCULATION
    BigDecimal projectedProfit;
    Double profitMargin;
    
    if (isCompleted) {
        // PROJECT COMPLETED: Show actual profit
        // Actual Profit = Total received from client - Total paid to vendors
        projectedProfit = paidInvoiceValue.subtract(paidBillValue);
        profitMargin = paidInvoiceValue.compareTo(BigDecimal.ZERO) > 0
            ? projectedProfit.multiply(new BigDecimal("100"))
                .divide(paidInvoiceValue, 2, RoundingMode.HALF_UP)
                .doubleValue()
            : 0.0;
    } else {
        // PROJECT IN PROGRESS: Show projected profit
        // CORRECTED: Projected Profit = Budget - Amount Spent
        projectedProfit = totalProjectValue.subtract(amountSpent);
        profitMargin = totalProjectValue.compareTo(BigDecimal.ZERO) > 0
            ? projectedProfit.multiply(new BigDecimal("100"))
                .divide(totalProjectValue, 2, RoundingMode.HALF_UP)
                .doubleValue()
            : 0.0;
    }
    
    // CASH FLOW CALCULATIONS
    BigDecimal cashInHand = paidInvoiceValue.subtract(paidBillValue);
    BigDecimal cashDeficit = BigDecimal.ZERO;
    if (cashInHand.compareTo(BigDecimal.ZERO) < 0) {
        cashDeficit = cashInHand.abs();
        cashInHand = BigDecimal.ZERO;
    }
    
    // PERCENTAGES
    Double billingPercentage = totalProjectValue.compareTo(BigDecimal.ZERO) > 0
        ? paidInvoiceValue.multiply(new BigDecimal("100"))
            .divide(totalProjectValue, 2, RoundingMode.HALF_UP)
            .doubleValue()
        : 0.0;
    
    Double paymentPercentage = totalBillValue.compareTo(BigDecimal.ZERO) > 0
        ? paidBillValue.multiply(new BigDecimal("100"))
            .divide(totalBillValue, 2, RoundingMode.HALF_UP)
            .doubleValue()
        : 0.0;
    
    // MANUALLY UPDATE PROJECT STATS
    try {
        project.setBudgetUtilized(amountSpent);
        project.setBudgetUtilizationPercent(new BigDecimal(budgetUtilizationPercent));
        project.setProjectedProfit(projectedProfit);
        project.setProfitMarginPercent(new BigDecimal(profitMargin));
        project.setStatsCalculatedAt(LocalDateTime.now());
        projectRepository.save(project);
    } catch (Exception e) {
        log.warn("Could not update project stats", e);
    }
    
    return FinancialData.builder()
        .totalProjectValue(totalProjectValue)
        .totalSpent(amountSpent)  // Paid to vendors
        .totalCommitted(project.getTotalPoValue())
        .remaining(totalProjectValue.subtract(amountSpent))
        
        // CLIENT BILLING
        .amountToBeReceived(totalProjectValue)
        .amountReceived(paidInvoiceValue)
        .pendingReceipts(totalProjectValue.subtract(paidInvoiceValue))
        .billingPercentage(billingPercentage)
        
        // VENDOR PAYMENTS
        .totalPayable(totalProcurementCost)     // All bills
        .amountPaid(paidBillValue)              // Same as amountSpent
        .pendingPayments(pendingPaymentValue)
        .paymentPercentage(paymentPercentage)
        
        // PROFIT
        .projectedProfit(projectedProfit)
        .profitMargin(profitMargin)
        .isCompleted(isCompleted)
        
        // BUDGET
        .budgetUtilized(amountSpent)            // Based on amount spent
        .budgetUtilizationPercent(budgetUtilizationPercent)
        
        // CASH FLOW
        .cashInHand(cashInHand)
        .cashDeficit(cashDeficit)
        .burnRate(totalProjectValue.compareTo(BigDecimal.ZERO) > 0
            ? amountSpent.divide(totalProjectValue, 2, RoundingMode.HALF_UP).doubleValue() 
            : 0.0)
        .build();
}
    
    private ProcurementData buildProcurementData(ProjectEntity project, String projectUniqueId) {
        List<Object[]> poStatusCounts = purchaseOrderRepository.countByProjectIdAndGroupByStatus(projectUniqueId);
        Map<String, Long> poStatusMap = poStatusCounts.stream()
            .collect(Collectors.toMap(
                arr -> (String) arr[0],
                arr -> ((Number) arr[1]).longValue()
            ));
        
        List<Object[]> quotationStatusCounts = quotationRepository.countByProjectIdAndGroupByStatus(projectUniqueId);
        Map<String, Long> quotationStatusMap = quotationStatusCounts.stream()
            .collect(Collectors.toMap(
                arr -> (String) arr[0],
                arr -> ((Number) arr[1]).longValue()
            ));
        
        List<Object[]> categorySpending = purchaseOrderRepository.sumTotalValueByProjectIdGroupByCategory(projectUniqueId);
        
        Integer totalItemsOrdered = purchaseOrderRepository.sumTotalItemsOrderedByProjectId(projectUniqueId);
        Integer totalItemsDelivered = purchaseOrderRepository.sumTotalItemsDeliveredByProjectId(projectUniqueId);
        Double deliveryRate = totalItemsOrdered != null && totalItemsOrdered > 0
            ? (totalItemsDelivered != null ? totalItemsDelivered.doubleValue() : 0.0) / totalItemsOrdered * 100
            : 0.0;
        
        BigDecimal avgPoValue = project.getTotalPoCount() != null && project.getTotalPoCount() > 0
            ? project.getTotalPoValue().divide(new BigDecimal(project.getTotalPoCount()), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        Double avgVendorRating = vendorRepository.getAverageRatingByProjectId(projectUniqueId);
        
        return ProcurementData.builder()
            .totalPOs(project.getTotalPoCount())
            .totalPOValue(project.getTotalPoValue())
            .avgPOValue(avgPoValue)
            .draftPOs(poStatusMap.getOrDefault("Draft", 0L).intValue())
            .approvedPOs(poStatusMap.getOrDefault("Approved", 0L).intValue())
            .orderedPOs(poStatusMap.getOrDefault("Ordered", 0L).intValue())
            .inTransitPOs(poStatusMap.getOrDefault("In-Transit", 0L).intValue())
            .deliveredPOs(project.getDeliveredPoCount())
            .cancelledPOs(poStatusMap.getOrDefault("Cancelled", 0L).intValue())
            .totalItemsOrdered(totalItemsOrdered != null ? totalItemsOrdered : 0)
            .totalItemsDelivered(totalItemsDelivered != null ? totalItemsDelivered : 0)
            .pendingDeliveries(poStatusMap.getOrDefault("Pending", 0L).intValue())
            .deliveryRate(deliveryRate)
            .totalQuotations(project.getTotalQuotationCount())
            .totalQuotationValue(project.getTotalQuotationValue())
            .newQuotations(quotationStatusMap.getOrDefault("New", 0L).intValue())
            .underReviewQuotations(quotationStatusMap.getOrDefault("Under Review", 0L).intValue())
            .approvedQuotations(project.getApprovedQuotationCount())
            .rejectedQuotations(quotationStatusMap.getOrDefault("Rejected", 0L).intValue())
            .totalVendors(vendorRepository.countByProjectId(projectUniqueId).intValue())
            .activeVendors(project.getActiveVendorCount())
            .totalVendorSpend(project.getTotalVendorSpend())
            .avgVendorRating(avgVendorRating != null ? avgVendorRating : 0.0)
            .pendingPayments(poStatusMap.getOrDefault("Pending", 0L).intValue())
            .partialPayments(poStatusMap.getOrDefault("Partial", 0L).intValue())
            .paidPOs(poStatusMap.getOrDefault("Paid", 0L).intValue())
            .posByStatus(buildChartData(poStatusMap))
            .quotationsByStatus(buildChartData(quotationStatusMap))
            .paymentDistribution(Collections.emptyList())
            .categoryDistribution(buildCategoryChartData(categorySpending))
            .build();
    }
    
    private List<ActivityDTO> getRecentActivities(String projectUniqueId) {
        List<ActivityDTO> activities = new ArrayList<>();
        
        purchaseOrderRepository.findTop5ByProjectIdOrderByCreatedAtDesc(projectUniqueId)
            .forEach(po -> activities.add(ActivityDTO.builder()
                .type("Purchase Order")
                .action("PO " + po.getPoNo() + " " + po.getStatus().toLowerCase())
                .status(po.getStatus())
                .amount(po.getTotalValue())
                .date(po.getCreatedAt())
                .color(getStatusColor(po.getStatus()))
                .build()));
        
        quotationRepository.findTop5ByProjectIdOrderByUploadedAtDesc(projectUniqueId)
            .forEach(q -> activities.add(ActivityDTO.builder()
                .type("Quotation")
                .action("Quotation " + q.getQuoteNo() + " " + q.getStatus().toLowerCase())
                .status(q.getStatus())
                .amount(q.getTotalValue())
                .date(q.getUploadedAt())
                .color(getStatusColor(q.getStatus()))
                .build()));
        
        // Add recent payments
        List<ActivityDTO> payments = getRecentPayments(projectUniqueId);
        activities.addAll(payments);
        
        activities.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        
        return activities.stream().limit(10).collect(Collectors.toList());
    }
    
    private List<VendorSummaryDTO> getTopVendors(String projectUniqueId) {
        return vendorRepository.findTop5ByProjectIdOrderByTotalPurchaseValueDesc(projectUniqueId)
            .stream()
            .map(vendor -> VendorSummaryDTO.builder()
                .id(vendor.getId())
                .name(vendor.getName())
                .totalOrders(purchaseOrderRepository.countByVendorId(vendor.getId()).intValue())
                .rating(vendor.getRating() != null ? vendor.getRating().doubleValue() : 0.0)
                .totalPurchaseValue(vendor.getLastPurchaseAmount() != null ? vendor.getLastPurchaseAmount() : BigDecimal.ZERO)
                .build())
            .collect(Collectors.toList());
    }
    
    private List<SpendingTrendDTO> getSpendingTrend(String projectUniqueId) {
        List<SpendingTrendDTO> trend = new ArrayList<>();
        YearMonth currentMonth = YearMonth.now();
        
        for (int i = 5; i >= 0; i--) {
            YearMonth month = currentMonth.minusMonths(i);
            LocalDate startOfMonth = month.atDay(1);
            LocalDate endOfMonth = month.atEndOfMonth();
            
            BigDecimal monthlySpending = purchaseOrderRepository
                .sumTotalValueByProjectIdAndDateRange(projectUniqueId, startOfMonth.atStartOfDay(), endOfMonth.atTime(23, 59, 59));
            
            Long monthlyOrders = purchaseOrderRepository
                .countByProjectIdAndDateRange(projectUniqueId, startOfMonth.atStartOfDay(), endOfMonth.atTime(23, 59, 59));
            
            BigDecimal avgOrderValue = monthlyOrders > 0 && monthlySpending != null
                ? monthlySpending.divide(new BigDecimal(monthlyOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            
            trend.add(SpendingTrendDTO.builder()
                .month(month.getMonth().toString().substring(0, 3) + " " + String.valueOf(month.getYear()).substring(2))
                .spending(monthlySpending != null ? monthlySpending : BigDecimal.ZERO)
                .orders(monthlyOrders.intValue())
                .avgOrderValue(avgOrderValue)
                .build());
        }
        
        return trend;
    }
    
    /**
     * Build comprehensive project timeline with procurement milestones
     */
    // ============================================================================
// FIX FOR DATE CONVERSION ERROR IN buildProjectTimeline
// ============================================================================

// Replace the buildProjectTimeline method in ProjectDashboardService.java with this:

private List<TimelineMilestoneDTO> buildProjectTimeline(ProjectEntity project, String projectUniqueId) {
    List<TimelineMilestoneDTO> timeline = new ArrayList<>();
    
    if (project.getStartDate() != null) {
        timeline.add(TimelineMilestoneDTO.builder()
            .date(project.getStartDate())
            .title("Project Kickoff")
            .description(project.getProjectName() + " started")
            .type("milestone")
            .status("completed")
            .build());
    }
    
    // Add delivered POs
    try {
        List<PurchaseOrderEntity> deliveredPOs = purchaseOrderRepository
            .findByProjectIdAndStatus(projectUniqueId, "Delivered");
        
        for (PurchaseOrderEntity po : deliveredPOs) {
            // FIXED: Convert LocalDateTime to LocalDate
            LocalDate poDate = null;
            if (po.getExpectedDelivery() != null) {
                poDate = po.getExpectedDelivery().toLocalDate();  // ← CONVERT HERE
            } else if (po.getOrderDate() != null) {
                poDate = po.getOrderDate().toLocalDate();         // ← CONVERT HERE
            }
            
            timeline.add(TimelineMilestoneDTO.builder()
                .date(poDate)
                .title("PO Delivered")
                .description("PO " + po.getPoNo() + " delivered")
                .type("po_delivered")
                .status("completed")
                .amount(po.getTotalValue())
                .reference(po.getPoNo())
                .build());
        }
    } catch (Exception e) {
        log.error("Error fetching delivered POs for timeline", e);
    }
    
    // Add bills
    try {
        List<BillEntity> bills = billRepository
            .findByProjectIdAndStatusNot(projectUniqueId, "Pending");
        
        for (BillEntity bill : bills) {
            String billStatus = "Paid".equals(bill.getStatus()) ? "completed" : "in-progress";
            timeline.add(TimelineMilestoneDTO.builder()
                .date(bill.getBillDate())  // Already LocalDate ✓
                .title("Paid".equals(bill.getStatus()) ? "Bill Paid" : "Bill Received")
                .description("Bill " + bill.getBillNo() + " - " + bill.getStatus())
                .type("Paid".equals(bill.getStatus()) ? "bill_paid" : "bill_received")
                .status(billStatus)
                .amount(bill.getTotalAmount())
                .reference(bill.getBillNo())
                .build());
        }
    } catch (Exception e) {
        log.error("Error fetching bills for timeline", e);
    }
    
    // Add invoices
    try {
        List<InvoiceEntity> invoices = invoiceRepository
            .findByProjectIdAndDeletedAtIsNull(projectUniqueId);
        
        for (InvoiceEntity invoice : invoices) {
            String invoiceStatus = "Paid".equals(invoice.getStatus()) ? "completed" : "in-progress";
            timeline.add(TimelineMilestoneDTO.builder()
                .date(invoice.getInvoiceDate())  // Already LocalDate ✓
                .title("Paid".equals(invoice.getStatus()) ? "Invoice Paid" : "Invoice Raised")
                .description("Invoice " + invoice.getInvoiceNo() + " - " + invoice.getStatus())
                .type("Paid".equals(invoice.getStatus()) ? "invoice_paid" : "invoice_raised")
                .status(invoiceStatus)
                .amount(invoice.getTotalAmount())
                .reference(invoice.getInvoiceNo())
                .build());
        }
    } catch (Exception e) {
        log.error("Error fetching invoices for timeline", e);
    }
    
    if (project.getEndDate() != null) {
        LocalDate now = LocalDate.now();
        LocalDate end = project.getEndDate();
        String status = now.isAfter(end) ? "overdue" : 
                       now.isAfter(end.minusDays(30)) ? "in-progress" : "upcoming";
        
        timeline.add(TimelineMilestoneDTO.builder()
            .date(project.getEndDate())
            .title("Project Completion")
            .description("Target completion date")
            .type("milestone")
            .status(status)
            .build());
    }
    
    // Sort by date
    timeline.sort((a, b) -> {
        if (a.getDate() == null) return 1;
        if (b.getDate() == null) return -1;
        return a.getDate().compareTo(b.getDate());
    });
    
    return timeline;
}
    
    /**
     * Get payment method distribution from payment_history
     */
    private List<ChartDataDTO> getPaymentMethodDistribution(String projectUniqueId) {
        try {
            List<Object[]> results = paymentHistoryRepository
                .getPaymentMethodDistributionByProject(projectUniqueId);
            
            return results.stream()
                .map(arr -> ChartDataDTO.builder()
                    .name((String) arr[0])
                    .value((BigDecimal) arr[1])
                    .count(((Number) arr[2]).intValue())
                    .build())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching payment method distribution", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Get payment timeline (monthly payments received)
     */
    private List<PaymentTrendDTO> getPaymentTimeline(String projectUniqueId) {
        try {
            List<Object[]> results = paymentHistoryRepository
                .getMonthlyPaymentsByProject(projectUniqueId);
            
            return results.stream()
                .map(arr -> PaymentTrendDTO.builder()
                    .month((String) arr[0])
                    .totalAmount((BigDecimal) arr[1])
                    .paymentCount(((Number) arr[2]).intValue())
                    .build())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching payment timeline", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get recent payments (last 10)
     */
    private List<ActivityDTO> getRecentPayments(String projectUniqueId) {
        try {
            List<PaymentHistoryEntity> payments = paymentHistoryRepository
                .findTop10ByProjectIdOrderByPaymentDateDesc(projectUniqueId);
            
            return payments.stream()
                .limit(10)
                .map(payment -> ActivityDTO.builder()
                    .type("Payment Received")
                    .action("Payment via " + payment.getPaymentMethod())
                    .status("Completed")
                    .amount(payment.getAmount())
                    .date(payment.getPaymentDate())
                    .color("#22c55e")
                    .reference(payment.getTransactionReference())
                    .build())
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching recent payments", e);
            return Collections.emptyList();
        }
    }
    
    // Helper methods
    
    private String getProjectManager(ProjectEntity project) {
        return project.getAssignedTo() != null ? "Project Manager" : "Not Assigned";
    }
    
    private List<ChartDataDTO> buildChartData(Map<String, Long> dataMap) {
        return dataMap.entrySet().stream()
            .map(entry -> ChartDataDTO.builder()
                .name(entry.getKey())
                .value(entry.getValue().intValue())
                .build())
            .collect(Collectors.toList());
    }
    
    private List<ChartDataDTO> buildCategoryChartData(List<Object[]> categorySpending) {
        return categorySpending.stream()
            .map(arr -> ChartDataDTO.builder()
                .name((String) arr[0])
                .value((BigDecimal) arr[1])
                .build())
            .limit(5)
            .collect(Collectors.toList());
    }
    
    private String getStatusColor(String status) {
        Map<String, String> colors = Map.of(
            "Draft", "#94a3b8",
            "Approved", "#22c55e",
            "Delivered", "#22c55e",
            "Cancelled", "#ef4444",
            "Pending", "#f59e0b"
        );
        return colors.getOrDefault(status, "#94a3b8");
    }
}