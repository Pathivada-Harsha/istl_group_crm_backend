package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * Everything the standard (non-solar) proposal document varies per lead, as
 * confirmed by the preparer in the review step.
 *
 * <p>Deliberately industry-neutral: there is no capacity, no subsidy and no ROI
 * here, and no wording that assumes what is being sold. The document this feeds
 * says "proposed solution" and "bill of materials", never "solar system" — see
 * {@code GenericProposalPdfService}.
 *
 * <p>The company's identity is NOT in this DTO. Branding is fixed server-side.
 *
 * <p>Serialized verbatim into {@code proposal_documents.payload}, the same way
 * {@link SolarProposalDocRequest} is, so a re-generate can prefill exactly what
 * was used last time.
 */
@Data
public class GenericProposalDocRequest {

    /** Lead whose tabs the content came from. Required. */
    private Long leadId;

    /**
     * Existing proposal to add a version to. Null creates a new proposal record
     * (the tracked entity keeps its number, status lifecycle and list entry).
     */
    private Long proposalId;

    /**
     * Marks a stored payload as this generator's rather than the solar one's.
     * Constant — never sent by the client — and read back by
     * {@code OrderBookService.generatedProposalItems}, which has to interpret
     * payloads of both shapes. Solar payloads simply have no such key.
     */
    private String docKind = "GENERIC";

    // ── Cover ────────────────────────────────────────────────────────────────
    private String title;
    private String clientName;
    private String clientAddress;
    private String siteLocation;
    private Integer validityDays;
    /** ISO yyyy-MM-dd. Blank means the day the document is generated. */
    private String quoteValidFrom;

    // ── Narrative, from the proposal record's own text fields ────────────────
    /** Introduction paragraph — {@code proposals.description}. */
    private String description;
    /** {@code proposals.about_us}. */
    private String aboutUs;
    /** The proposed solution — {@code proposals.about_system}, read generically. */
    private String aboutSystem;
    /** {@code proposals.payment_terms}. */
    private String paymentTerms;
    /** {@code proposals.defect_liability_period}. */
    private String defectLiabilityPeriod;
    /** Optional extra clauses under Warranty, one per line. */
    private String warrantyNotes;

    // ── Commercial ───────────────────────────────────────────────────────────
    /** Description of the single priced deliverable. */
    private String workDescription;
    /** Price excluding GST. */
    private BigDecimal basePrice;
    private BigDecimal gstPercent;
    /**
     * Price including GST. Authoritative when present: sales quote round totals
     * and the base is back-computed.
     */
    private BigDecimal totalCost;

    // ── Tables, as entered on the lead's tabs ────────────────────────────────
    private List<ScopeRow> scopeRows;
    private List<BomRow> bomRows;

    // ── Signature block ──────────────────────────────────────────────────────
    private String preparedByName;
    private String preparedByDesignation;
    private String preparedByPhone;
    private String preparedByEmail;

    /** One line of the Scope of Work table — the lead's Technical Scope items. */
    @Data
    public static class ScopeRow {
        private String activity;
        private String specification;
        private String quantity;
        private String unit;
        private String notes;
    }

    /** One line of the Scope of Supply table — the lead's BOM, without rates. */
    @Data
    public static class BomRow {
        private String component;
        private String specification;
        private String make;
        private String quantity;
        private String unit;
    }
}
