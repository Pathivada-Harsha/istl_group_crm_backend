package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * DTOs for the Order Book detail view (Technical Scope + Commercial tabs).
 * Kept in one file to mirror the lightweight wrapper style used elsewhere.
 */
public class OrderBookDetailWrapper {

    // ── Technical Scope (header + phases) ────────────────────────────────────
    @Data
    public static class ScopeRequest {
        private String projectType;
        private String scopeOfWork;
        private String technicalNotes;
        private String systemCapacity;
        private String siteLocation;
        private java.math.BigDecimal siteLat;
        private java.math.BigDecimal siteLng;
        private LocalDate plannedStartDate;
        private LocalDate plannedEndDate;
        private Integer totalPlannedWeeks;
        private String planUnit;
        private String trackingMode; // SIMPLE | DETAILED
        private List<PhaseRequest> phases;
    }

    // ── Detailed per-period progress (DETAILED tracking mode) ────────────────
    @Data
    public static class ProgressRequest {
        private List<ProgressCell> cells;
    }

    @Data
    public static class ProgressCell {
        private Long phaseId;
        private String subItemKey;  // null = the phase itself is the leaf
        private Integer periodNo;
        private BigDecimal plannedPct;
        private BigDecimal actualPct;
    }

    @Data
    public static class PhaseRequest {
        private Long id;               // null = new
        private Integer seqNo;
        private String phaseName;
        private String phaseDescription;
        private Integer startWeek;
        private Integer endWeek;
        private LocalDate plannedStartDate;
        private LocalDate plannedEndDate;
        private LocalDate actualStartDate;
        private LocalDate actualEndDate;
        private String status;
        private BigDecimal progressPercent;
        private BigDecimal weightPct;   // absolute project weight %; parent = sum of sub-item weights
        private BigDecimal plannedBudget; // planned procure budget; parent = sum of sub-item budgets
        private Long responsibleUserId;
        /** Sub-items / work-packages. Serialised to JSON before persist. */
        private List<java.util.Map<String, Object>> subItems;
    }

    // ── Commercial (budget lines) ────────────────────────────────────────────
    @Data
    public static class BudgetRequest {
        private List<BudgetLineRequest> lines;
    }

    @Data
    public static class BudgetLineRequest {
        private Long id;               // null = new
        private Integer seqNo;
        private String category;
        private String description;
        private BigDecimal allocatedAmount;
        private String notes;
    }

    // ── Commercial summary response (allocated vs actual) ────────────────────
    @Data
    public static class CommercialSummary {
        private boolean projectLinked;
        private String  projectId;
        private BigDecimal totalAllocated;
        private BigDecimal totalProcurement;   // sum of PO total_value for projectId
        private BigDecimal totalSpend;         // sum of project_expenses total_amount for projectId
        private BigDecimal orderValue;         // order_book.total_amount, for reference
        private List<BudgetLineRequest> lines; // echoed allocation lines
    }

    // ── Item-keyed billing / cost lines (Commercial tab rework) ──────────────
    @Data
    public static class FinanceLineRequest {
        private Long id;            // null = new
        private Integer seqNo;
        private String itemName;
        private BigDecimal amount;
        private java.time.LocalDate plannedDate;
        private String notes;
    }

    @Data
    public static class FinanceSaveRequest {
        private List<FinanceLineRequest> lines;
    }

    /**
     * Commercial summary v2:
     *  • Billing block: planned-to-bill total vs actuals from invoices (by project_id)
     *  • Cost block:    planned-cost total vs actuals from PO + expenses (by project_id)
     */
    @Data
    public static class CommercialSummaryV2 {
        private boolean projectLinked;
        private String  projectId;
        private BigDecimal orderValue;

        // Billing (receivables)
        private BigDecimal totalBillingPlanned;
        private BigDecimal totalInvoiced;     // sum invoices.total_amount
        private BigDecimal totalPaid;         // sum invoices.paid_amount
        private BigDecimal totalInvoiceBalance;
        private List<FinanceLineRequest> billingLines;

        // Cost (payables)
        private BigDecimal totalCostPlanned;
        private BigDecimal totalProcurement;  // sum PO total_value
        private BigDecimal totalSpend;        // sum project_expenses total_amount
        private List<FinanceLineRequest> costLines;
    }

    // ── BOM / BOQ ────────────────────────────────────────────────────────────
    @Data
    public static class BomLineRequest {
        private Long id;            // null = new
        private Integer seqNo;
        private String category;
        private String itemName;
        private String make;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal unitRate;
        private BigDecimal amount;
        private String notes;
    }

    @Data
    public static class BomSaveRequest {
        private List<BomLineRequest> lines;
    }

    // ── Site location (editable from Overview tab) ───────────────────────────
    @Data
    public static class SiteLocationRequest {
        private String siteLocation;
        private BigDecimal siteLat;
        private BigDecimal siteLng;
    }
}