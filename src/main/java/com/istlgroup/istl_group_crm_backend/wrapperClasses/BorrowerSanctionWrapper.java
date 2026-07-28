package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request + response DTO for a sanction letter.
 *
 * <p>Scalars are String, following {@code TenderWrapper}: the frontend sends
 * loose values ("", "₹153.75 Cr", "14 March 2025") and the service parses them
 * into the entity's typed columns. That also lets the review screen post back
 * exactly what the parser produced without the frontend having to normalise it.
 *
 * <p>The {@code derived*} fields are read-only. They are recomputed on every
 * read from the stored inputs, so a corrected amount can never leave a stale
 * equity figure behind. Anything the frontend sends in them is ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BorrowerSanctionWrapper {

    private Long id;
    private Long borrowerId;

    // ── as printed in the letter ──
    private String refNo;
    private String sanctionDate;
    private String lenderName;
    private String projectName;
    private String category;
    private String location;

    /** Accepts "205.00 Cr", "Rs. 205 Crore", "2050000000" — all the same value. */
    private String projectCost;
    private String sanctionedAmount;

    // ── means of finance (registry sheet) ──
    /**
     * The sheet's column header declares crore, so a unit-less number here is
     * read as crore. Explicit units ("Rs. 205 Crore", "2,05,00,000 lakh") still
     * win — see {@code SanctionValueParser.parseMoneyCrore}.
     */
    private String debtAmount;
    private String equityAmount;
    private String debtPct;
    private String equityPct;
    private String debtEquityRatio;

    // ── rate build-up ──
    private String baseRatePct;
    private String spreadPct;
    private String roiPct;
    private String interestRatePct;
    private String interestRateText;

    // ── project details ──
    private String technology;
    private String village;
    private String district;
    private String instrument;

    // ── security ──
    private String coObligators;
    private String pledgeOfSharesPct;

    // ── financial covenants ──
    private String minDscr;
    private String dsra;
    private String isra;
    private String cashSweep;

    private String tenorText;
    private String tenorMonths;
    private String moratoriumMonths;

    // ── timeline ──
    private String disbursementDate;
    /** Contractual, as printed — the {@code derived*} twins stay modelled. */
    private String repaymentStartDate;
    private String repaymentEndDate;
    private String scheduledCod;

    // ── base case assumptions ──
    private String plfPct;
    private String tariffPerUnit;

    // ── lifecycle ──
    private String status;
    private String source;
    private String extractionEngine;

    // ── document ──
    private String sanctionDocName;
    private String sanctionDocMime;
    private Long sanctionDocSize;
    private Boolean hasDocument;

    // ── derived, read-only ──
    private String derivedEquityContribution;
    private String derivedRatioCheck;
    private String derivedMoratoriumEnd;
    private String derivedRepaymentStart;
    private String derivedRepaymentEnd;
    private String derivedTotalTenorMonths;
    private String derivedFirstYearInterest;
    private String derivedSanctionValidTill;
    private String derivedCodStatus;
    /** Set when a printed ROI disagrees with base rate + spread. */
    private String derivedRoiCheck;

    /**
     * Keys this response filled by calculation because the letter did not print
     * them — so the UI can mark a value as computed rather than read. A key
     * being absent from the map is how "not in the document" is represented;
     * a key present here means "not in the document, but inferable".
     */
    private List<String> computedFields = new ArrayList<>();

    // ── audit ──
    private String createdAt;
    private String updatedAt;
}
