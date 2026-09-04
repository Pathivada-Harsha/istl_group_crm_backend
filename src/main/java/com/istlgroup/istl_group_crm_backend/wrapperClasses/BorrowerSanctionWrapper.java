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
    /** Set only for a sanction associated directly with a Parent Group or Sub Group, instead of a borrowerId. */
    private Long groupId;

    /**
     * Read-only, computed on the way out — "COMPANY" | "GROUP" | "SUB_GROUP",
     * telling the UI which kind of record {@link #associatedWithName} names.
     * Never inferred from hierarchy: a company under a group is always
     * "COMPANY", never rolled up into its group's own association.
     */
    private String associatedWithType;
    /** Read-only, computed on the way out — the borrower or group name this sanction is directly attached to. */
    private String associatedWithName;

    // ── as printed in the letter ──
    private String refNo;
    private String sanctionDate;
    private String lenderName;
    private String projectName;
    private String category;
    private String location;

    /**
     * The borrower's own CIN, mirrored in from {@code BorrowerEntity.cin} for
     * display only — read-only context on the sanction screen, never a
     * column on {@code borrower_sanctions}. {@code BorrowerEntity.cin}
     * remains the sole authoritative source: this field is only ever set on
     * the way out (see {@code BorrowerService.toWrapper(BorrowerSanctionEntity,
     * BorrowerEntity)}); the sanction save path never reads it back off an
     * incoming request.
     */
    private String cin;

    /**
     * The borrower's own registered address, mirrored in from {@code
     * BorrowerEntity.registeredAddress} for display only — same treatment as
     * {@code cin} above, and for the same reason: {@code location} (above)
     * is a genuinely separate, independently-editable per-sanction/project
     * value and is deliberately left untouched by this — this field is
     * never copied into it, and is itself never read back off an incoming
     * request.
     */
    private String registeredAddress;

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
    private String instrument;

    // ── security ──
    private String coObligators;
    private String pledgeOfSharesPct;

    // ── financial covenants ──
    private String minDscr;
    private String avgDscr;
    private String dsra;
    private String isra;
    /**
     * Reviewer-confirmed reserve amount, editable on the sanction form.
     * Blank means "not manually confirmed" — the response gap-fills this
     * with the calculated figure (see {@code derivedDsraAmount}/
     * {@code derivedIsraAmount} below) every read, so the box always starts
     * from a sensible suggestion but a typed-over value sticks.
     */
    private String dsraAmount;
    private String israAmount;
    private String cashSweep;

    private String tenorText;
    private String tenorMonths;
    private String moratoriumMonths;
    /** SERVICED | CAPITALIZED, as confirmed by the reviewer. */
    private String interestDuringMoratorium;
    /** MONTHLY | BI_MONTHLY | QUARTERLY | HALF_YEARLY | YEARLY | OTHER. */
    private String repaymentFrequency;
    /** Custom interval in months; only meaningful when repaymentFrequency is OTHER. */
    private String repaymentFrequencyOtherMonths;
    /** JSON array of per-period repayment percentages; null = auto equal-split. */
    private String repaymentProfileJson;

    // ── timeline ──
    private String disbursementDate;
    /** Contractual, as printed — the {@code derived*} twins stay modelled. */
    private String repaymentStartDate;
    private String repaymentEndDate;
    private String scheduledCod;
    private String actualCod;

    // ── base case assumptions ──
    private String plfPct;
    private String tariffPerUnit;

    // ── lifecycle ──
    private String status;
    /** ACTIVE / INACTIVE — this letter's own business status; see BorrowerSanctionEntity.activeStatus. */
    private String activeStatus;
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
    /** actualCod if set, else scheduledCod — what the UI shows as "Actual COD Date" until a real one is entered. */
    private String derivedActualCod;
    /**
     * Set only when the DSRA/ISRA phrase states a recognisable reserve
     * period. "Not Calculated" (as opposed to null) means the phrase is
     * there but didn't parse — never confused with "nothing entered".
     */
    private String derivedDsraAmount;
    private String derivedIsraAmount;
    /**
     * true  = the letter's own ISRA clause priced a genuine contractual figure.
     * false = no ISRA clause exists; derivedIsraAmount is the interest
     *         component of the DSRA calculation instead, shown for reference
     *         only.
     * null  = no ISRA figure at all — either nothing to show, or the letter's
     *         own ISRA text didn't parse into a recognisable period (see
     *         "Not Calculated" above).
     */
    private Boolean derivedIsraIsContractual;
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
