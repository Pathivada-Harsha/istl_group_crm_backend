package com.istlgroup.istl_group_crm_backend.service;



import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStatsService {

    private final ProjectRepository projectRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final QuotationRepository quotationRepository;
    private final BillRepository billRepository;
    private final VendorRepository vendorRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReceiptRepository receiptRepository;       // source of truth for amount received
    private final BillPaymentRepository billPaymentRepository; // source of truth for paid_bill_value
    private final VendorAdvanceRepository vendorAdvanceRepository; // vendor advances paid
    

    /**
     * Recalculate ALL project statistics from scratch
     * Use this after bulk operations or to fix inconsistencies
     */
    @Transactional
    public void recalculateProjectStats(String projectUniqueId) {
        log.info("Recalculating statistics for project: {}", projectUniqueId);

        // Flush all pending Hibernate writes before native aggregate queries run
        billRepository.flush();
        invoiceRepository.flush();

        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));

        // Calculate all stats
        calculatePOStats(project);
        calculateQuotationStats(project);
        calculateBillStats(project);
        calculateVendorStats(project);
        calculateFinancialMetrics(project);
        
        // Update timestamps
        project.setStatsCalculatedAt(LocalDateTime.now());
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
        log.info("Statistics recalculated successfully for project: {}", projectUniqueId);
    }

    /**
     * Calculate Purchase Order statistics
     */
    private void calculatePOStats(ProjectEntity project) {
        String projectId = project.getProjectUniqueId();
        
        // Total POs
        Long totalCount = purchaseOrderRepository.countByProjectId(projectId);
        project.setTotalPoCount(totalCount != null ? totalCount.intValue() : 0);
        
        BigDecimal totalValue = purchaseOrderRepository.sumTotalValueByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setTotalPoValue(totalValue);
        
        // Delivered POs
        Long deliveredCount = purchaseOrderRepository.countByProjectIdAndStatus(projectId, "Delivered");
        project.setDeliveredPoCount(deliveredCount != null ? deliveredCount.intValue() : 0);
        
        BigDecimal deliveredValue = purchaseOrderRepository
            .sumTotalValueByProjectIdAndStatus(projectId, "Delivered")
            .orElse(BigDecimal.ZERO);
        project.setDeliveredPoValue(deliveredValue);
        
        // Pending POs (Approved, Ordered, In-Transit)
        BigDecimal pendingValue = BigDecimal.ZERO;
        List<String> pendingStatuses = Arrays.asList("Approved", "Ordered", "In-Transit");
        
        for (String status : pendingStatuses) {
            BigDecimal statusValue = purchaseOrderRepository
                .sumTotalValueByProjectIdAndStatus(projectId, status)
                .orElse(BigDecimal.ZERO);
            pendingValue = pendingValue.add(statusValue);
        }
        project.setPendingPoValue(pendingValue);
        
        // Cancelled POs
        BigDecimal cancelledValue = purchaseOrderRepository
            .sumTotalValueByProjectIdAndStatus(projectId, "Cancelled")
            .orElse(BigDecimal.ZERO);
        project.setCancelledPoValue(cancelledValue);
    }

    /**
     * Calculate Quotation statistics
     */
    private void calculateQuotationStats(ProjectEntity project) {
        String projectId = project.getProjectUniqueId();
        
        Long totalCount = quotationRepository.countByProjectId(projectId);
        project.setTotalQuotationCount(totalCount != null ? totalCount.intValue() : 0);
        
        BigDecimal totalValue = quotationRepository.sumTotalValueByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setTotalQuotationValue(totalValue);
        
        Long approvedCount = quotationRepository.countByProjectIdAndStatus(projectId, "Approved");
        project.setApprovedQuotationCount(approvedCount != null ? approvedCount.intValue() : 0);
        
        BigDecimal approvedValue = quotationRepository
            .sumTotalValueByProjectIdAndStatus(projectId, "Approved")
            .orElse(BigDecimal.ZERO);
        project.setApprovedQuotationValue(approvedValue);
    }

    /**
     * Calculate Bill statistics
     */
    private void calculateBillStats(ProjectEntity project) {
        String projectId = project.getProjectUniqueId();
        
        Long totalCount = billRepository.countByProjectId(projectId);
        project.setTotalBillCount(totalCount != null ? totalCount.intValue() : 0);
        
        BigDecimal totalAmount = billRepository.sumTotalAmountByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setTotalBillValue(totalAmount);
        
        Long paidCount = billRepository.countByProjectIdAndStatus(projectId, "Paid");
        project.setPaidBillCount(paidCount != null ? paidCount.intValue() : 0);
        
        // FIX: paid_bill_value has two sources:
        //  1. vendor_advances — covers ADVANCE type (recorded upfront) and BILL_PAYMENT
        //     type (payment recorded via Vendor Payments page). This is the source for
        //     all vendor-payment-page cash outflows.
        //  2. bill_payments WHERE reference NOT LIKE VADV/VPAY — covers direct payments
        //     recorded from the Bills page (BillService.addPayment / markAsPaid).
        //     These rows do NOT exist in vendor_advances so they must be summed separately.
        //     Rows written by applyAmountToBill() have reference = VADV-xxxx or VPAY-xxxx
        //     and must be EXCLUDED to avoid double-counting with vendor_advances.
        BigDecimal paidViaAdvances        = vendorAdvanceRepository.sumAdvanceAmountByProjectId(projectId);
        BigDecimal paidViaDirectPayments  = billPaymentRepository.sumDirectPaymentAmountByProjectId(projectId);
        BigDecimal totalPaid = (paidViaAdvances       != null ? paidViaAdvances       : BigDecimal.ZERO)
                             .add(paidViaDirectPayments != null ? paidViaDirectPayments : BigDecimal.ZERO);
        project.setPaidBillValue(totalPaid);

        // FIX: pending = MAX(0, totalBillValue - paidBillValue) — never negative
        BigDecimal totalBill = project.getTotalBillValue() != null ? project.getTotalBillValue() : BigDecimal.ZERO;
        project.setPendingPaymentValue(totalBill.subtract(totalPaid).max(BigDecimal.ZERO));
    }

    /**
     * Calculate Vendor statistics
     */
    private void calculateVendorStats(ProjectEntity project) {
        String projectId = project.getProjectUniqueId();
        
        Long activeCount = vendorRepository.countByProjectIdAndStatus(projectId, "Active");
        project.setActiveVendorCount(activeCount != null ? activeCount.intValue() : 0);
        
        BigDecimal totalSpend = vendorRepository.sumTotalPurchaseValueByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setTotalVendorSpend(totalSpend);
    }

    /**
     * Calculate financial metrics
     */
    private void calculateFinancialMetrics(ProjectEntity project) {
        BigDecimal budget = project.getBudget() != null ? project.getBudget() : BigDecimal.ZERO;
        
        // Budget utilized = Total PO Value - Cancelled POs
        BigDecimal budgetUtilized = project.getTotalPoValue()
            .subtract(project.getCancelledPoValue());
        project.setBudgetUtilized(budgetUtilized);
        
        // Budget utilization percentage
        if (budget.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal utilizationPercent = budgetUtilized
                .divide(budget, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
            project.setBudgetUtilizationPercent(utilizationPercent);
        } else {
            project.setBudgetUtilizationPercent(BigDecimal.ZERO);
        }
        
        // Projected profit = Budget - Budget Utilized
        BigDecimal projectedProfit = budget.subtract(budgetUtilized);
        project.setProjectedProfit(projectedProfit);
        
        // Profit margin percentage
        if (budget.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal profitMargin = projectedProfit
                .divide(budget, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
            project.setProfitMarginPercent(profitMargin);
        } else {
            project.setProfitMarginPercent(BigDecimal.ZERO);
        }

        // ═══════════════════════════════════════════════════════════════════
        // SMART AUTO-STATUS + PROGRESS ENGINE
        // Never overrides ON_HOLD or CANCELLED — those are manual decisions.
        // ═══════════════════════════════════════════════════════════════════
        ProjectEntity.ProjectStatus currentStatus = project.getStatus();
        boolean isLocked = currentStatus == ProjectEntity.ProjectStatus.ON_HOLD
                        || currentStatus == ProjectEntity.ProjectStatus.CANCELLED;

        // ── Procurement signals ──────────────────────────────────────────
        int    totalPOs       = project.getTotalPoCount()    != null ? project.getTotalPoCount()    : 0;
        int    deliveredPOs   = project.getDeliveredPoCount() != null ? project.getDeliveredPoCount() : 0;
        int    totalQuotes    = project.getTotalQuotationCount() != null ? project.getTotalQuotationCount() : 0;
        int    approvedQuotes = project.getApprovedQuotationCount() != null ? project.getApprovedQuotationCount() : 0;
        int    totalBills     = project.getTotalBillCount()  != null ? project.getTotalBillCount()  : 0;
        BigDecimal deliveredPOVal = project.getDeliveredPoValue() != null ? project.getDeliveredPoValue() : BigDecimal.ZERO;
        BigDecimal committedPOVal = project.getTotalPoValue() != null
                ? project.getTotalPoValue().subtract(project.getCancelledPoValue() != null ? project.getCancelledPoValue() : BigDecimal.ZERO)
                : BigDecimal.ZERO;

        // ── Financial signals ────────────────────────────────────────────
        BigDecimal received   = project.getPaidInvoiceValue()  != null ? project.getPaidInvoiceValue()  : BigDecimal.ZERO;
        BigDecimal invoiced   = project.getTotalInvoiceValue() != null ? project.getTotalInvoiceValue() : BigDecimal.ZERO;
        int        totalInvoices = project.getTotalInvoiceCount() != null ? project.getTotalInvoiceCount() : 0;

        boolean hasAnyActivity = totalPOs > 0 || totalQuotes > 0 || totalBills > 0 || totalInvoices > 0
                               || received.compareTo(BigDecimal.ZERO) > 0;
        boolean hasProcurement = totalPOs > 0 || approvedQuotes > 0;
        boolean hasFinancial   = totalInvoices > 0 || received.compareTo(BigDecimal.ZERO) > 0;

        // ── Weighted progress calculation ────────────────────────────────
        // Weights: Financial collection 40% | Delivery 30% | Invoicing 20% | PO commitment 10%
        BigDecimal financialScore    = BigDecimal.ZERO; // paidInvoice / budget
        BigDecimal deliveryScore     = BigDecimal.ZERO; // deliveredPOValue / committedPOValue (or count-based)
        BigDecimal invoicingScore    = BigDecimal.ZERO; // totalInvoiceValue / budget
        BigDecimal commitmentScore   = BigDecimal.ZERO; // committedPOValue / budget

        if (budget.compareTo(BigDecimal.ZERO) > 0) {
            financialScore = received.divide(budget, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).min(new BigDecimal("100")).max(BigDecimal.ZERO);
            invoicingScore = invoiced.divide(budget, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).min(new BigDecimal("100")).max(BigDecimal.ZERO);
            commitmentScore = committedPOVal.divide(budget, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).min(new BigDecimal("100")).max(BigDecimal.ZERO);
        }
        if (committedPOVal.compareTo(BigDecimal.ZERO) > 0) {
            deliveryScore = deliveredPOVal.divide(committedPOVal, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).min(new BigDecimal("100")).max(BigDecimal.ZERO);
        } else if (totalPOs > 0) {
            // Fallback: count-based delivery when no PO values
            deliveryScore = new BigDecimal(deliveredPOs)
                    .divide(new BigDecimal(totalPOs), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        BigDecimal weightedProgress = financialScore.multiply(new BigDecimal("0.40"))
                .add(deliveryScore.multiply(new BigDecimal("0.30")))
                .add(invoicingScore.multiply(new BigDecimal("0.20")))
                .add(commitmentScore.multiply(new BigDecimal("0.10")))
                .setScale(2, RoundingMode.HALF_UP);

        // ── Only write auto-progress when no manual override is set ──────
        boolean hasManualProgress = project.getProgressPercentage() != null
                && project.getProgressPercentage().compareTo(BigDecimal.ZERO) > 0;
        if (!hasManualProgress) {
            project.setProgressPercentage(weightedProgress);
        }

        // ── Smart status update (skip if locked) ─────────────────────────
        if (!isLocked) {
            ProjectEntity.ProjectStatus newStatus;

            if (!hasAnyActivity) {
                // Zero activity on all fronts → Not Started
                newStatus = ProjectEntity.ProjectStatus.NOT_STARTED;

            } else if (weightedProgress.compareTo(new BigDecimal("99.00")) >= 0
                    || received.compareTo(budget) >= 0) {
                // Fully received from client → Completed
                newStatus = ProjectEntity.ProjectStatus.COMPLETED;

            } else if (hasProcurement || hasFinancial) {
                // Has POs/quotations/invoices/receipts → actively in progress
                newStatus = ProjectEntity.ProjectStatus.IN_PROGRESS;

            } else {
                // Has some activity but only quotations or minor signals → Planning
                newStatus = ProjectEntity.ProjectStatus.PLANNING;
            }

            if (newStatus != currentStatus) {
                log.info("Auto status change for project {}: {} → {}",
                        project.getProjectUniqueId(), currentStatus, newStatus);
                project.setStatus(newStatus);
            }
        }
    }

    /**
     * Recalculate stats for all projects
     * Use with caution - can be time-consuming
     */
    @Transactional
    public void recalculateAllProjectStats() {
        log.info("Starting recalculation for all active projects");
        
        List<ProjectEntity> projects = projectRepository.findByIsActive(true);
        int successCount = 0;
        int failCount = 0;
        
        for (ProjectEntity project : projects) {
            try {
                recalculateProjectStats(project.getProjectUniqueId());
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("Failed to recalculate stats for project: {}", 
                    project.getProjectUniqueId(), e);
            }
        }
        
        log.info("Completed recalculation for all projects. Success: {}, Failed: {}", 
            successCount, failCount);
    }

    /**
     * Quick update after PO status change
     */
    @Transactional
    public void updateProjectAfterPOChange(String projectUniqueId) {
        log.debug("Quick update for project {} after PO change", projectUniqueId);
        
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found"));
        
        calculatePOStats(project);
        calculateFinancialMetrics(project);
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
    }

    /**
     * Quick update after Quotation status change
     */
    @Transactional
    public void updateProjectAfterQuotationChange(String projectUniqueId) {
        log.debug("Quick update for project {} after Quotation change", projectUniqueId);
        
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found"));
        
        calculateQuotationStats(project);
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
    }

    /**
     * Quick update after Bill payment
     */
    @Transactional
    public void updateProjectAfterBillPayment(String projectUniqueId) {
        log.debug("Quick update for project {} after Bill payment", projectUniqueId);

        // Flush any pending Hibernate writes (e.g. the bill.paid_amount update from
        // BillService.addPayment) to the DB before the native SUM() queries run.
        // Without this, native SQL reads the OLD paid_amount from DB.
        billRepository.flush();

        // Use orElse(null) — warehouse-issuance advances may carry an inventory
        // projectId that is not registered in the CRM projects table.
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElse(null);
        if (project == null) {
            log.debug("updateProjectAfterBillPayment: project [{}] not found in CRM projects table — skipping stats update", projectUniqueId);
            return;
        }
        
        calculateBillStats(project);
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
        log.info("Bill stats updated for project [{}]: totalBill={}, paidBill={}, pending={}",
                 projectUniqueId, project.getTotalBillValue(),
                 project.getPaidBillValue(), project.getPendingPaymentValue());
    }

    /**
     * Quick update after Invoice / Receipt change
     */
    @Transactional
    public void updateProjectAfterInvoiceChange(String projectUniqueId) {
        log.debug("Quick update for project {} after Invoice/Receipt change", projectUniqueId);

        // Flush any pending Hibernate writes (e.g. the invoice.paid_amount update from
        // InvoiceService.recordPayment) to the DB before the native SUM() queries run.
        invoiceRepository.flush();

        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));

        calculateInvoiceStats(project);
        project.setLastProcurementUpdate(LocalDateTime.now());

        projectRepository.save(project);
        log.info("Invoice stats updated for project [{}]: totalValue={}, paidValue={}, pending={}",
                 projectUniqueId, project.getTotalInvoiceValue(),
                 project.getPaidInvoiceValue(), project.getPendingInvoiceValue());
    }

    /**
     * Calculate Invoice statistics (client billing)
     */
    private void calculateInvoiceStats(ProjectEntity project) {
        String projectId = project.getProjectUniqueId();

        Long totalCount = invoiceRepository.countByProjectId(projectId);
        project.setTotalInvoiceCount(totalCount != null ? totalCount.intValue() : 0);

        BigDecimal totalAmount = invoiceRepository.sumTotalAmountByProjectId(projectId);
        project.setTotalInvoiceValue(totalAmount != null ? totalAmount : BigDecimal.ZERO);

        Long paidCount = invoiceRepository.countByProjectIdAndStatus(projectId, "Paid");
        project.setPaidInvoiceCount(paidCount != null ? paidCount.intValue() : 0);

        // FIX: paid_invoice_value = SUM of ALL receipts (ADVANCE + INVOICE_PAYMENT) for this
        // project. Receipts represent actual cash received — regardless of whether an advance
        // has been allocated to a specific invoice yet. This is the single source of truth
        // for "amount received" on the project dashboard, updated immediately on receipt creation.
        BigDecimal receivedAmount = receiptRepository.sumReceiptAmountByProjectId(projectId);
        project.setPaidInvoiceValue(receivedAmount != null ? receivedAmount : BigDecimal.ZERO);

        // FIX: pending_invoice_value = MAX(0, totalInvoiceValue - paidInvoiceValue)
        // Using GREATEST(0, ...) prevents a negative value when advance receipts are
        // recorded against a project before any invoice has been raised.
        // sumPendingAmountByProjectId only covers invoices in Sent/Partially Paid status
        // and would give 0 while paidInvoiceValue is already > 0, making the dashboard
        // show a negative pending amount. Instead we derive it from the totals.
        BigDecimal totalInv  = project.getTotalInvoiceValue() != null ? project.getTotalInvoiceValue() : BigDecimal.ZERO;
        BigDecimal receivedInv = project.getPaidInvoiceValue() != null ? project.getPaidInvoiceValue() : BigDecimal.ZERO;
        BigDecimal pendingAmount = totalInv.subtract(receivedInv).max(BigDecimal.ZERO);
        project.setPendingInvoiceValue(pendingAmount);
    }

    /**
     * Quick update after Vendor change
     */
    @Transactional
    public void updateProjectAfterVendorChange(String projectUniqueId) {
        log.debug("Quick update for project {} after Vendor change", projectUniqueId);
        
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found"));
        
        calculateVendorStats(project);
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
    }

    /**
     * Get projects that need stats recalculation
     * (not updated in last 24 hours)
     */
    public List<ProjectEntity> getProjectsNeedingRecalculation() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        return projectRepository.findAll().stream()
            .filter(p -> p.getIsActive())
            .filter(p -> p.getStatsCalculatedAt() == null || 
                        p.getStatsCalculatedAt().isBefore(cutoffTime))
            .toList();
    }

    /**
     * Verify project stats consistency
     * Returns true if stats match actual data
     */
    public boolean verifyProjectStats(String projectUniqueId) {
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found"));
        
        // Verify PO count
        Long actualPOCount = purchaseOrderRepository.countByProjectId(projectUniqueId);
        if (!actualPOCount.equals(Long.valueOf(project.getTotalPoCount()))) {
            log.warn("PO count mismatch for project {}: stored={}, actual={}", 
                projectUniqueId, project.getTotalPoCount(), actualPOCount);
            return false;
        }
        
        // Verify PO value
        BigDecimal actualPOValue = purchaseOrderRepository
            .sumTotalValueByProjectId(projectUniqueId)
            .orElse(BigDecimal.ZERO);
        if (project.getTotalPoValue().compareTo(actualPOValue) != 0) {
            log.warn("PO value mismatch for project {}: stored={}, actual={}", 
                projectUniqueId, project.getTotalPoValue(), actualPOValue);
            return false;
        }
        
        log.info("Project stats verified successfully for: {}", projectUniqueId);
        return true;
    }

    /**
     * Fix inconsistent project stats
     */
    @Transactional
    public void fixInconsistentStats() {
        log.info("Starting to fix inconsistent project stats");
        
        List<ProjectEntity> projects = projectRepository.findByIsActive(true);
        int fixedCount = 0;
        
        for (ProjectEntity project : projects) {
            if (!verifyProjectStats(project.getProjectUniqueId())) {
                recalculateProjectStats(project.getProjectUniqueId());
                fixedCount++;
            }
        }
        
        log.info("Fixed stats for {} projects", fixedCount);
    }
}