package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * Request bodies for the Lead Technical Scope + Budget Estimation feature.
 * Responses are assembled as plain Maps in the service to match the exact JSON
 * shapes the frontend expects.
 */
public class LeadScopeWrapper {

    /** POST /leads/{leadId}/scope */
    @Data
    public static class ScopeHeaderRequest {
        private String projectType;
        private String systemCapacity;
        private String scopeOfWork;
        private String technicalNotes;
        private String siteLocation;
    }

    /** POST /leads/{leadId}/scope/items */
    @Data
    public static class ScopeItemRequest {
        private Long id;            // null → create, else update
        private Integer seqNo;
        private String activity;
        private String category;
        private String specification;
        private BigDecimal quantity;
        private String unit;
        private String notes;
    }

    /** POST /leads/{leadId}/budget/category */
    @Data
    public static class BudgetCategoryRequest {
        private Long id;            // null → create, else update
        private Integer seqNo;
        private String category;
        private String description;
        private String notes;
    }

    /** POST /leads/{leadId}/budget/item */
    @Data
    public static class BudgetItemRequest {
        private Long id;            // null → create, else update
        private Long budgetId;      // parent category id (required on create)
        private Integer seqNo;
        private String itemName;
        private String make;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitRate;
        private String notes;
    }

    /** PUT /leads/{leadId}/selling-price — a negotiated target price. */
    @Data
    public static class SellingPriceRequest {
        private BigDecimal sellingPrice;
    }

    /** PUT /leads/{leadId}/margin — the profit markup % on cost. */
    @Data
    public static class MarginRequest {
        private BigDecimal marginPercent;
    }

    /**
     * PUT /leads/{leadId}/scope/items — whole-list replace.
     * Lines absent from {@code items} are soft-deleted.
     */
    @Data
    public static class ScopeItemsBulkRequest {
        private List<ScopeItemRequest> items;
    }

    /** A single line of PUT /leads/{leadId}/bom */
    @Data
    public static class BomLineRequest {
        private Long id;            // null → create, else update
        private Long scopeItemId;   // the scope activity this material is for (optional)
        private Integer seqNo;
        private String category;
        private String itemName;
        private String make;
        private String specification;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal unitRate;
        private BigDecimal amount;  // optional override; derived from qty × rate when absent
        private String notes;
        // Pick-a-make: catalog link + chosen variant snapshotted onto the line.
        private Long bomItemId;
        private Long variantId;
        // Auto-sizing: the quantity basis is snapshotted onto the saved line so a
        // reloaded BOM stays live (recomputes on capacity / make change).
        private String basis;
        private BigDecimal basisValue;
        private BigDecimal stepValue;
        private String siteVisitField;
        private BigDecimal driverAttr;
        private Boolean autoQty;
    }

    /** PUT /leads/{leadId}/bom — whole-list replace. */
    @Data
    public static class BomSaveRequest {
        private List<BomLineRequest> lines;
    }

    /** A single line of PUT /leads/{leadId}/budget/extras */
    @Data
    public static class ExtraLineRequest {
        private Long id;            // null → create, else update
        private Integer seqNo;
        private String name;
        private String basis;       // FIXED | PERCENT
        private BigDecimal rateValue;
        private String notes;
        // No amount: FIXED uses rateValue as-is, PERCENT derives from the BOM
        // subtotal. The client never gets to set the stored figure.
    }

    /** PUT /leads/{leadId}/budget/extras — whole-list replace. */
    @Data
    public static class ExtrasSaveRequest {
        private List<ExtraLineRequest> lines;
    }
}
