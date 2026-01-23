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

    /**
     * Recalculate ALL project statistics from scratch
     * Use this after bulk operations or to fix inconsistencies
     */
    @Transactional
    public void recalculateProjectStats(String projectUniqueId) {
        log.info("Recalculating statistics for project: {}", projectUniqueId);
        
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
        
        BigDecimal paidAmount = billRepository.sumPaidAmountByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setPaidBillValue(paidAmount);
        
        BigDecimal balanceAmount = billRepository.sumBalanceAmountByProjectId(projectId)
            .orElse(BigDecimal.ZERO);
        project.setPendingPaymentValue(balanceAmount);
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
        
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found"));
        
        calculateBillStats(project);
        project.setLastProcurementUpdate(LocalDateTime.now());
        
        projectRepository.save(project);
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
