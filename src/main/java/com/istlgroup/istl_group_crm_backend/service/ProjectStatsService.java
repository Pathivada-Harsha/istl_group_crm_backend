package com.istlgroup.istl_group_crm_backend.service;



import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectPhaseEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStatsService {

    private final ProjectRepository projectRepository;
    private final ProjectPhaseRepo projectPhaseRepo;   // technical-scope phases for physical progress
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

        // ── Financial progress (40/30/20/10) ─────────────────────────────
        // Single source of the financial score + its component breakdown, also
        // exposed to the dashboard's Progress-breakdown modal so what's stored and
        // what's shown never diverge.
        ProjectDashboardDTO.ProgressBreakdown fb = computeFinancialProgress(project);
        BigDecimal weightedProgress = fb.getFinancialProgress();

        // ── Physical (technical) progress — weighted roll-up of the tech scope.
        //    Kept SEPARATE from the financial figure (they are shown side-by-side,
        //    never blended). NULL when no scope is defined.
        BigDecimal physical = computePhysicalProgress(project.getId());
        project.setPhysicalProgressPct(physical);
        project.setFinancialProgressPct(weightedProgress); // stored for the list/detail dual display

        // ── Overall project progress = TECHNICAL (physical) only ─────────────
        //  Financial is a SEPARATE metric (shown beside the dashboard donut + in the
        //  Progress-breakdown modal), never blended into "progress" — a blended number
        //  hid where a project was actually delayed. A manual override wins; with no
        //  override and no scope this stays NULL, meaning "not tracked".
        //  It used to fall back to the financial score here so the headline wouldn't
        //  be blank, but that made every scope-less project display the financial
        //  number under a "Technical" label — the breakdown modal showed the same
        //  figure twice while saying no scope was defined. The UI now renders "—".
        //  A genuine 0% is not "manually set" — the override lives in its own column
        //  (NULL = auto). (Defect §5.3a.)
        if (project.getProgressOverride() != null) {
            project.setProgressPercentage(project.getProgressOverride());
        } else {
            project.setProgressPercentage(physical);   // null when no scope
        }

        // ── Smart status update (skip if locked) ─────────────────────────
        if (!isLocked) {
            ProjectEntity.ProjectStatus newStatus;

            if (!hasAnyActivity) {
                // Zero activity on all fronts → Not Started
                newStatus = ProjectEntity.ProjectStatus.NOT_STARTED;

            } else if ((weightedProgress.compareTo(new BigDecimal("99.00")) >= 0
                        || received.compareTo(budget) >= 0)
                    && (physical == null || physical.compareTo(new BigDecimal("99.00")) >= 0)) {
                // Completion needs BOTH the financial condition AND the physical work
                // done (≥99% of the tech scope). When no scope is defined (physical
                // == null) fall back to the financial-only rule so existing projects
                // don't regress. (Defect §5.3c.)
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

    // ═══════════════════════════════════════════════════════════════════════
    //  FINANCIAL PROGRESS (40/30/20/10) — read-only; single source for the stored
    //  status logic AND the dashboard Progress-breakdown modal.
    // ═══════════════════════════════════════════════════════════════════════
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private BigDecimal pctClamped(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() <= 0) return BigDecimal.ZERO;
        return num.divide(den, 4, RoundingMode.HALF_UP).multiply(HUNDRED).min(HUNDRED).max(BigDecimal.ZERO);
    }

    /**
     * The financial score (0-100) + its four weighted components and raw ₹ inputs,
     * computed from stored project fields. Weights: Collection 40% | PO Delivery 30% |
     * Invoicing 20% | PO Commitment 10%. Read-only (no persistence).
     */
    public ProjectDashboardDTO.ProgressBreakdown computeFinancialProgress(ProjectEntity project) {
        BigDecimal budget    = project.getBudget()            != null ? project.getBudget()            : BigDecimal.ZERO;
        BigDecimal received  = project.getPaidInvoiceValue()  != null ? project.getPaidInvoiceValue()  : BigDecimal.ZERO;
        BigDecimal invoiced  = project.getTotalInvoiceValue() != null ? project.getTotalInvoiceValue() : BigDecimal.ZERO;
        BigDecimal deliveredPOVal = project.getDeliveredPoValue() != null ? project.getDeliveredPoValue() : BigDecimal.ZERO;
        BigDecimal committedPOVal = project.getTotalPoValue() != null
                ? project.getTotalPoValue().subtract(project.getCancelledPoValue() != null ? project.getCancelledPoValue() : BigDecimal.ZERO)
                : BigDecimal.ZERO;
        int totalPOs     = project.getTotalPoCount()     != null ? project.getTotalPoCount()     : 0;
        int deliveredPOs = project.getDeliveredPoCount()  != null ? project.getDeliveredPoCount()  : 0;

        BigDecimal collection = pctClamped(received, budget);
        BigDecimal invoicing  = pctClamped(invoiced, budget);
        BigDecimal commitment = pctClamped(committedPOVal, budget);
        BigDecimal delivery;
        if (committedPOVal.signum() > 0) {
            delivery = pctClamped(deliveredPOVal, committedPOVal);
        } else if (totalPOs > 0) {
            delivery = new BigDecimal(deliveredPOs).divide(new BigDecimal(totalPOs), 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).min(HUNDRED).max(BigDecimal.ZERO);
        } else {
            delivery = BigDecimal.ZERO;
        }

        BigDecimal financial = collection.multiply(new BigDecimal("0.40"))
                .add(delivery.multiply(new BigDecimal("0.30")))
                .add(invoicing.multiply(new BigDecimal("0.20")))
                .add(commitment.multiply(new BigDecimal("0.10")))
                .setScale(2, RoundingMode.HALF_UP);

        ProjectDashboardDTO.ProgressBreakdown b = new ProjectDashboardDTO.ProgressBreakdown();
        b.setFinancialProgress(financial);
        b.setCollectionPct(collection.setScale(2, RoundingMode.HALF_UP));
        b.setDeliveryPct(delivery.setScale(2, RoundingMode.HALF_UP));
        b.setInvoicingPct(invoicing.setScale(2, RoundingMode.HALF_UP));
        b.setCommitmentPct(commitment.setScale(2, RoundingMode.HALF_UP));
        b.setBudget(budget);
        b.setReceived(received);
        b.setInvoiced(invoiced);
        b.setCommittedPoValue(committedPOVal);
        b.setDeliveredPoValue(deliveredPOVal);
        return b;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PHYSICAL (TECHNICAL) PROGRESS — weighted roll-up of the tech scope
    // ═══════════════════════════════════════════════════════════════════════

    private static final ObjectMapper PHYS_MAPPER = new ObjectMapper();

    /**
     * Recompute ONLY physical progress for a project (cheap — no native financial
     * queries). Called after a scope/phase/progress save so the figure is fresh
     * immediately; the full stats recalc (scheduler / PO/invoice events) re-derives
     * status from it.
     */
    @Transactional
    public void recomputePhysicalProgress(String projectUniqueId) {
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId).orElse(null);
        if (project == null) return;
        BigDecimal physical = computePhysicalProgress(project.getId());
        project.setPhysicalProgressPct(physical);
        // Keep the headline in step with the roll-up right away (same rule as the full
        // recalc: override wins, otherwise physical, otherwise NULL = not tracked) —
        // otherwise adding the first phase leaves progress_percentage stale until the
        // scheduler next runs.
        if (project.getProgressOverride() == null) {
            project.setProgressPercentage(physical);
        }
        projectRepository.save(project);
    }

    /**
     * Weighted roll-up of the project's phases:
     *   parent actual = (has sub-items) ? Σ(sub.progress×sub.weight)/Σ(sub.weight) : phase.progressPercent
     *   physical      = Σ(parent.actual × parent.weight) / Σ(parent.weight)
     * Phases with null/zero weight are excluded from the denominator. Returns NULL
     * (not 0) when there is no scope or no positive weight — "no plan" ≠ "no work".
     */
    private BigDecimal computePhysicalProgress(Long projectId) {
        if (projectId == null) return null;
        List<ProjectPhaseEntity> phases = projectPhaseRepo.findByProjectIdOrderBySeqNo(projectId);
        if (phases == null || phases.isEmpty()) return null;
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (ProjectPhaseEntity p : phases) {
            BigDecimal w = p.getWeightPct();
            if (w == null || w.signum() <= 0) continue;
            weightedSum = weightedSum.add(parentActual(p).multiply(w));
            weightTotal = weightTotal.add(w);
        }
        if (weightTotal.signum() > 0) {
            return weightedSum.divide(weightTotal, 2, RoundingMode.HALF_UP);
        }
        // Phases exist but none carry a weight → equal-weighted average of their
        // actuals (matches the grid's equal-split default), so physical progress is
        // still meaningful for plans authored before parent weights were set.
        BigDecimal sum = BigDecimal.ZERO;
        for (ProjectPhaseEntity p : phases) sum = sum.add(parentActual(p));
        return sum.divide(new BigDecimal(phases.size()), 2, RoundingMode.HALF_UP);
    }

    /** A phase's actual %: weighted mean of its sub-items when present, else its own. */
    private BigDecimal parentActual(ProjectPhaseEntity p) {
        List<Map<String, Object>> subs = parseSubItems(p.getSubItems());
        if (subs != null && !subs.isEmpty()) {
            BigDecimal wsum = BigDecimal.ZERO, acc = BigDecimal.ZERO;
            for (Map<String, Object> si : subs) {
                BigDecimal w = toBD(si.get("weightPct"));
                if (w.signum() <= 0) continue;
                acc = acc.add(toBD(si.get("progressPercent")).multiply(w));
                wsum = wsum.add(w);
            }
            if (wsum.signum() > 0) return acc.divide(wsum, 2, RoundingMode.HALF_UP);
        }
        return p.getProgressPercent() != null ? p.getProgressPercent() : BigDecimal.ZERO;
    }

    private List<Map<String, Object>> parseSubItems(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return PHYS_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal toBD(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(o)); }
        catch (Exception e) { return BigDecimal.ZERO; }
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