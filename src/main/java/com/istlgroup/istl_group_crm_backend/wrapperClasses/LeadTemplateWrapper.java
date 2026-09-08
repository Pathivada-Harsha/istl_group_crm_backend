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
        /**
         * Second-level breakdown under this activity — a subset of the
         * {@code project_phases.sub_items} shape (name / description / unit /
         * weightPct / weightManual), so a phase seeded from it later needs no
         * translation. Sub-weights are a share of THIS line and total 100 within
         * the parent; the parent keeps its own share of the template's 100.
         * Null or empty means "no breakdown".
         */
        private List<TemplateScopeSubItemRequest> subItems;
    }

    /**
     * One node in a scope line's breakdown tree.
     *
     * <p>A node may itself have {@link #children}, to any depth — "Electrical Works →
     * PV Module → Purchase Order" is three levels and nothing caps it. A node with no
     * children is a leaf and is where progress is actually recorded.
     *
     * <p><b>{@link #id} is the node's identity, not its name.</b> It is a UUID minted
     * once, when the node is first created, and carried through every copy
     * (template → lead → project) and every edit. Progress
     * ({@code project_progress_periods.sub_item_key}) and the planned budget key off it,
     * so a node can be renamed freely without detaching its history, and two nodes that
     * happen to share a name in different branches stay separate. A null id on the way in
     * means "new node" and is filled by {@code ScopeSubItems.ensureIds}.
     */
    @Data
    public static class TemplateScopeSubItemRequest {
        /** Stable UUID. Null only for a node the client has just created. */
        private String id;
        private String name;
        private String description;
        private String unit;
        private BigDecimal weightPct;
        private Boolean weightManual;
        /**
         * How this node's own span divides for the items under it: "WEEK" or "MONTH".
         * Only meaningful on a node that HAS children — it is the grid they are
         * scheduled against. Null means weekly, the long-standing default.
         */
        private String planUnit;
        /** A node's own span (a parent's grid), ISO yyyy-MM-dd. */
        private String plannedStartDate;
        private String plannedEndDate;
        /** A leaf's own dates, ISO yyyy-MM-dd. */
        private String startDate;
        private String endDate;
        /** A child's 1-based period indices on its PARENT's grid. */
        private Integer startWeek;
        private Integer endWeek;
        /**
         * This node's own breakdown. Each child's weight is a share of THIS node and the
         * children total 100 within it — the same rule as the top level, applied at every
         * depth. Null or empty means this node is a leaf.
         */
        private List<TemplateScopeSubItemRequest> children;
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
