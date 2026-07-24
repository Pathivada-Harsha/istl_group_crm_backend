package com.istlgroup.istl_group_crm_backend.wrapperClasses;

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
    private String debtEquityRatio;

    private String interestRatePct;
    private String interestRateText;

    private String tenorText;
    private String tenorMonths;
    private String moratoriumMonths;
    private String scheduledCod;

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

    // ── audit ──
    private String createdAt;
    private String updatedAt;
}
