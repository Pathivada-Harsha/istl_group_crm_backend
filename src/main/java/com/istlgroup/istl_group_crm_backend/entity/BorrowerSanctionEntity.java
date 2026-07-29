package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One sanction letter. A borrower may have several over time, so this is a
 * child of {@link BorrowerEntity} rather than a set of columns on it.
 *
 * <p>Only the values actually printed in the letter are stored. Everything the
 * detail page shows under "derived" — equity contribution, moratorium end,
 * repayment window, COD status — is computed on read by
 * {@code SanctionDerivedCalculator} and never persisted, so a correction to one
 * input can't leave a stale derived value behind.
 *
 * <p>The document bytes sit on the row as LONGBLOB, following the convention
 * already used by {@code TenderEntity.sourcePdfData}. They are lazily fetched
 * so list and detail queries don't pull megabytes they won't use.
 */
@Entity
@Table(name = "borrower_sanctions")
@Data
public class BorrowerSanctionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "borrower_id", nullable = false)
    private Long borrowerId;

    // ── read from the letter ──
    @Column(name = "ref_no", nullable = false, length = 120)
    private String refNo;

    @Column(name = "sanction_date")
    private LocalDate sanctionDate;

    @Column(name = "lender_name")
    private String lenderName;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "category", length = 120)
    private String category;

    @Column(name = "location")
    private String location;

    /** Plain rupees, so "Rs. 205.00 Crore" and a typed value agree. */
    @Column(name = "project_cost", precision = 18, scale = 2)
    private BigDecimal projectCost;

    @Column(name = "sanctioned_amount", precision = 18, scale = 2)
    private BigDecimal sanctionedAmount;

    // ── means of finance (registry sheet) ──
    /**
     * Plain rupees, like every other money column here, even though the sheet's
     * header reads "Rs. Cr's" — the unit is handled at the edges by
     * {@code SanctionValueParser.parseMoneyCrore} on the way in and
     * {@code formatCrore} on the way out.
     */
    @Column(name = "debt_amount", precision = 18, scale = 2)
    private BigDecimal debtAmount;

    @Column(name = "equity_amount", precision = 18, scale = 2)
    private BigDecimal equityAmount;

    /** The number only; the "%" is presentation, re-added on render. */
    @Column(name = "debt_pct", precision = 6, scale = 3)
    private BigDecimal debtPct;

    @Column(name = "equity_pct", precision = 6, scale = 3)
    private BigDecimal equityPct;

    /** As printed, e.g. "75:25". Kept as text — it is a ratio, not a number. */
    @Column(name = "debt_equity_ratio", length = 30)
    private String debtEquityRatio;

    // ── rate build-up (registry sheet: Rate of Interest) ──
    @Column(name = "base_rate_pct", precision = 6, scale = 3)
    private BigDecimal baseRatePct;

    @Column(name = "spread_pct", precision = 6, scale = 3)
    private BigDecimal spreadPct;

    /** As printed. Falls back to base + spread on read when the letter is silent. */
    @Column(name = "roi_pct", precision = 6, scale = 3)
    private BigDecimal roiPct;

    @Column(name = "interest_rate_pct", precision = 6, scale = 3)
    private BigDecimal interestRatePct;

    /** Full phrase, including the floating / MCLR basis. */
    @Column(name = "interest_rate_text")
    private String interestRateText;

    // ── project details (registry sheet) ──
    @Column(name = "technology", length = 120)
    private String technology;

    @Column(name = "village", length = 180)
    private String village;

    @Column(name = "district", length = 120)
    private String district;

    /** Registry sheet: Product → Instrument. */
    @Column(name = "instrument", length = 120)
    private String instrument;

    // ── security (registry sheet) ──
    @Column(name = "co_obligators", length = 500)
    private String coObligators;

    @Column(name = "pledge_of_shares_pct", precision = 6, scale = 3)
    private BigDecimal pledgeOfSharesPct;

    // ── financial covenants (registry sheet) ──
    /** A coverage multiple; the trailing "x" is a unit, re-added on render. */
    @Column(name = "min_dscr", precision = 6, scale = 3)
    private BigDecimal minDscr;

    /**
     * DSRA / ISRA / cash sweep stay text on purpose. Letters write these as
     * phrases — "equivalent to one quarter's debt service", "to be built up by
     * COD", "100% above 1.30x DSCR" — and a numeric column would have to either
     * drop the qualifier or invent a number the document never printed.
     */
    @Column(name = "dsra", columnDefinition = "TEXT")
    private String dsra;

    @Column(name = "isra", columnDefinition = "TEXT")
    private String isra;

    @Column(name = "cash_sweep", columnDefinition = "TEXT")
    private String cashSweep;

    @Column(name = "tenor_text")
    private String tenorText;

    @Column(name = "tenor_months")
    private Integer tenorMonths;

    @Column(name = "moratorium_months")
    private Integer moratoriumMonths;

    // ── timeline (registry sheet: Time Lines) ──
    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    /**
     * The contractual dates as printed. Distinct from
     * {@code derivedRepaymentStart} / {@code derivedRepaymentEnd}, which stay
     * modelled from tenor + moratorium — a divergence between the two is
     * information a credit officer wants, not a bug to reconcile away.
     */
    @Column(name = "repayment_start_date")
    private LocalDate repaymentStartDate;

    @Column(name = "repayment_end_date")
    private LocalDate repaymentEndDate;

    @Column(name = "scheduled_cod")
    private LocalDate scheduledCod;

    // ── base case assumptions (registry sheet) ──
    @Column(name = "plf_pct", precision = 6, scale = 3)
    private BigDecimal plfPct;

    @Column(name = "tariff_per_unit", precision = 10, scale = 4)
    private BigDecimal tariffPerUnit;

    // ── lifecycle ──
    /** DRAFT | IMPORTED | REVIEW | ONBOARDED */
    @Column(name = "status", nullable = false, length = 30)
    private String status = "DRAFT";

    /** MANUAL | IMPORTED | IMPORTED_EDITED */
    @Column(name = "source", nullable = false, length = 30)
    private String source = "MANUAL";

    /** What the parser first returned, kept so a saved value can be audited. */
    @Lob
    @Column(name = "raw_extracted_json", columnDefinition = "LONGTEXT")
    private String rawExtractedJson;

    /** TABLE | REGEX | AI | MIXED */
    @Column(name = "extraction_engine", length = 30)
    private String extractionEngine;

    // ── the letter itself ──
    // NOT @Basic(LAZY): lazy loading of a @Lob only works with bytecode
    // enhancement, and reading it from a detached entity then throws. The
    // bytes are instead never selected on the normal read paths, because
    // BorrowerSanctionRepo.findDocData() fetches them by explicit query.
    @Lob
    @Column(name = "sanction_doc_data", columnDefinition = "LONGBLOB")
    private byte[] sanctionDocData;

    @Column(name = "sanction_doc_name")
    private String sanctionDocName;

    @Column(name = "sanction_doc_mime", length = 120)
    private String sanctionDocMime;

    @Column(name = "sanction_doc_size")
    private Long sanctionDocSize;

    // ── audit ──
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}