package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * Everything the Solar proposal document varies per lead, as confirmed by the
 * preparer in the review step. The static half of the document (cover art,
 * company sections, images, header/footer, warranty, signatory) lives in the
 * skeleton and is never sent from the client.
 *
 * <p>ROI and Subsidy have no stored source in the CRM, so the values arrive here
 * from the review step and are never invented server-side — the only ROI numbers
 * the service produces are the ones derived from these inputs.
 */
@Data
public class SolarProposalDocRequest {

    /** Lead whose tabs the content came from. Required. */
    private Long leadId;

    /**
     * Existing proposal to add a version to. Null creates a new proposal record
     * (the tracked entity keeps its number, status lifecycle and list entry).
     */
    private Long proposalId;

    private String title;

    /**
     * "Residential" | "Commercial" | "Industrial" | "Institutional" |
     * "Agricultural" | "Government", or null when the lead has none recorded.
     * Decides the Subsidy section only — ROI no longer consults it.
     */
    private String propertyType;

    // ── Cover + header info table ────────────────────────────────────────────
    private String coverTitle;        // "Ashok Erugurala Residential"
    private String coverSubtitle;     // "4 kWp Ongrid Rooftop Solar Plant"
    private String clientName;
    private String siteLocation;
    private String capacityLabel;     // "4 kWp" — also used in the price header
    private String capacityLine;      // "4 kWp On grid Rooftop Solar Plant"

    // ── Financial pricing ────────────────────────────────────────────────────
    private String workDescription;
    /** Price excluding GST. */
    private BigDecimal basePrice;
    private BigDecimal gstPercent;
    /**
     * Price including GST. Authoritative when present: sales quote round totals
     * and the base is back-computed, which is how both reference proposals read.
     */
    private BigDecimal totalCost;

    // ── Note block ───────────────────────────────────────────────────────────
    private Integer quoteValidDays;
    /** ISO yyyy-MM-dd; the day name printed alongside is derived from it. */
    private String quoteValidFrom;

    // ── Subsidy (residential rooftops only — MNRE PM Surya Ghar) ─────────────
    private Boolean includeSubsidy;
    private BigDecimal subsidyAmount;
    private String subsidyNote1;
    private String subsidyNote2;
    private String subsidyNote3;

    // ── ROI analysis (any property type, once capacity + tariff are known) ───
    private Boolean includeRoi;
    /** Plant capacity in kWp — the multiplier for every generation figure. */
    private BigDecimal capacityKw;
    /** ₹ per unit, from the Site Visit tab. */
    private BigDecimal tariffPerUnit;
    /** Assumption: units generated per kWp per year. */
    private BigDecimal specificGeneration;
    /** Assumption: years of useful life used for the lifetime rows. */
    private Integer systemLifeYears;

    // ── Bill of Material — exactly as entered on the lead's BOM tab ──────────
    private List<BomRow> bomRows;

    @Data
    public static class BomRow {
        private String component;
        private String specification;
        private String make;
        private String quantity;
        private String unit;
    }
}
