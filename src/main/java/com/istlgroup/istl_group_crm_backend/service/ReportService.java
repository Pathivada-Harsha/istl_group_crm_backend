package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectReportDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ExpenseFilterRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectExpenseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ProjectRepository            projectRepository;
    private final InvoiceRepository            invoiceRepository;
    private final InvoiceItemRepository        invoiceItemRepository;
    private final ReceiptRepository            receiptRepository;
    private final BillPaymentRepository        billPaymentRepository;
    private final VendorAdvanceRepository      vendorAdvanceRepository;
    private final PurchaseOrderRepository      purchaseOrderRepository;
    private final PurchaseOrderItemRepository  poItemRepository;
    private final BillRepository               billRepository;
    private final BillItemRepository           billItemRepository;
    private final VendorRepository             vendorRepository;
    private final ProjectExpenseService        expenseService;
    private final InvTransactionRepository     invTransactionRepository;
    private final WarehouseRepository          warehouseRepository;
    private final InventoryItemRepository      itemRepository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Transactional(readOnly = true)
    public ProjectReportDTO generateReport(String projectId) {
        ProjectEntity project = projectRepository.findByProjectUniqueId(projectId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

        return ProjectReportDTO.builder()
            .overview(buildOverview(project, projectId))
            .billing(buildBilling(projectId))
            .procurement(buildProcurement(projectId))
            .profitability(buildProfitability(project, projectId))
            .warehouse(buildWarehouseData(projectId))
            .generatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")))
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Overview
    // ─────────────────────────────────────────────────────────────────────────
    private ProjectReportDTO.ProjectOverview buildOverview(ProjectEntity p, String projectId) {
        // Contract value = project budget (what was agreed with client)
        BigDecimal contractValue = p.getBudget() != null ? p.getBudget() : BigDecimal.ZERO;

        // Total invoiced = sum of all invoice totals raised to client
        List<InvoiceEntity> invoices = invoiceRepository.findByProjectIdAndDeletedAtIsNull(projectId);
        BigDecimal totalInvoiced = safeSum(invoices.stream().map(InvoiceEntity::getTotalAmount).collect(Collectors.toList()));

        BigDecimal totalReceived = safeSum(
            receiptRepository.findByProjectId(projectId, PageRequest.of(0, 10000))
            .getContent().stream()
            .map(ReceiptEntity::getAmount).collect(Collectors.toList()));

        // Total procurement = sum of all vendor bills (total amount incl. GST)
        List<BillEntity> bills = billRepository.findByProjectIdAndStatusNot(projectId, "Cancelled");
        BigDecimal totalBilled = safeSum(bills.stream().map(BillEntity::getTotalAmount).collect(Collectors.toList()));
        BigDecimal totalPaid   = safeSum(bills.stream().map(BillEntity::getPaidAmount).collect(Collectors.toList()));

        // Invoice GST collected from client (from line items)
        BigDecimal invoiceGST = invoices.stream().flatMap(inv ->
            invoiceItemRepository.findByInvoiceId(inv.getId()).stream()
        ).map(it -> {
            BigDecimal base = nvl(it.getQuantity()).multiply(nvl(it.getUnitPrice()));
            return base.multiply(nvl(it.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Procurement GST paid to vendors (from bill line items)
        BigDecimal procurementGST = bills.stream().flatMap(b ->
            billItemRepository.findByBillId(b.getId()).stream()
        ).map(bi -> {
            BigDecimal base = nvl(bi.getQuantity()).multiply(nvl(bi.getUnitPrice()));
            return base.multiply(nvl(bi.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netGST = invoiceGST.subtract(procurementGST);

        // Approved expenses only (consistent with dashboard)
        BigDecimal approvedExpenses = BigDecimal.ZERO;
        try {
            ExpenseFilterRequest filter = new ExpenseFilterRequest();
            filter.setProjectId(projectId);
            filter.setPage(0);
            filter.setSize(1000);
            approvedExpenses = expenseService.getExpenses(filter).getContent().stream()
                .filter(e -> "Approved".equalsIgnoreCase(e.getStatus()))
                .map(e -> nvl(e.getTotalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception ex) {
            log.warn("Could not fetch expenses for overview {}: {}", projectId, ex.getMessage());
        }

        // Profit = Total Invoiced − Procurement Bills − Approved Expenses − Net GST
        BigDecimal projectedProfit = totalInvoiced.subtract(totalBilled)
                                                   .subtract(approvedExpenses)
                                                   .subtract(netGST);
        double marginPct = totalInvoiced.compareTo(BigDecimal.ZERO) > 0
            ? projectedProfit.divide(totalInvoiced, 4, RoundingMode.HALF_UP)
                             .multiply(BigDecimal.valueOf(100)).doubleValue()
            : 0.0;

        return ProjectReportDTO.ProjectOverview.builder()
            .projectId(p.getProjectUniqueId())
            .projectName(p.getProjectName())
            .location(p.getLocation())
            .status(p.getStatus() != null ? p.getStatus().name() : "UNKNOWN")
            .groupName(p.getGroupId())
            .subGroupName(p.getSubGroupName())
            .startDate(p.getStartDate())
            .endDate(p.getEndDate())
            .budget(p.getBudget())
            .progressPercentage(p.getProgressPercentage())
            .totalContractValue(contractValue)   // ← project budget (contract agreed)
            .totalInvoiced(totalInvoiced)         // ← sum of all invoices raised to client
            .totalReceived(totalReceived)
            .totalProcurement(totalBilled)
            .totalPaid(totalPaid)
            .projectedProfit(projectedProfit)
            .profitMarginPercent(round2(marginPct))
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Billing
    // ─────────────────────────────────────────────────────────────────────────
    private ProjectReportDTO.BillingStatus buildBilling(String projectId) {
        List<InvoiceEntity> invoices = invoiceRepository.findByProjectIdAndDeletedAtIsNull(projectId);

        // Build a map invoiceId → most recent receipt
        Map<Long, ReceiptEntity> receiptByInvoice = new HashMap<>();
        Pageable big = PageRequest.of(0, 10000);
        receiptRepository.findByProjectId(projectId, big)
            .getContent().forEach(r -> {
                if (r.getInvoiceId() != null)
                    receiptByInvoice.merge(r.getInvoiceId(), r, (a, b) ->
                        a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
            });

        List<ProjectReportDTO.InvoiceRow> invoiceRows = invoices.stream().map(inv -> {
            // sum tax from items
            List<InvoiceItemEntity> items = invoiceItemRepository.findByInvoiceId(inv.getId());
            BigDecimal taxAmt = items.stream().map(it -> {
                BigDecimal base = nvl(it.getQuantity()).multiply(nvl(it.getUnitPrice()));
                return base.multiply(nvl(it.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }).reduce(BigDecimal.ZERO, BigDecimal::add);

            ReceiptEntity rec = receiptByInvoice.get(inv.getId());
            return ProjectReportDTO.InvoiceRow.builder()
                .invoiceNo(inv.getInvoiceNo())
                .invoiceDate(inv.getInvoiceDate() != null ? inv.getInvoiceDate().format(FMT) : "")
                .dueDate(inv.getDueDate() != null ? inv.getDueDate().format(FMT) : "")
                .customerName(inv.getCustomerCompanyName() != null ? inv.getCustomerCompanyName() : inv.getCustomerName())
                .totalAmount(nvl(inv.getTotalAmount()))
                .paidAmount(nvl(inv.getPaidAmount()))
                .balanceAmount(nvl(inv.getBalanceAmount()))
                .status(inv.getStatus())
                .paymentMethod(rec != null ? rec.getPaymentMethod() : "")
                .receiptNo(rec != null ? rec.getReceiptNo() : "")
                .taxAmount(taxAmt)
                .build();
        }).collect(Collectors.toList());

        // All receipts
        List<ProjectReportDTO.ReceiptRow> receiptRows = receiptRepository
            .findByProjectId(projectId, big)
            .getContent().stream().map(r -> {
                // find linked invoice no
                String invNo = "";
                if (r.getInvoiceId() != null) {
                    invNo = invoices.stream().filter(i -> i.getId().equals(r.getInvoiceId()))
                        .findFirst().map(InvoiceEntity::getInvoiceNo).orElse("");
                }
                return ProjectReportDTO.ReceiptRow.builder()
                    .receiptNo(r.getReceiptNo())
                    .receiptDate(r.getReceiptDate() != null ? r.getReceiptDate().format(FMT) : "")
                    .receiptType(r.getReceiptType())
                    .amount(nvl(r.getAmount()))
                    .appliedAmount(nvl(r.getAppliedAmount()))
                    .unappliedAmount(nvl(r.getUnappliedAmount()))
                    .paymentMethod(r.getPaymentMethod())
                    .transactionReference(r.getTransactionReference())
                    .linkedInvoiceNo(invNo)
                    .build();
            }).collect(Collectors.toList());

        BigDecimal totalInvoiced  = invoiceRows.stream().map(ProjectReportDTO.InvoiceRow::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReceived  = receiptRows.stream().map(ProjectReportDTO.ReceiptRow::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAdvances  = receiptRows.stream().filter(r -> "ADVANCE".equals(r.getReceiptType())).map(ProjectReportDTO.ReceiptRow::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProjectReportDTO.BillingStatus.builder()
            .totalInvoiced(totalInvoiced)
            .totalReceived(totalReceived)
            .totalPending(totalInvoiced.subtract(totalReceived).max(BigDecimal.ZERO))
            .totalAdvances(totalAdvances)
            .invoices(invoiceRows)
            .receipts(receiptRows)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Procurement
    // ─────────────────────────────────────────────────────────────────────────
    private ProjectReportDTO.ProcurementStatus buildProcurement(String projectId) {
        List<PurchaseOrderEntity> pos = purchaseOrderRepository.findByProjectId(projectId);

        // vendor name map
        Map<Long, String> vendorNames = new HashMap<>();
        pos.stream().filter(p -> p.getVendorId() != null).forEach(p ->
            vendorNames.computeIfAbsent(p.getVendorId(), id ->
                vendorRepository.findById(id).map(VendorEntity::getName).orElse("Vendor #" + id)));

        List<ProjectReportDTO.PORow> poRows = pos.stream()
            .filter(p -> !"Cancelled".equals(p.getStatus()))
            .map(p -> ProjectReportDTO.PORow.builder()
                .poNo(p.getPoNo())
                .orderDate(p.getOrderDate() != null ? p.getOrderDate().toLocalDate().format(FMT) : "")
                .vendorName(p.getVendorName() != null ? p.getVendorName() : vendorNames.getOrDefault(p.getVendorId(), "—"))
                .totalValue(nvl(p.getTotalValue()))
                .paymentStatus(p.getPaymentStatus())
                .status(p.getStatus())
                .totalItems(p.getTotalItemsOrdered() != null ? p.getTotalItemsOrdered() : 0)
                .deliveredItems(p.getTotalItemsDelivered() != null ? p.getTotalItemsDelivered() : 0)
                .build())
            .collect(Collectors.toList());

        // Bills
        List<BillEntity> bills = billRepository.findByProjectIdAndStatusNot(projectId, "Cancelled");
        Map<Long, String> poNoByPoId = pos.stream().collect(
            Collectors.toMap(PurchaseOrderEntity::getId, PurchaseOrderEntity::getPoNo, (a,b)->a));

        List<ProjectReportDTO.BillRow> billRows = bills.stream().map(b -> {
            List<BillItemEntity> bItems = billItemRepository.findByBillId(b.getId());
            BigDecimal taxAmt = bItems.stream().map(bi -> {
                BigDecimal base = nvl(bi.getQuantity()).multiply(nvl(bi.getUnitPrice()));
                return base.multiply(nvl(bi.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }).reduce(BigDecimal.ZERO, BigDecimal::add);

            String vendorName = b.getVendorId() != null
                ? vendorNames.computeIfAbsent(b.getVendorId(), id ->
                    vendorRepository.findById(id).map(VendorEntity::getName).orElse("Vendor #" + id))
                : "—";

            return ProjectReportDTO.BillRow.builder()
                .billNo(b.getBillNo())
                .billDate(b.getBillDate() != null ? b.getBillDate().format(FMT) : "")
                .dueDate(b.getDueDate() != null ? b.getDueDate().format(FMT) : "")
                .vendorName(vendorName)
                .totalAmount(nvl(b.getTotalAmount()))
                .paidAmount(nvl(b.getPaidAmount()))
                .balanceAmount(nvl(b.getBalanceAmount()))
                .status(b.getStatus())
                .linkedPONo(b.getPoId() != null ? poNoByPoId.getOrDefault(b.getPoId(), "") : "")
                .taxAmount(taxAmt)
                .build();
        }).collect(Collectors.toList());

        BigDecimal totalPOValue  = poRows.stream().map(ProjectReportDTO.PORow::getTotalValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBilled   = billRows.stream().map(ProjectReportDTO.BillRow::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaid     = billRows.stream().map(ProjectReportDTO.BillRow::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBalance  = billRows.stream().map(ProjectReportDTO.BillRow::getBalanceAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProjectReportDTO.ProcurementStatus.builder()
            .totalPOValue(totalPOValue)
            .totalBilled(totalBilled)
            .totalPaid(totalPaid)
            .totalBalance(totalBalance)
            .purchaseOrders(poRows)
            .bills(billRows)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Profitability
    // ─────────────────────────────────────────────────────────────────────────
    private ProjectReportDTO.Profitability buildProfitability(ProjectEntity project, String projectId) {
        final ProjectEntity p = project;
        // Revenue = Total Invoiced to client (sum of all invoice totals incl. GST)
        List<InvoiceEntity> invoices = invoiceRepository.findByProjectIdAndDeletedAtIsNull(projectId);
        BigDecimal totalRevenue = safeSum(invoices.stream().map(InvoiceEntity::getTotalAmount).collect(Collectors.toList()));

        // Invoice GST
        BigDecimal invoiceGST = invoices.stream().flatMap(inv ->
            invoiceItemRepository.findByInvoiceId(inv.getId()).stream()
        ).map(it -> {
            BigDecimal base = nvl(it.getQuantity()).multiply(nvl(it.getUnitPrice()));
            return base.multiply(nvl(it.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Procurement = sum of bills
        List<BillEntity> bills = billRepository.findByProjectIdAndStatusNot(projectId, "Cancelled");
        BigDecimal totalProcurement = safeSum(bills.stream().map(BillEntity::getTotalAmount).collect(Collectors.toList()));

        // PO GST — derived from bills received (actual supplier invoices), not PO items.
        // PO items can have very large ordered quantities (e.g. 192920 poles) that would
        // give astronomically wrong GST. Bills represent actual amounts charged by vendors
        // and are the correct basis for "GST paid to suppliers".
        List<PurchaseOrderEntity> pos = purchaseOrderRepository.findByProjectId(projectId);
        BigDecimal poGST = bills.stream().flatMap(b ->
            billItemRepository.findByBillId(b.getId()).stream()
        ).map(bi -> {
            BigDecimal base = nvl(bi.getQuantity()).multiply(nvl(bi.getUnitPrice()));
            return base.multiply(nvl(bi.getTaxPercent())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expenses — approved only (consistent with dashboard which uses approvedExpenses)
        BigDecimal projectExpenses = BigDecimal.ZERO;
        List<ProjectReportDTO.ExpenseRow> expenseRows = new ArrayList<>();
        try {
            ExpenseFilterRequest filter = new ExpenseFilterRequest();
            filter.setProjectId(projectId);
            filter.setPage(0);
            filter.setSize(1000);
            List<ProjectExpenseResponse> expenses = expenseService.getExpenses(filter).getContent();
            // Only count approved expenses in profit calculation (pending may not be settled)
            projectExpenses = expenses.stream()
                .filter(e -> "Approved".equalsIgnoreCase(e.getStatus()))
                .map(e -> nvl(e.getTotalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Show all expenses (with status) in the expense detail table
            expenseRows = expenses.stream().map(e -> {
                String cat = e.getExpenseItems() != null && !e.getExpenseItems().isEmpty()
                    ? e.getExpenseItems().stream().map(ProjectExpenseResponse.ExpenseItemResponse::getCategory)
                       .filter(Objects::nonNull).collect(Collectors.joining(", "))
                    : "General";
                return ProjectReportDTO.ExpenseRow.builder()
                    .expenseCode(e.getExpenseCode())
                    .tripDate(e.getTripDate() != null ? e.getTripDate().format(FMT) : "")
                    .category(cat)
                    .amount(nvl(e.getTotalAmount()))
                    .paidBy(e.getPaidByName())
                    .status(e.getStatus())
                    .build();
            }).collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("Could not fetch expenses for {}: {}", projectId, ex.getMessage());
        }

        BigDecimal additionalGST = invoiceGST.subtract(poGST);

        // ── Keep excl-GST values for display/reference ─────────────────────────
        BigDecimal totalRevenueExclGST     = totalRevenue.subtract(invoiceGST);
        BigDecimal totalProcurementExclGST = totalProcurement.subtract(poGST);

        // ── Cash-received basis (matching ProjectDashboardService formula) ──────
        // amountReceived = SUM of all receipts from client (advances + invoice payments)
        BigDecimal amountReceived = receiptRepository.sumReceiptAmountByProjectId(projectId);
        amountReceived = amountReceived != null ? amountReceived : BigDecimal.ZERO;

        // paidBillValue = actual payments made to vendors (same source as ProjectStatsService)
        BigDecimal paidViaAdvances       = vendorAdvanceRepository.sumAdvanceAmountByProjectId(projectId);
        BigDecimal paidViaDirectPayments = billPaymentRepository.sumDirectPaymentAmountByProjectId(projectId);
        BigDecimal paidBillValue = (paidViaAdvances       != null ? paidViaAdvances       : BigDecimal.ZERO)
                                 .add(paidViaDirectPayments != null ? paidViaDirectPayments : BigDecimal.ZERO);

        // ── Inward Recovery: items returned from site to warehouse ────────────
        // OUTWARD transactions already created warehouse bills (included in paidBillValue).
        // When materials come back INWARD, the project gets credit for that value back.
        // Formula: inwardRecovery = SUM(qty × unitCost) for all INWARD txns on this project.
        BigDecimal inwardRecoveryValue = BigDecimal.ZERO;
        try {
            List<InvTransactionEntity> inwardTxns =
                invTransactionRepository.findByProjectIdAndType(projectId, "INWARD");
            for (InvTransactionEntity t : inwardTxns) {
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                BigDecimal uc  = BigDecimal.ZERO;
                if (t.getInventoryItemId() != null) {
                    uc = itemRepository.findById(t.getInventoryItemId())
                        .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                        .orElse(BigDecimal.ZERO);
                }
                inwardRecoveryValue = inwardRecoveryValue.add(qty.multiply(uc));
            }
        } catch (Exception e) {
            log.warn("Could not compute inward recovery for project {}: {}", projectId, e.getMessage());
        }

        // Net Profit = Received − Bills Paid − Expenses − Net GST + Inward Recovery
        // netGST (additionalGST) always subtracted regardless of sign
        BigDecimal netProfitRaw = amountReceived.subtract(paidBillValue)
                                               .subtract(projectExpenses)
                                               .subtract(additionalGST)
                                               .add(inwardRecoveryValue);  // recovery adds back
        // Pass raw profit — negative means loss; frontend shows "In Loss" in red
        BigDecimal netProfit = netProfitRaw;

        // Margin on amount received (cash basis); negative margin = loss
        double netMargin = amountReceived.compareTo(BigDecimal.ZERO) > 0
            ? netProfit.divide(amountReceived, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0;

        // Gross profit kept for display reference (invoiced basis, excl-GST)
        BigDecimal grossProfit = totalRevenueExclGST.subtract(totalProcurementExclGST);
        double grossMargin = totalRevenueExclGST.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.divide(totalRevenueExclGST, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0;

        return ProjectReportDTO.Profitability.builder()
            .totalRevenue(totalRevenue)
            .totalRevenueExclGST(totalRevenueExclGST)
            .totalProcurement(totalProcurement)
            .totalProcurementExclGST(totalProcurementExclGST)
            .projectExpenses(projectExpenses)
            .invoiceGSTAmount(invoiceGST)
            .poGSTAmount(poGST)
            .additionalGST(additionalGST)
            .amountReceived(amountReceived)
            .paidBillValue(paidBillValue)
            .grossProfit(grossProfit)
            .netProfit(netProfit)
            .inwardRecoveryValue(inwardRecoveryValue)
            .isCompleted(p.getStatus() == com.istlgroup.istl_group_crm_backend.entity.ProjectEntity.ProjectStatus.COMPLETED)
            .grossMarginPercent(round2(grossMargin))
            .netMarginPercent(round2(netMargin))
            .expenses(expenseRows)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Warehouse Issuance & Site Returns
    // ─────────────────────────────────────────────────────────────────────────
    private ProjectReportDTO.WarehouseData buildWarehouseData(String projectId) {
        try {
            List<InvTransactionEntity> outwardTxns =
                invTransactionRepository.findByProjectIdAndType(projectId, "OUTWARD");
            List<InvTransactionEntity> inwardTxns =
                invTransactionRepository.findByProjectIdAndType(projectId, "INWARD");

            // Count warehouse bills
            long billCount = billRepository.findAll().stream()
                .filter(b -> "WAREHOUSE".equalsIgnoreCase(b.getSourceType())
                    && projectId.equals(b.getProjectId()) && b.getDeletedAt() == null)
                .count();

            // Build warehouse name cache
            Map<Long, String> whNames = new HashMap<>();
            java.util.function.Function<Long, String> wName = id -> id == null ? "—" :
                whNames.computeIfAbsent(id, i -> warehouseRepository.findById(i)
                    .map(WarehouseEntity::getName).orElse("Warehouse"));

            // Outward transactions
            BigDecimal totalIssuanceValue = BigDecimal.ZERO;
            BigDecimal totalQtyIssued     = BigDecimal.ZERO;
            List<ProjectReportDTO.WarehouseTxnRow> outwardRows = new ArrayList<>();
            for (InvTransactionEntity t : outwardTxns) {
                if (t.getNotes() != null && t.getNotes().startsWith("VOIDED")) continue;
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                BigDecimal uc  = t.getInventoryItemId() != null
                    ? itemRepository.findById(t.getInventoryItemId())
                        .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                        .orElse(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
                BigDecimal lineVal = qty.multiply(uc);
                totalQtyIssued     = totalQtyIssued.add(qty);
                totalIssuanceValue = totalIssuanceValue.add(lineVal);
                outwardRows.add(ProjectReportDTO.WarehouseTxnRow.builder()
                    .txnNo(t.getTxnNo()).txnDate(t.getTransactionDate() != null ? t.getTransactionDate().toString() : null)
                    .type("OUTWARD").itemCode(t.getItemCode()).itemName(t.getItemName()).unit(t.getUnit())
                    .qty(qty).unitCost(uc).lineValue(lineVal).warehouseName(wName.apply(t.getWarehouseId()))
                    .refNo(t.getRefNo()).notes(t.getNotes()).build());
            }

            // Inward transactions
            BigDecimal totalReturnValue = BigDecimal.ZERO;
            BigDecimal totalQtyReturned = BigDecimal.ZERO;
            List<ProjectReportDTO.WarehouseTxnRow> inwardRows = new ArrayList<>();
            for (InvTransactionEntity t : inwardTxns) {
                if (t.getNotes() != null && t.getNotes().startsWith("VOIDED")) continue;
                BigDecimal qty = t.getQty() != null ? t.getQty().abs() : BigDecimal.ZERO;
                BigDecimal uc  = t.getInventoryItemId() != null
                    ? itemRepository.findById(t.getInventoryItemId())
                        .map(i -> i.getUnitCost() != null ? i.getUnitCost() : BigDecimal.ZERO)
                        .orElse(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
                BigDecimal lineVal = qty.multiply(uc);
                totalQtyReturned = totalQtyReturned.add(qty);
                totalReturnValue = totalReturnValue.add(lineVal);
                inwardRows.add(ProjectReportDTO.WarehouseTxnRow.builder()
                    .txnNo(t.getTxnNo()).txnDate(t.getTransactionDate() != null ? t.getTransactionDate().toString() : null)
                    .type("INWARD").itemCode(t.getItemCode()).itemName(t.getItemName()).unit(t.getUnit())
                    .qty(qty).unitCost(uc).lineValue(lineVal).warehouseName(wName.apply(t.getWarehouseId()))
                    .refNo(t.getRefNo()).notes(t.getNotes()).build());
            }

            return ProjectReportDTO.WarehouseData.builder()
                .totalOutwardTxns(outwardRows.size())
                .totalQtyIssued(totalQtyIssued)
                .totalIssuanceValue(totalIssuanceValue)
                .warehouseBillCount((int) billCount)
                .totalInwardTxns(inwardRows.size())
                .totalQtyReturned(totalQtyReturned)
                .totalReturnValue(totalReturnValue)
                .outwardTransactions(outwardRows)
                .inwardTransactions(inwardRows)
                .build();
        } catch (Exception e) {
            log.warn("Could not build warehouse data for project {}: {}", projectId, e.getMessage());
            return ProjectReportDTO.WarehouseData.builder()
                .totalOutwardTxns(0).totalQtyIssued(BigDecimal.ZERO).totalIssuanceValue(BigDecimal.ZERO)
                .warehouseBillCount(0).totalInwardTxns(0).totalQtyReturned(BigDecimal.ZERO)
                .totalReturnValue(BigDecimal.ZERO).outwardTransactions(new ArrayList<>()).inwardTransactions(new ArrayList<>())
                .build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private BigDecimal safeSum(List<BigDecimal> list) {
        return list.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}