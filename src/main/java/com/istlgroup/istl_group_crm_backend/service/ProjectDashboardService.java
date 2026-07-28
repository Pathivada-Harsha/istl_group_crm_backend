package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.ExpenseDashboardBlock;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO.*;
import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.PaymentHistoryEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
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

    private final ProjectRepository          projectRepository;
    private final PurchaseOrderRepository    purchaseOrderRepository;
    private final QuotationRepository        quotationRepository;
    private final VendorRepository           vendorRepository;
    private final BillRepository             billRepository;
    private final PaymentHistoryRepository   paymentHistoryRepository;
    private final InvoiceRepository          invoiceRepository;
    private final InvoiceItemRepository      invoiceItemRepository;
    private final BillItemRepository         billItemRepository;
    private final ProjectExpenseService      expenseService;
    private final ReceiptRepository          receiptRepository; // source of truth for amount received
    private final ProjectAccessService        projectAccessService;
    private final InvTransactionRepository   invTransactionRepository;
    private final WarehouseRepository        warehouseRepository;
    private final InventoryItemRepository    itemRepository;
    private final VendorAdvanceRepository    vendorAdvanceRepository;
    private final BillPaymentRepository      billPaymentRepository;
    private final ProjectStatsService        statsService; // financial-progress breakdown (read-only)

    @Transactional(readOnly = true)
    public ProjectDashboardDTO getDashboardData(String projectUniqueId) {
        log.info("Fetching dashboard data for project: {}", projectUniqueId);

        ProjectEntity project = projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));

        // ── Fetch expense block (safe – returns zeros if no expenses yet) ──────
        ExpenseDashboardBlock expenseBlock = null;
        try {
            expenseBlock = expenseService.getExpenseDashboardBlock(projectUniqueId);
        } catch (Exception e) {
            log.warn("Could not fetch expense block for {}: {}", projectUniqueId, e.getMessage());
        }

        ProjectDashboardDTO dashboard = ProjectDashboardDTO.builder()
            .projectId(project.getProjectUniqueId())
            .projectName(project.getProjectName())
            .uniqueId(project.getProjectUniqueId())
            .location(project.getLocation())
            .status(project.getStatus() != null ? project.getStatus().name() : "UNKNOWN")
            .groupId(project.getGroupId())
            .subGroupName(project.getSubGroupName())
            .startDate(project.getStartDate())
            .endDate(project.getEndDate())
            .manager(getProjectManager(project))
            .budget(project.getBudget())
            .progressPercentage(project.getProgressPercentage())
            .physicalProgress(project.getPhysicalProgressPct())            // technical (null = no scope)
            .progressBreakdown(statsService.computeFinancialProgress(project)) // financial + 40/30/20/10 split
            .financialData(buildFinancialData(project, expenseBlock))
            .procurementData(buildProcurementData(project, projectUniqueId))
            .recentActivities(getRecentActivities(projectUniqueId))
            .topVendors(getTopVendors(projectUniqueId))
            .spendingTrend(getSpendingTrend(projectUniqueId))
            .projectTimeline(buildProjectTimeline(project, projectUniqueId))
            .paymentMethodDistribution(getPaymentMethodDistribution(projectUniqueId))
            .paymentTimeline(getPaymentTimeline(projectUniqueId))
            .expenseData(expenseBlock)      // ← NEW FIELD in ProjectDashboardDTO
            .warehouseIssuanceData(buildWarehouseIssuanceData(projectUniqueId))
            .siteReturnData(buildSiteReturnData(projectUniqueId))
            .lastUpdate(project.getUpdatedAt())
            .statsCalculatedAt(project.getStatsCalculatedAt())
            .build();

        log.info("Dashboard data built successfully for project: {}", projectUniqueId);
        return dashboard;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aggregated Dashboard  (All / Group / Group+SubGroup)
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getAggregatedDashboard(String groupName, String subGroupName, Long userId, String userRole) {

        // 1 – Fetch the right set of projects, scoped by user's project access grants
        List<ProjectEntity> projects;
        boolean hasGroup    = groupName    != null && !groupName.isBlank();
        boolean hasSubGroup = subGroupName != null && !subGroupName.isBlank();
        boolean isAdmin     = projectAccessService.isBypassRole(userRole);

        // For non-admin users, restrict to accessible project IDs
        List<String> accessibleProjectIds = isAdmin ? null : projectAccessService.getAccessibleProjectIds(userId, userRole);

        if (!hasGroup) {
            projects = projectRepository.findByIsActive(true);
        } else if (!hasSubGroup) {
            projects = projectRepository.findByIsActive(true).stream()
                .filter(p -> groupName.equalsIgnoreCase(p.getGroupId()))
                .collect(Collectors.toList());
        } else {
            projects = projectRepository.findByIsActive(true).stream()
                .filter(p -> groupName.equalsIgnoreCase(p.getGroupId())
                          && subGroupName.equalsIgnoreCase(p.getSubGroupName()))
                .collect(Collectors.toList());
        }

        // Filter to accessible projects for non-admin users
        if (!isAdmin && accessibleProjectIds != null) {
            final List<String> finalAccessible = accessibleProjectIds;
            projects = projects.stream()
                .filter(p -> finalAccessible.contains(p.getProjectUniqueId()))
                .collect(Collectors.toList());
        }

        // 2 – Aggregate the pre-computed columns stored on each project row
        BigDecimal totalBudget          = BigDecimal.ZERO;
        BigDecimal totalBillValue       = BigDecimal.ZERO;
        BigDecimal paidBillValue        = BigDecimal.ZERO;
        BigDecimal pendingPaymentValue  = BigDecimal.ZERO;
        BigDecimal totalInvoiceValue    = BigDecimal.ZERO;
        BigDecimal paidInvoiceValue     = BigDecimal.ZERO;
        BigDecimal totalPoValue         = BigDecimal.ZERO;
        int        totalPoCount         = 0;
        int        deliveredPoCount     = 0;
        int        totalProjects        = projects.size();
        int        notStartedProjects  = 0;
        int        completedProjects    = 0;
        int        inProgressProjects   = 0;
        int        planningProjects     = 0;
        int        onHoldProjects       = 0;
        int        cancelledProjects    = 0;
        BigDecimal totalVendorSpend     = BigDecimal.ZERO;
        int        activeVendorCount    = 0;
        int        totalQuotationCount  = 0;
        int        totalInvoiceCount    = 0;

        for (ProjectEntity p : projects) {
            totalBudget         = totalBudget.add(safe(p.getBudget()));
            totalBillValue      = totalBillValue.add(safe(p.getTotalBillValue()));
            paidBillValue       = paidBillValue.add(safe(p.getPaidBillValue()));
            pendingPaymentValue = pendingPaymentValue.add(safe(p.getPendingPaymentValue()));
            totalInvoiceValue   = totalInvoiceValue.add(safe(p.getTotalInvoiceValue()));
            paidInvoiceValue    = paidInvoiceValue.add(safe(p.getPaidInvoiceValue()));
            totalPoValue        = totalPoValue.add(safe(p.getTotalPoValue()));
            totalPoCount       += p.getTotalPoCount()    != null ? p.getTotalPoCount()    : 0;
            deliveredPoCount   += p.getDeliveredPoCount() != null ? p.getDeliveredPoCount() : 0;
            totalVendorSpend   = totalVendorSpend.add(safe(p.getTotalVendorSpend()));
            activeVendorCount  += p.getActiveVendorCount()   != null ? p.getActiveVendorCount()   : 0;
            totalQuotationCount+= p.getTotalQuotationCount() != null ? p.getTotalQuotationCount() : 0;
            totalInvoiceCount  += p.getTotalInvoiceCount()   != null ? p.getTotalInvoiceCount()   : 0;

            // if/else instead of switch: an enum switch makes javac emit a synthetic
            // ProjectDashboardService$1 switch-map class that spring-boot-devtools' restart
            // classloader can fail to load (NoClassDefFoundError on /dashboard/aggregate).
            // No switch, no synthetic class, nothing for devtools to trip over.
            ProjectEntity.ProjectStatus status = p.getStatus();
            if (status != null) {
                if      (status == ProjectEntity.ProjectStatus.NOT_STARTED) notStartedProjects++;
                else if (status == ProjectEntity.ProjectStatus.COMPLETED)   completedProjects++;
                else if (status == ProjectEntity.ProjectStatus.IN_PROGRESS) inProgressProjects++;
                else if (status == ProjectEntity.ProjectStatus.PLANNING)    planningProjects++;
                else if (status == ProjectEntity.ProjectStatus.ON_HOLD)     onHoldProjects++;
                else if (status == ProjectEntity.ProjectStatus.CANCELLED)   cancelledProjects++;
            }
        }

        // 3 – Derived metrics
        // FIX: clamp to zero — advance receipts can exceed billed amount, making this negative
        BigDecimal pendingReceipts   = totalInvoiceValue.subtract(paidInvoiceValue).max(BigDecimal.ZERO);
        BigDecimal cashInHand        = paidInvoiceValue.subtract(paidBillValue);
        BigDecimal cashDeficit       = BigDecimal.ZERO;
        if (cashInHand.compareTo(BigDecimal.ZERO) < 0) {
            cashDeficit = cashInHand.abs();
            cashInHand  = BigDecimal.ZERO;
        }

        double billingPct = totalBudget.compareTo(BigDecimal.ZERO) > 0
            ? paidInvoiceValue.multiply(new BigDecimal("100"))
                .divide(totalBudget, 2, RoundingMode.HALF_UP).doubleValue() : 0.0;

        double paymentPct = totalBillValue.compareTo(BigDecimal.ZERO) > 0
            ? paidBillValue.multiply(new BigDecimal("100"))
                .divide(totalBillValue, 2, RoundingMode.HALF_UP).doubleValue() : 0.0;

        double deliveryRate = totalPoCount > 0
            ? (deliveredPoCount * 100.0) / totalPoCount : 0.0;

        // 4 – Per-project summary list (for breakdown table)
        List<Map<String, Object>> projectList = projects.stream()
            .map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("projectId",   p.getProjectUniqueId());
                pm.put("projectName", p.getProjectName());
                pm.put("groupId",     p.getGroupId());
                pm.put("subGroupName",p.getSubGroupName());
                pm.put("status",      p.getStatus() != null ? p.getStatus().name() : "UNKNOWN");
                pm.put("budget",      safe(p.getBudget()));
                pm.put("billed",      safe(p.getTotalInvoiceValue()));
                pm.put("received",    safe(p.getPaidInvoiceValue()));
                pm.put("spent",       safe(p.getPaidBillValue()));
                pm.put("pendingPay",  safe(p.getPendingPaymentValue()));
                pm.put("totalPOs",    p.getTotalPoCount()    != null ? p.getTotalPoCount()    : 0);
                pm.put("deliveredPOs",p.getDeliveredPoCount() != null ? p.getDeliveredPoCount() : 0);
                pm.put("startDate",   p.getStartDate());
                pm.put("endDate",     p.getEndDate());
                pm.put("progressPercentage", p.getProgressPercentage() != null ? p.getProgressPercentage().doubleValue() : null);
                pm.put("capacityValue", p.getCapacityValue() != null ? p.getCapacityValue().doubleValue() : null);
                pm.put("capacityUnit",  p.getCapacityUnit());
                return pm;
            }).collect(Collectors.toList());

        // 5 – Status distribution for chart
        List<Map<String, Object>> statusDist = new ArrayList<>();
        if (notStartedProjects > 0) statusDist.add(Map.of("name","Not Started","value",notStartedProjects));
        if (planningProjects   > 0) statusDist.add(Map.of("name","Planning",   "value",planningProjects));
        if (inProgressProjects > 0) statusDist.add(Map.of("name","In Progress","value",inProgressProjects));
        if (completedProjects  > 0) statusDist.add(Map.of("name","Completed",  "value",completedProjects));
        if (onHoldProjects     > 0) statusDist.add(Map.of("name","On Hold",    "value",onHoldProjects));
        if (cancelledProjects  > 0) statusDist.add(Map.of("name","Cancelled",  "value",cancelledProjects));

        // 6 – Assemble response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope",            hasGroup ? (hasSubGroup ? "SUBGROUP" : "GROUP") : "ALL");
        result.put("groupName",        hasGroup    ? groupName    : null);
        result.put("subGroupName",     hasSubGroup ? subGroupName : null);
        result.put("totalProjects",    totalProjects);
        result.put("notStartedProjects",notStartedProjects);
        result.put("completedProjects",completedProjects);
        result.put("inProgressProjects",inProgressProjects);
        result.put("planningProjects", planningProjects);
        result.put("onHoldProjects",   onHoldProjects);
        result.put("cancelledProjects",cancelledProjects);
        result.put("statusDistribution", statusDist);

        Map<String, Object> financial = new LinkedHashMap<>();
        financial.put("totalProjectValue",  totalBudget);
        financial.put("totalBilled",        totalInvoiceValue);
        financial.put("totalReceived",      paidInvoiceValue);
        financial.put("pendingReceipts",    pendingReceipts);
        financial.put("billingPercentage",  billingPct);
        financial.put("totalPayable",       totalBillValue);
        financial.put("totalPaid",          paidBillValue);
        financial.put("pendingPayments",    pendingPaymentValue);
        financial.put("paymentPercentage",  paymentPct);
        financial.put("cashInHand",         cashInHand);
        financial.put("cashDeficit",        cashDeficit);
        financial.put("totalVendorSpend",   totalVendorSpend);
        result.put("financial", financial);

        Map<String, Object> procurement = new LinkedHashMap<>();
        procurement.put("totalPOs",       totalPoCount);
        procurement.put("deliveredPOs",   deliveredPoCount);
        procurement.put("deliveryRate",   deliveryRate);
        procurement.put("totalPOValue",   totalPoValue);
        procurement.put("totalVendors",   activeVendorCount);
        procurement.put("totalQuotations",totalQuotationCount);
        procurement.put("totalInvoices",  totalInvoiceCount);
        result.put("procurement", procurement);

        result.put("projects", projectList);

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Financial Data
    // Profit = Total Project Value − (Total Procurement Cost + Total Employee Expenses)
    // Only calculated and shown when project status = COMPLETED.
    // ─────────────────────────────────────────────────────────────────────────
    private FinancialData buildFinancialData(ProjectEntity project, ExpenseDashboardBlock expenseBlock) {
        BigDecimal totalProjectValue = project.getBudget() != null ? project.getBudget() : BigDecimal.ZERO;
        boolean isCompleted = project.getStatus() == ProjectEntity.ProjectStatus.COMPLETED;
        String projectUniqueId = project.getProjectUniqueId();

        // ── Live queries (same source as Reports) ─────────────────────────────
        // Read fresh from invoices/bills tables — NOT cached project columns
        // which can be stale and cause dashboard vs reports discrepancies.
        List<InvoiceEntity> invoices = invoiceRepository.findByProjectIdAndDeletedAtIsNull(projectUniqueId);
        BigDecimal totalInvoiceValue = invoices.stream()
            .map(i -> safe(i.getTotalAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Amount Received: use receipts table as single source of truth ─────
        // Summing invoice.paidAmount would double-count advances:
        //   - An ADVANCE receipt increments project paid_invoice_value when created.
        //   - When that advance is later allocated to an invoice, invoice.paidAmount is also
        //     incremented — but the cash was already received and counted.
        // Using SUM(receipts.amount) avoids this: it counts each receipt exactly once,
        // regardless of whether the advance has been allocated yet.
        BigDecimal advanceAmount        = receiptRepository.sumReceiptAmountByProjectIdAndType(projectUniqueId, "ADVANCE");
        BigDecimal invoicePaymentAmount = receiptRepository.sumReceiptAmountByProjectIdAndType(projectUniqueId, "INVOICE_PAYMENT");
        BigDecimal paidInvoiceValue     = (advanceAmount        != null ? advanceAmount        : BigDecimal.ZERO)
                                        .add(invoicePaymentAmount != null ? invoicePaymentAmount : BigDecimal.ZERO);

        List<BillEntity> bills = billRepository.findByProjectIdAndStatusNot(projectUniqueId, "Cancelled");
        BigDecimal totalBillValue = bills.stream()
            .map(b -> safe(b.getTotalAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // paidBillValue uses the same two-source calculation as ProjectStatsService.calculateBillStats:
        // Source 1: ALL vendor_advances (ADVANCE + BILL_PAYMENT) -- real cash out, advances included immediately
        // Source 2: direct bill_payments (from Bills page, reference NOT VADV/VPAY) -- no duplicate with source 1
        BigDecimal paidViaAdvances       = vendorAdvanceRepository.sumAdvanceAmountByProjectId(projectUniqueId);
        BigDecimal paidViaDirectPayments = billPaymentRepository.sumDirectPaymentAmountByProjectId(projectUniqueId);
        BigDecimal paidBillValue = (paidViaAdvances       != null ? paidViaAdvances       : BigDecimal.ZERO)
                                  .add(paidViaDirectPayments != null ? paidViaDirectPayments : BigDecimal.ZERO);
        BigDecimal pendingPaymentValue = totalBillValue.subtract(paidBillValue).max(BigDecimal.ZERO);

        // Total employee cost & expense management for this project
        BigDecimal totalEmployeeExpenses = BigDecimal.ZERO;
        if (expenseBlock != null && expenseBlock.getApprovedExpenses() != null) {
            totalEmployeeExpenses = expenseBlock.getApprovedExpenses();
        }

        // Total cost = procurement (all bills, matching Reports) + employee/expense cost
        BigDecimal totalProcurementCost = totalBillValue;  // total billed by vendors (matches Reports)
        BigDecimal totalCost = totalProcurementCost.add(totalEmployeeExpenses);

        BigDecimal amountSpent = totalCost; // used for budget utilization (gross — before site return credit)

        Double budgetUtilizationPercent = totalProjectValue.compareTo(BigDecimal.ZERO) > 0
            ? totalCost.multiply(new BigDecimal("100"))
                .divide(totalProjectValue, 2, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        // ── GST Calculation (reuses live invoice/bill lists fetched above) ────
        // Invoice GST collected from client — computed from line items
        BigDecimal invoiceGSTCollected = invoices.stream().flatMap(inv ->
            invoiceItemRepository.findByInvoiceId(inv.getId()).stream()
        ).map(it -> {
            BigDecimal base = safe(it.getQuantity()).multiply(safe(it.getUnitPrice()));
            return base.multiply(safe(it.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Procurement GST paid to vendors — computed from bill line items
        BigDecimal procurementGSTPaid = bills.stream().flatMap(b ->
            billItemRepository.findByBillId(b.getId()).stream()
        ).map(bi -> {
            BigDecimal base = safe(bi.getQuantity()).multiply(safe(bi.getUnitPrice()));
            return base.multiply(safe(bi.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Net GST liability = GST collected from client − GST paid to vendors
        // If positive: net GST payable to govt; if negative: eligible for ITC
        BigDecimal netGST = invoiceGSTCollected.subtract(procurementGSTPaid);

        // ── Profit Calculation ─────────────────────────────────────────────────
        // Profit formula (cash-received basis):
        //   Receipts received from clients − Bills paid to vendors − Approved Expenses − Net GST = Net Profit
        // Using actual cash received (paidInvoiceValue from receipts table) instead of invoices raised,
        // and actual bills paid (paidBillValue) instead of total bills received.
        // Net Profit is only meaningful when the project is COMPLETED.
        BigDecimal totalInvoiceExclGST = totalInvoiceValue.subtract(invoiceGSTCollected);
        BigDecimal totalBillExclGST    = totalBillValue.subtract(procurementGSTPaid);

        // Net GST on cash basis: proportional GST on amount actually received vs total invoiced
        // netGST = invoiceGSTCollected − procurementGSTPaid
        // Always subtract the full netGST — if positive it's a liability (reduces profit),
        // if negative it's an ITC benefit (increases profit via the negative subtraction).
        // ── Inward Recovery: items returned from site to warehouse ────────────
        // OUTWARD txns already created warehouse bills (included in paidBillValue via bills table).
        // INWARD txns (site → warehouse) represent material coming back — the project
        // gets credit for that value since it was already charged via an outward warehouse bill.
        // ── Inward Recovery: SITE RETURNS only (items sent back from site to warehouse) ─
        // Only INWARD txns with poId IS NULL are site returns.
        // INWARD txns with a poId are vendor/PO deliveries into the warehouse — they must
        // NOT be counted here, otherwise vendor replenishments falsely inflate the credit.
        // Unit cost: prefer the cost stored on the transaction itself (at time of original
        // OUTWARD); fall back to current item unit cost only if the txn value is zero.
        BigDecimal inwardRecoveryValue = BigDecimal.ZERO;
        try {
            List<com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity> siteReturnTxns =
                invTransactionRepository.findByProjectIdAndTypeAndPoIdIsNull(projectUniqueId, "INWARD");
            for (com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity t : siteReturnTxns) {
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                // Use unit_cost stored on the transaction (cost at time of issue)
                BigDecimal uc = t.getUnitCost() != null && t.getUnitCost().compareTo(BigDecimal.ZERO) > 0
                    ? t.getUnitCost()
                    : (t.getInventoryItemId() != null
                        ? itemRepository.findById(t.getInventoryItemId())
                            .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                            .orElse(BigDecimal.ZERO)
                        : BigDecimal.ZERO);
                inwardRecoveryValue = inwardRecoveryValue.add(qty.multiply(uc));
            }
        } catch (Exception e) {
            log.warn("Could not compute inward recovery for {}: {}", projectUniqueId, e.getMessage());
        }

        BigDecimal projectedProfit = paidInvoiceValue.subtract(paidBillValue)
                                                      .subtract(totalEmployeeExpenses)
                                                      .subtract(netGST)
                                                      .add(inwardRecoveryValue); // credit returned material
        // Pass raw profit — negative means loss; frontend shows "In Loss" in red (only when COMPLETED)
        BigDecimal netProfitDisplay = projectedProfit;

        Double profitMargin = paidInvoiceValue.compareTo(BigDecimal.ZERO) > 0
            ? netProfitDisplay.multiply(new BigDecimal("100"))
                .divide(paidInvoiceValue, 2, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        // ── Cash Position = Cash Inflow − Cash Outflow ─────────────────────────
        // Cash Inflow  = amount actually received from clients
        // Cash Outflow = paid to vendors + approved expenses + net GST liability
        BigDecimal cashOutflowTotal = paidBillValue
                .add(totalEmployeeExpenses)
                .add(netGST.compareTo(BigDecimal.ZERO) > 0 ? netGST : BigDecimal.ZERO);
        BigDecimal cashInHand = paidInvoiceValue.subtract(cashOutflowTotal);
        BigDecimal cashDeficit = BigDecimal.ZERO;
        if (cashInHand.compareTo(BigDecimal.ZERO) < 0) {
            cashDeficit = cashInHand.abs();
            cashInHand  = BigDecimal.ZERO;
        }

        Double billingPercentage = totalInvoiceValue.compareTo(BigDecimal.ZERO) > 0
            ? paidInvoiceValue.multiply(new BigDecimal("100"))
                .divide(totalInvoiceValue, 2, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        Double paymentPercentage = totalBillValue.compareTo(BigDecimal.ZERO) > 0
            ? paidBillValue.multiply(new BigDecimal("100"))
                .divide(totalBillValue, 2, RoundingMode.HALF_UP).doubleValue()
            : 0.0;

        try {
            project.setBudgetUtilized(totalCost);
            project.setBudgetUtilizationPercent(new BigDecimal(budgetUtilizationPercent));
            project.setProjectedProfit(netProfitDisplay);
            project.setProfitMarginPercent(new BigDecimal(profitMargin));
            project.setStatsCalculatedAt(LocalDateTime.now());
            projectRepository.save(project);
        } catch (Exception e) {
            log.warn("Could not update project stats", e);
        }

        // Net amount spent = paid to vendors minus materials returned from site.
        // inwardRecoveryValue is subtracted because those items were already billed
        // when they left the warehouse (OUTWARD auto-bill), and returning them credits
        // that cost back to the project.
        BigDecimal netSpent = paidBillValue.subtract(inwardRecoveryValue).max(BigDecimal.ZERO);

        return FinancialData.builder()
            .totalProjectValue(totalProjectValue)
            .totalSpent(netSpent)                   // Net paid to vendors after site returns
            .inwardRecoveryValue(inwardRecoveryValue) // Credit for materials returned from site
            .totalCommitted(project.getTotalPoValue())
            .remaining(totalInvoiceValue.subtract(totalCost))  // invoiced minus total cost
            .amountToBeReceived(totalInvoiceValue)
            .amountReceived(paidInvoiceValue)
            .advanceAmount(advanceAmount != null ? advanceAmount : BigDecimal.ZERO)
            .invoicePaymentAmount(invoicePaymentAmount != null ? invoicePaymentAmount : BigDecimal.ZERO)
            .pendingReceipts(totalInvoiceValue.subtract(paidInvoiceValue).max(BigDecimal.ZERO))
            .billingPercentage(billingPercentage)
            .totalPayable(totalBillValue)
            .amountPaid(paidBillValue)
            .pendingPayments(pendingPaymentValue.max(BigDecimal.ZERO))
            .paymentPercentage(paymentPercentage)
            .projectedProfit(netProfitDisplay)   // raw value; negative = loss; only shown when COMPLETED
            .profitMargin(profitMargin)
            .isCompleted(isCompleted)
            .totalEmployeeExpenses(totalEmployeeExpenses)
            .invoiceGSTCollected(invoiceGSTCollected)
            .procurementGSTPaid(procurementGSTPaid)
            .netGST(netGST)
            .amountToBeReceivedExclGST(totalInvoiceExclGST)
            .totalPayableExclGST(totalBillExclGST)
            .budgetUtilized(totalCost)
            .budgetUtilizationPercent(budgetUtilizationPercent)
            .cashInHand(cashInHand)
            .cashDeficit(cashDeficit)
            .burnRate(totalInvoiceValue.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalInvoiceValue, 2, RoundingMode.HALF_UP).doubleValue() : 0.0)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Procurement Data (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private ProcurementData buildProcurementData(ProjectEntity project, String projectUniqueId) {
        List<Object[]> poStatusCounts = purchaseOrderRepository.countByProjectIdAndGroupByStatus(projectUniqueId);
        Map<String, Long> poStatusMap = poStatusCounts.stream()
            .collect(Collectors.toMap(arr -> (String) arr[0], arr -> ((Number) arr[1]).longValue()));

        List<Object[]> quotationStatusCounts = quotationRepository.countByProjectIdAndGroupByStatus(projectUniqueId);
        Map<String, Long> quotationStatusMap = quotationStatusCounts.stream()
            .collect(Collectors.toMap(arr -> (String) arr[0], arr -> ((Number) arr[1]).longValue()));

        List<Object[]> categorySpending = purchaseOrderRepository.sumTotalValueByProjectIdGroupByCategory(projectUniqueId);
        Integer totalItemsOrdered   = purchaseOrderRepository.sumTotalItemsOrderedByProjectId(projectUniqueId);
        Integer totalItemsDelivered = purchaseOrderRepository.sumTotalItemsDeliveredByProjectId(projectUniqueId);
        Double deliveryRate = totalItemsOrdered != null && totalItemsOrdered > 0
            ? (totalItemsDelivered != null ? totalItemsDelivered.doubleValue() : 0.0) / totalItemsOrdered * 100 : 0.0;

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

    // ─────────────────────────────────────────────────────────────────────────
    // Recent Activities (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
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

        activities.addAll(getRecentPayments(projectUniqueId));
        activities.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        return activities.stream().limit(10).collect(Collectors.toList());
    }

    private List<VendorSummaryDTO> getTopVendors(String projectUniqueId) {
        return vendorRepository.findTop5ByProjectIdOrderByTotalPurchaseValueDesc(projectUniqueId).stream()
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
            LocalDate start = month.atDay(1);
            LocalDate end   = month.atEndOfMonth();
            BigDecimal spending = purchaseOrderRepository.sumTotalValueByProjectIdAndDateRange(
                projectUniqueId, start.atStartOfDay(), end.atTime(23, 59, 59));
            Long orders = purchaseOrderRepository.countByProjectIdAndDateRange(
                projectUniqueId, start.atStartOfDay(), end.atTime(23, 59, 59));
            BigDecimal avg = orders > 0 && spending != null
                ? spending.divide(new BigDecimal(orders), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            trend.add(SpendingTrendDTO.builder()
                .month(month.getMonth().toString().substring(0, 3) + " " + String.valueOf(month.getYear()).substring(2))
                .spending(spending != null ? spending : BigDecimal.ZERO)
                .orders(orders.intValue())
                .avgOrderValue(avg)
                .build());
        }
        return trend;
    }

    private List<TimelineMilestoneDTO> buildProjectTimeline(ProjectEntity project, String projectUniqueId) {
        List<TimelineMilestoneDTO> timeline = new ArrayList<>();

        if (project.getStartDate() != null) {
            timeline.add(TimelineMilestoneDTO.builder()
                .date(project.getStartDate()).title("Project Kickoff")
                .description(project.getProjectName() + " started")
                .type("milestone").status("completed").build());
        }

        try {
            purchaseOrderRepository.findByProjectIdAndStatus(projectUniqueId, "Delivered")
                .forEach(po -> {
                    LocalDate d = po.getExpectedDelivery() != null ? po.getExpectedDelivery().toLocalDate()
                        : po.getOrderDate() != null ? po.getOrderDate().toLocalDate() : null;
                    timeline.add(TimelineMilestoneDTO.builder().date(d)
                        .title("PO Delivered").description("PO " + po.getPoNo() + " delivered")
                        .type("po_delivered").status("completed").amount(po.getTotalValue())
                        .reference(po.getPoNo()).build());
                });
        } catch (Exception e) { log.error("Error fetching POs for timeline", e); }

        try {
            billRepository.findByProjectIdAndStatusNot(projectUniqueId, "Pending").forEach(bill -> {
                String st = "Paid".equals(bill.getStatus()) ? "completed" : "in-progress";
                timeline.add(TimelineMilestoneDTO.builder().date(bill.getBillDate())
                    .title("Paid".equals(bill.getStatus()) ? "Bill Paid" : "Bill Received")
                    .description("Bill " + bill.getBillNo() + " - " + bill.getStatus())
                    .type("Paid".equals(bill.getStatus()) ? "bill_paid" : "bill_received")
                    .status(st).amount(bill.getTotalAmount()).reference(bill.getBillNo()).build());
            });
        } catch (Exception e) { log.error("Error fetching bills for timeline", e); }

        try {
            invoiceRepository.findByProjectIdAndDeletedAtIsNull(projectUniqueId).forEach(inv -> {
                String st = "Paid".equals(inv.getStatus()) ? "completed" : "in-progress";
                timeline.add(TimelineMilestoneDTO.builder().date(inv.getInvoiceDate())
                    .title("Paid".equals(inv.getStatus()) ? "Invoice Paid" : "Invoice Raised")
                    .description("Invoice " + inv.getInvoiceNo() + " - " + inv.getStatus())
                    .type("Paid".equals(inv.getStatus()) ? "invoice_paid" : "invoice_raised")
                    .status(st).amount(inv.getTotalAmount()).reference(inv.getInvoiceNo()).build());
            });
        } catch (Exception e) { log.error("Error fetching invoices for timeline", e); }

        if (project.getEndDate() != null) {
            LocalDate now = LocalDate.now();
            LocalDate end = project.getEndDate();
            String st = now.isAfter(end) ? "overdue" : now.isAfter(end.minusDays(30)) ? "in-progress" : "upcoming";
            timeline.add(TimelineMilestoneDTO.builder().date(end)
                .title("Project Completion").description("Target completion date")
                .type("milestone").status(st).build());
        }

        timeline.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return a.getDate().compareTo(b.getDate());
        });
        return timeline;
    }

    private List<ChartDataDTO> getPaymentMethodDistribution(String projectUniqueId) {
        try {
            return paymentHistoryRepository.getPaymentMethodDistributionByProject(projectUniqueId).stream()
                .map(arr -> ChartDataDTO.builder()
                    .name((String) arr[0]).value((BigDecimal) arr[1])
                    .count(((Number) arr[2]).intValue()).build())
                .collect(Collectors.toList());
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private List<PaymentTrendDTO> getPaymentTimeline(String projectUniqueId) {
        try {
            return paymentHistoryRepository.getMonthlyPaymentsByProject(projectUniqueId).stream()
                .map(arr -> PaymentTrendDTO.builder()
                    .month((String) arr[0]).totalAmount((BigDecimal) arr[1])
                    .paymentCount(((Number) arr[2]).intValue()).build())
                .collect(Collectors.toList());
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private List<ActivityDTO> getRecentPayments(String projectUniqueId) {
        try {
            return paymentHistoryRepository.findTop10ByProjectIdOrderByPaymentDateDesc(projectUniqueId).stream()
                .map(p -> ActivityDTO.builder()
                    .type("Payment Received").action("Payment via " + p.getPaymentMethod())
                    .status("Completed").amount(p.getAmount()).date(p.getPaymentDate())
                    .color("#22c55e").reference(p.getTransactionReference()).build())
                .collect(Collectors.toList());
        } catch (Exception e) { return Collections.emptyList(); }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String getProjectManager(ProjectEntity project) {
        return project.getAssignedTo() != null ? "Project Manager" : "Not Assigned";
    }

    private List<ChartDataDTO> buildChartData(Map<String, Long> dataMap) {
        return dataMap.entrySet().stream()
            .map(e -> ChartDataDTO.builder().name(e.getKey()).value(e.getValue().intValue()).build())
            .collect(Collectors.toList());
    }

    private List<ChartDataDTO> buildCategoryChartData(List<Object[]> data) {
        return data.stream()
            .map(arr -> ChartDataDTO.builder().name((String) arr[0]).value((BigDecimal) arr[1]).build())
            .limit(5).collect(Collectors.toList());
    }

    private String getStatusColor(String status) {
        return Map.of("Draft","#94a3b8","Approved","#22c55e","Delivered","#22c55e",
            "Cancelled","#ef4444","Pending","#f59e0b").getOrDefault(status, "#94a3b8");
    }

    private BigDecimal safe(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    // ── Warehouse Issuance (OUTWARD transactions for this project) ────────────
    private WarehouseIssuanceData buildWarehouseIssuanceData(String projectId) {
        try {
            List<com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity> outwardTxns =
                invTransactionRepository.findByProjectIdAndType(projectId, "OUTWARD");

            List<com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity> inwardTxns =
                invTransactionRepository.findByProjectIdAndType(projectId, "INWARD");

            // Count warehouse bills for this project
            long warehouseBillCount = 0;
            try {
                warehouseBillCount = billRepository.findAll().stream()
                    .filter(b -> "WAREHOUSE".equalsIgnoreCase(b.getSourceType())
                        && projectId.equals(b.getProjectId())
                        && b.getDeletedAt() == null)
                    .count();
            } catch (Exception ignored) {}

            BigDecimal totalQty   = BigDecimal.ZERO;
            BigDecimal totalValue = BigDecimal.ZERO;
            List<WarehouseIssuanceData.IssuanceLine> lines = new ArrayList<>();

            Map<Long, String> whNames = new HashMap<>();
            for (com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity t : outwardTxns) {
                if (t.getNotes() != null && t.getNotes().startsWith("VOIDED")) continue;
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                totalQty = totalQty.add(qty);

                // Try to get unit cost from inventory item
                BigDecimal unitCost = BigDecimal.ZERO;
                if (t.getInventoryItemId() != null) {
                    unitCost = itemRepository.findById(t.getInventoryItemId())
                        .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                        .orElse(BigDecimal.ZERO);
                }
                BigDecimal lineVal = qty.multiply(unitCost);
                totalValue = totalValue.add(lineVal);

                String whName = whNames.computeIfAbsent(t.getWarehouseId(), id ->
                    id != null ? warehouseRepository.findById(id).map(w -> w.getName()).orElse("Warehouse") : "—");

                lines.add(WarehouseIssuanceData.IssuanceLine.builder()
                    .itemCode(t.getItemCode())
                    .itemName(t.getItemName())
                    .unit(t.getUnit())
                    .qtyIssued(qty)
                    .unitCost(unitCost)
                    .lineValue(lineVal)
                    .warehouseName(whName)
                    .txnNo(t.getTxnNo())
                    .txnDate(t.getTransactionDate() != null ? t.getTransactionDate().toString() : null)
                    .build());
            }

            // Inward: SITE RETURNS only (poId IS NULL).
            // Vendor/PO deliveries (poId set) are NOT site returns — exclude them.
            List<com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity> siteReturnTxns =
                invTransactionRepository.findByProjectIdAndTypeAndPoIdIsNull(projectId, "INWARD");

            BigDecimal totalReturnQty   = BigDecimal.ZERO;
            BigDecimal totalReturnValue = BigDecimal.ZERO;
            for (com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity t : siteReturnTxns) {
                if (t.getNotes() != null && t.getNotes().startsWith("VOIDED")) continue;
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                totalReturnQty = totalReturnQty.add(qty);
                BigDecimal uc = t.getUnitCost() != null && t.getUnitCost().compareTo(BigDecimal.ZERO) > 0
                    ? t.getUnitCost()
                    : (t.getInventoryItemId() != null
                        ? itemRepository.findById(t.getInventoryItemId())
                            .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                            .orElse(BigDecimal.ZERO)
                        : BigDecimal.ZERO);
                totalReturnValue = totalReturnValue.add(qty.multiply(uc));
            }

            return WarehouseIssuanceData.builder()
                .totalItemsIssued(outwardTxns.size())
                .totalQtyIssued(totalQty)
                .totalIssuanceValue(totalValue)
                .totalItemsReturned(siteReturnTxns.size())
                .totalReturnValue(totalReturnValue)
                .warehouseBillCount((int) warehouseBillCount)
                .issuanceLines(lines)
                .build();
        } catch (Exception e) {
            log.warn("Could not build warehouse issuance data for project {}: {}", projectId, e.getMessage());
            return WarehouseIssuanceData.builder()
                .totalItemsIssued(0).totalQtyIssued(BigDecimal.ZERO)
                .totalIssuanceValue(BigDecimal.ZERO).totalItemsReturned(0)
                .totalReturnValue(BigDecimal.ZERO).warehouseBillCount(0)
                .issuanceLines(new ArrayList<>()).build();
        }
    }

    // ── Site Return (INWARD transactions for this project — items from site) ───
    private SiteReturnData buildSiteReturnData(String projectId) {
        try {
            // Use poId IS NULL to get only genuine site returns, not vendor PO receipts
            List<com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity> inwardTxns =
                invTransactionRepository.findByProjectIdAndTypeAndPoIdIsNull(projectId, "INWARD");

            BigDecimal totalQty = BigDecimal.ZERO;
            List<SiteReturnData.SiteReturnLine> lines = new ArrayList<>();

            Map<Long, String> whNames = new HashMap<>();
            for (com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity t : inwardTxns) {
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                totalQty = totalQty.add(qty);

                String whName = whNames.computeIfAbsent(t.getWarehouseId(), id ->
                    id != null ? warehouseRepository.findById(id).map(w -> w.getName()).orElse("Warehouse") : "—");

                lines.add(SiteReturnData.SiteReturnLine.builder()
                    .itemCode(t.getItemCode())
                    .itemName(t.getItemName())
                    .unit(t.getUnit())
                    .qty(qty)
                    .warehouseName(whName)
                    .txnNo(t.getTxnNo())
                    .txnDate(t.getTransactionDate() != null ? t.getTransactionDate().toString() : null)
                    .build());
            }

            return SiteReturnData.builder()
                .totalReturns(inwardTxns.size())
                .totalQtyReturned(totalQty)
                .lines(lines)
                .build();
        } catch (Exception e) {
            log.warn("Could not build site return data for project {}: {}", projectId, e.getMessage());
            return SiteReturnData.builder().totalReturns(0).totalQtyReturned(BigDecimal.ZERO).lines(new ArrayList<>()).build();
        }
    }
}