package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;

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

    /** PUT /leads/{leadId}/selling-price */
    @Data
    public static class SellingPriceRequest {
        private BigDecimal sellingPrice;
    }
}
