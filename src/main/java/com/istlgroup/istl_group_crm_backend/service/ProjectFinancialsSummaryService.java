package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.repo.BillPaymentRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillRepository;
import com.istlgroup.istl_group_crm_backend.repo.InvoiceRepository;
import com.istlgroup.istl_group_crm_backend.repo.ReceiptRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorAdvanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Money-in / money-out per project, for LIST screens and for the client
 * roll-up behind the Customers > Financials tab (ClientFinancialsService).
 *
 * Deliberately computed LIVE from receipts / invoices / bills — exactly like
 * ProjectDashboardService.buildFinancialData — and NOT from the cached
 * projects.paid_invoice_value / paid_bill_value columns, which are only
 * refreshed when ProjectStatsService runs and therefore drift (that is why the
 * dashboard stopped using them too).
 *
 * The difference from the dashboard is batching: five GROUP BY project_id
 * queries cover every project at once, so the list costs a fixed 5 queries
 * instead of 5 per row.
 *
 * Definitions (identical to the dashboard):
 *   billed   = SUM(invoices.total_amount)                    — raised on the customer
 *   received = SUM(receipts.amount) for ADVANCE + INVOICE_PAYMENT — cash in
 *   payable  = SUM(bills.total_amount), non-cancelled        — booked by vendors
 *   spent    = vendor_advances + direct bill_payments        — cash out
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectFinancialsSummaryService {

    private final InvoiceRepository      invoiceRepository;
    private final ReceiptRepository      receiptRepository;
    private final BillRepository         billRepository;
    private final VendorAdvanceRepository vendorAdvanceRepository;
    private final BillPaymentRepository  billPaymentRepository;

    /** One financial snapshot per project, keyed by project_unique_id. */
    public Map<String, Financials> getFinancialsByProject() {
        Map<String, BigDecimal> billed   = toMap(invoiceRepository.sumInvoiceValueGroupedByProject());
        Map<String, BigDecimal> received = toMap(receiptRepository.sumReceiptAmountGroupedByProject());
        Map<String, BigDecimal> payable  = toMap(billRepository.sumBillValueGroupedByProject());
        Map<String, BigDecimal> viaAdv   = toMap(vendorAdvanceRepository.sumAdvanceAmountGroupedByProject());
        Map<String, BigDecimal> viaDirect = toMap(billPaymentRepository.sumDirectPaymentAmountGroupedByProject());

        Map<String, Financials> out = new HashMap<>();
        // Union of every project id that appeared in any of the five roll-ups
        java.util.Set<String> ids = new java.util.HashSet<>();
        ids.addAll(billed.keySet());
        ids.addAll(received.keySet());
        ids.addAll(payable.keySet());
        ids.addAll(viaAdv.keySet());
        ids.addAll(viaDirect.keySet());

        for (String id : ids) {
            BigDecimal b = billed.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal r = received.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal p = payable.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal sa = viaAdv.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal sd = viaDirect.getOrDefault(id, BigDecimal.ZERO);
            out.put(id, new Financials(b, r, p, sa, sd));
        }
        return out;
    }

    /** Snapshot for a project with no financial activity at all. */
    public static Financials empty() {
        return new Financials(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** (project_id, amount) rows → map, skipping null keys and null sums. */
    private static Map<String, BigDecimal> toMap(List<Object[]> rows) {
        Map<String, BigDecimal> m = new HashMap<>();
        if (rows == null) return m;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            String key = String.valueOf(row[0]);
            BigDecimal val = row[1] == null ? BigDecimal.ZERO
                    : (row[1] instanceof BigDecimal ? (BigDecimal) row[1]
                                                    : new BigDecimal(row[1].toString()));
            m.merge(key, val, BigDecimal::add);
        }
        return m;
    }

    /** Immutable per-project money snapshot; the derived figures are computed here. */
    public static class Financials {
        private final BigDecimal billed;
        private final BigDecimal received;
        private final BigDecimal payable;
        private final BigDecimal spent;
        // The two sources `spent` is made of, kept separately so the Client
        // Financials tab can show WHERE the cash went without re-querying.
        // spentViaAdvances + spentViaBillPayments == spent, always.
        private final BigDecimal spentViaAdvances;
        private final BigDecimal spentViaBillPayments;

        Financials(BigDecimal billed, BigDecimal received, BigDecimal payable,
                   BigDecimal spentViaAdvances, BigDecimal spentViaBillPayments) {
            this.billed   = billed;
            this.received = received;
            this.payable  = payable;
            this.spentViaAdvances     = spentViaAdvances;
            this.spentViaBillPayments = spentViaBillPayments;
            this.spent    = spentViaAdvances.add(spentViaBillPayments);
        }

        public BigDecimal getBilled()   { return billed; }
        public BigDecimal getReceived() { return received; }
        public BigDecimal getPayable()  { return payable; }
        public BigDecimal getSpent()    { return spent; }
        public BigDecimal getSpentViaAdvances()     { return spentViaAdvances; }
        public BigDecimal getSpentViaBillPayments() { return spentViaBillPayments; }

        /** Invoiced but not yet collected — clamped, advances can exceed billing. */
        public BigDecimal getPendingReceipts() { return billed.subtract(received).max(BigDecimal.ZERO); }

        /** Vendor bills booked but not yet paid — clamped for the same reason. */
        public BigDecimal getPendingPayments() { return payable.subtract(spent).max(BigDecimal.ZERO); }

        /** Net cash position: money in − money out (may legitimately be negative). */
        public BigDecimal getBalance() { return received.subtract(spent); }
    }
}
