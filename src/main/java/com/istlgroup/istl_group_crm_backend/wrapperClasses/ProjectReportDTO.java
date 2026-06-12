package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProjectReportDTO {

    // ── 1. Project Overview ──────────────────────────────────────────────────
    private ProjectOverview overview;

    // ── 2. Billing / Order Book ──────────────────────────────────────────────
    private BillingStatus billing;

    // ── 3. Procurement / Purchase Orders ────────────────────────────────────
    private ProcurementStatus procurement;

    // ── 4. Profitability ─────────────────────────────────────────────────────
    private Profitability profitability;

    // ── 5. Warehouse Issuance & Site Returns ─────────────────────────────────
    private WarehouseData warehouse;

    // ── Meta ─────────────────────────────────────────────────────────────────
    private String generatedAt;

    // ════════════════════════════════════════════════════════════════════════
    // NESTED: Project Overview
    // ════════════════════════════════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProjectOverview {
        private String projectId;
        private String projectName;
        private String location;
        private String status;
        private String groupName;
        private String subGroupName;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal budget;
        private BigDecimal progressPercentage;
        // Financial snapshot
        private BigDecimal totalContractValue;   // project budget (agreed contract amount)
        private BigDecimal totalInvoiced;        // total invoices raised to client
        private BigDecimal totalReceived;        // total receipts from client
        private BigDecimal totalProcurement;     // total procurement bills (incl. GST)
        private BigDecimal totalPaid;            // total paid to vendors
        private BigDecimal projectedProfit;      // invoiced − procurement − approvedExpenses − netGST
        private Double     profitMarginPercent;
    }

    // ════════════════════════════════════════════════════════════════════════
    // NESTED: Billing Status
    // ════════════════════════════════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BillingStatus {
        private BigDecimal totalInvoiced;
        private BigDecimal totalReceived;
        private BigDecimal totalPending;
        private BigDecimal totalAdvances;      // advance receipts
        private List<InvoiceRow> invoices;
        private List<ReceiptRow> receipts;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InvoiceRow {
        private String  invoiceNo;
        private String  invoiceDate;
        private String  dueDate;
        private String  customerName;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal balanceAmount;
        private String  status;
        private String  paymentMethod;  // from most recent receipt
        private String  receiptNo;      // linked receipt no
        private BigDecimal taxAmount;   // sum of item tax
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReceiptRow {
        private String     receiptNo;
        private String     receiptDate;
        private String     receiptType;
        private BigDecimal amount;
        private BigDecimal appliedAmount;
        private BigDecimal unappliedAmount;
        private String     paymentMethod;
        private String     transactionReference;
        private String     linkedInvoiceNo;
    }

    // ════════════════════════════════════════════════════════════════════════
    // NESTED: Procurement Status
    // ════════════════════════════════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProcurementStatus {
        private BigDecimal totalPOValue;
        private BigDecimal totalBilled;
        private BigDecimal totalPaid;
        private BigDecimal totalBalance;
        private List<PORow>   purchaseOrders;
        private List<BillRow> bills;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PORow {
        private String     poNo;
        private String     orderDate;
        private String     vendorName;
        private BigDecimal totalValue;
        private String     paymentStatus;
        private String     status;
        private int        totalItems;
        private int        deliveredItems;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BillRow {
        private String     billNo;
        private String     billDate;
        private String     dueDate;
        private String     vendorName;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal balanceAmount;
        private String     status;
        private String     linkedPONo;
        private BigDecimal taxAmount;
    }

    // ════════════════════════════════════════════════════════════════════════
    // NESTED: Profitability
    // ════════════════════════════════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Profitability {
        private BigDecimal totalRevenue;            // sum of invoices incl. GST
        private BigDecimal totalRevenueExclGST;     // invoiced excl. GST (basis for profit)
        private BigDecimal totalProcurement;        // sum of bills incl. GST
        private BigDecimal totalProcurementExclGST; // procurement excl. GST
        private BigDecimal projectExpenses;         // approved expenses only
        private BigDecimal invoiceGSTAmount;        // GST collected from client
        private BigDecimal poGSTAmount;             // GST paid to vendors (ITC)
        private BigDecimal additionalGST;           // net GST = invoiceGST - poGST (always deducted)
        private BigDecimal amountReceived;          // actual cash received from client (receipts)
        private BigDecimal paidBillValue;           // actual payments made to vendors
        private BigDecimal grossProfit;             // revenueExclGST - procurementExclGST (reference)
        private BigDecimal netProfit;               // received - paidBills - expenses - netGST + inwardRecovery (raw, can be negative = loss)
        private Boolean    isCompleted;             // true if project status is COMPLETED
        private Double     grossMarginPercent;
        private Double     netMarginPercent;
        /** Value of items returned from site to warehouse (INWARD txns).
         *  This is ADDED back to profit because the outward warehouse bill already
         *  deducted the cost; when material comes back the deduction is partially reversed. */
        private BigDecimal inwardRecoveryValue;
        private List<ExpenseRow> expenses;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpenseRow {
        private String     expenseCode;
        private String     tripDate;
        private String     category;
        private BigDecimal amount;
        private String     paidBy;
        private String     status;
    }

    // ── Warehouse Issuance & Site Returns ─────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WarehouseData {
        // Outward summary
        private Integer    totalOutwardTxns;
        private BigDecimal totalQtyIssued;
        private BigDecimal totalIssuanceValue;
        private Integer    warehouseBillCount;
        // Inward summary
        private Integer    totalInwardTxns;
        private BigDecimal totalQtyReturned;
        private BigDecimal totalReturnValue;   // value credited back to project
        // Transactions
        private List<WarehouseTxnRow> outwardTransactions;
        private List<WarehouseTxnRow> inwardTransactions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WarehouseTxnRow {
        private String     txnNo;
        private String     txnDate;
        private String     type;       // OUTWARD | INWARD
        private String     itemCode;
        private String     itemName;
        private String     unit;
        private BigDecimal qty;
        private BigDecimal unitCost;
        private BigDecimal lineValue;
        private String     warehouseName;
        private String     refNo;
        private String     notes;
    }
}