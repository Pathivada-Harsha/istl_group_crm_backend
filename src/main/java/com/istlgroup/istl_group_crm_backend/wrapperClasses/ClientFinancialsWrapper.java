package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole Customers → Financials tab in one payload.
 *
 * Every money field is INR, un-rounded, and comes from
 * {@link com.istlgroup.istl_group_crm_backend.service.ClientFinancialsService}.
 * The vocabulary is deliberately the project dashboard's, so a reader who knows
 * one screen knows the other:
 *
 *   billed   — invoiced to this client
 *   received — cash collected from this client
 *   payable  — vendor bills booked against this client's projects
 *   spent    — cash paid out to vendors on those projects
 *
 * {@code projects} is the audit trail: its rows sum EXACTLY to the totals block,
 * and each row equals what that project's own dashboard shows.
 */
@Data
@Builder
public class ClientFinancialsWrapper {

    // ── Who ──────────────────────────────────────────────────────────────────
    private Long   customerId;
    private String customerCode;
    private String customerName;

    // ── Totals across the client's projects ──────────────────────────────────
    private Totals totals;

    // ── One row per project; sums to `totals` ────────────────────────────────
    private List<ProjectRow> projects;

    // ── Where the outgoing money went, client-wide ───────────────────────────
    private SpendComposition spendComposition;

    // ── This client as a share of the company ────────────────────────────────
    private Concentration concentration;

    /**
     * Projects deliberately left out of everything above: cancelled or
     * deactivated. Surfaced rather than silently dropped, so the tab can say
     * "2 cancelled projects excluded" instead of quietly under-reporting.
     */
    private int excludedProjectCount;

    /** True when the client has projects but not a single rupee of activity. */
    private boolean noActivity;

    @Data
    @Builder
    public static class Totals {
        private BigDecimal billed;
        private BigDecimal received;
        private BigDecimal payable;
        private BigDecimal spent;

        /** billed − received, floored at 0 — what the client still owes us. */
        private BigDecimal outstandingReceivable;
        /** payable − spent, floored at 0 — what we still owe vendors. */
        private BigDecimal outstandingPayable;
        /** received − spent. NOT floored: a negative net cash position is real. */
        private BigDecimal netCash;
        /** billed − payable. NOT floored, and always labelled "projected". */
        private BigDecimal projectedMargin;

        /** Awarded value across the client's projects, from the order book. */
        private BigDecimal contractValue;
        /** billed ÷ contractValue × 100; null when there is no contract value. */
        private Double     percentBilled;
        /** received ÷ contractValue × 100; null when there is no contract value. */
        private Double     percentCollected;

        private int projectCount;
    }

    @Data
    @Builder
    public static class ProjectRow {
        private String     projectUniqueId;
        private String     projectName;
        private String     status;

        private BigDecimal contractValue;
        private BigDecimal billed;
        private BigDecimal received;
        private BigDecimal payable;
        private BigDecimal spent;

        /** billed − received, floored — this project's slice of the receivable. */
        private BigDecimal outstandingReceivable;
        /** received − spent, signed. */
        private BigDecimal netCash;
        /** billed − payable, signed. Negative = underwater. */
        private BigDecimal projectedMargin;

        /**
         * (payable + approved internal expenses) ÷ budget × 100 — the same
         * numerator and denominator ProjectDashboardService.buildFinancialData
         * uses for its budget-utilisation gauge. Null when the project carries
         * no budget, because a percentage of nothing is not 0%, it is unknown.
         */
        private Double budgetUtilisationPercent;
        /** True once utilisation is over 100% — the flag the table renders. */
        private boolean overBudget;
    }

    /**
     * Client-wide outflow, split by where it went.
     *
     * vendorAdvances + vendorBillPayments == totals.spent exactly.
     * internalExpenses sits OUTSIDE `spent` — it is approved employee/site
     * expense, not money paid to a vendor — so the three together are the full
     * outflow, which is a larger number than `spent`. The tab must say so.
     */
    @Data
    @Builder
    public static class SpendComposition {
        private BigDecimal vendorAdvances;
        private BigDecimal vendorBillPayments;
        private BigDecimal internalExpenses;
        private BigDecimal totalOutflow;
    }

    /**
     * How much of the company this one client is.
     *
     * Both the client figure and the company figure are drawn from the same
     * live roll-up over the same project population (live, non-cancelled), so
     * the share is a like-for-like ratio, not two differently-filtered numbers
     * divided by each other. Shares are null when the company total is zero.
     */
    @Data
    @Builder
    public static class Concentration {
        private BigDecimal companyBilled;
        private BigDecimal companyReceived;
        private Double     billedSharePercent;
        private Double     receivedSharePercent;
    }
}
