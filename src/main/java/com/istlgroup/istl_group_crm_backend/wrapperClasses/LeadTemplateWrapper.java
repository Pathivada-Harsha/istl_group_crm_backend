package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * Request bodies for the Lead Scope/BOM template admin (/admin/lead-templates).
 * Responses are assembled as plain Maps in the service, matching the shapes the
 * admin page and the suggestion tabs expect.
 */
public class LeadTemplateWrapper {

    /** POST/PUT /admin/lead-templates */
    @Data
    public static class TemplateHeaderRequest {
        private String projectType;
        private String name;
        private String description;
        private Boolean isActive;
    }

    /** A single scope line of PUT /admin/lead-templates/{id}/scope-items */
    @Data
    public static class TemplateScopeLineRequest {
        private Long id;
        private Integer seqNo;
        private String activity;
        private String category;
        private String specification;
        private String unit;
        private String notes;
        /** Share of the template's 100%. Validated/normalised server-side. */
        private BigDecimal weightPct;
        /** TRUE when the user pinned this weight; FALSE when it auto-balances. */
        private Boolean weightManual;
    }

    /** PUT /admin/lead-templates/{id}/scope-items — whole-list replace. */
    @Data
    public static class TemplateScopeLinesRequest {
        private List<TemplateScopeLineRequest> items;
    }

    /** A single BOM line of PUT /admin/lead-templates/{id}/bom-items */
    @Data
    public static class TemplateBomLineRequest {
        private Long id;
        private Integer seqNo;
        private String scopeActivity;
        private String category;
        private String itemName;
        private String make;
        private String specification;
        private String unit;
        private String basis;              // FIXED | PER_KW | PER_STEP | FROM_SITE_VISIT
        private BigDecimal basisValue;
        private BigDecimal stepValue;
        private String siteVisitField;
        private BigDecimal defaultUnitRate;
        private String notes;
        // Pick-a-make: catalog link + curated allowed makes + which is the default.
        private Long bomItemId;
        private List<Long> allowedVariantIds;
        private Long defaultVariantId;
    }

    /** PUT /admin/lead-templates/{id}/bom-items — whole-list replace. */
    @Data
    public static class TemplateBomLinesRequest {
        private List<TemplateBomLineRequest> lines;
    }
}
