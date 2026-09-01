package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;

/**
 * Values the letter doesn't print but a credit officer would work out.
 *
 * <p>Computed on every read rather than stored, so correcting one input can
 * never leave a stale derived figure behind. This is the panel that makes the
 * page worth more than a document viewer: nine numbers from ten inputs, none of
 * them typed.
 *
 * <p>Mirrored in the frontend's {@code deriveSanction()} so the review screen
 * can update them live as the user edits. If you change a rule here — in
 * {@link #apply} or {@link #fillGaps} — change it there too; the backend value
 * is the one that is persisted-adjacent and shown on the detail page.
 */
@Component
public class SanctionDerivedCalculator {

    /** Letters in the sample set state a six-month validity. */
    private static final int DEFAULT_VALIDITY_MONTHS = 6;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Rates are decimal(6,3), so anything below a thousandth of a point is
     * storage noise, not a disagreement worth flagging to a credit officer.
     */
    private static final BigDecimal ROI_TOLERANCE = new BigDecimal("0.001");

    // Package-private (not private) so tests built with a bare `new
    // SanctionDerivedCalculator()` — this class's existing convention,
    // matching SanctionRegistryValueTest — can wire it directly.
    @Autowired
    LoanReserveCalculator reserveCalc = new LoanReserveCalculator();

    public void apply(BorrowerSanctionEntity e, BorrowerSanctionWrapper w) {
        if (e == null || w == null) return;

        BigDecimal cost   = e.getProjectCost();
        BigDecimal loan   = e.getSanctionedAmount();
        LocalDate  signed = e.getSanctionDate();
        Integer    tenor  = e.getTenorMonths();
        Integer    mora   = e.getMoratoriumMonths();
        // Until a real Actual COD Date is recorded, the planned date stands in
        // for it — a deliberate policy choice, not a fallback for missing
        // data (see the COD block below for the full rationale). Repayment
        // timing is modelled off this date, not the sanction date: the
        // moratorium is a holiday measured from the project's revenue start,
        // not from the day the letter was signed.
        LocalDate effectiveActual = e.getActualCod() != null ? e.getActualCod() : e.getScheduledCod();

        // ── equity contribution and the ratio it implies ──
        if (cost != null && loan != null) {
            BigDecimal equity = cost.subtract(loan);
            w.setDerivedEquityContribution(SanctionValueParser.formatCrore(equity));
            w.setDerivedRatioCheck(ratioCheck(cost, loan, e.getDebtEquityRatio()));
        }

        // ── sanction validity — still the signing date's business, not COD's ──
        if (signed != null) {
            w.setDerivedSanctionValidTill(SanctionValueParser.formatDate(sanctionValidTill(signed)));
        }

        // ── the repayment window, anchored on COD (planned until confirmed) ──
        if (effectiveActual != null) {
            LocalDate moratoriumEnd = mora != null ? effectiveActual.plusMonths(mora) : effectiveActual;
            w.setDerivedMoratoriumEnd(SanctionValueParser.formatDate(moratoriumEnd));

            LocalDate repayStart = moratoriumEnd;
            w.setDerivedRepaymentStart(SanctionValueParser.formatDate(repayStart));

            if (tenor != null) {
                int amortizingMonths = mora != null ? tenor - mora : tenor;
                LocalDate repayEnd = repayStart.plusMonths(amortizingMonths);
                w.setDerivedRepaymentEnd(SanctionValueParser.formatDate(repayEnd));
                // Interest still accrues from signing/disbursement, even though
                // principal repayment now starts relative to COD instead.
                if (signed != null) applyReserves(e, w, signed, repayStart, repayEnd);
            }
        }

        if (tenor != null) {
            w.setDerivedTotalTenorMonths(tenor + " months");
        }

        // ── indicative first-year interest ──
        // Not e.getInterestRatePct() — nothing populates that column (no
        // extractor, no form field), so it is always null. The rate actually
        // in force is resolveRoi(), the same precedence fillGaps() applies
        // when backfilling roiPct.
        BigDecimal roiForInterest = resolveRoi(e);
        if (loan != null && roiForInterest != null) {
            BigDecimal interest = loan
                    .multiply(roiForInterest)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            w.setDerivedFirstYearInterest(SanctionValueParser.formatCrore(interest));
        }

        // ── COD, the one derived value that moves on its own ──
        // effectiveActual (computed above) means status never reports
        // "Overdue" while a planned date exists — that reads as "on
        // schedule" until someone enters a real Actual COD Date.
        if (effectiveActual != null) {
            w.setDerivedActualCod(SanctionValueParser.formatDate(effectiveActual));
            String status = "Achieved on " + SanctionValueParser.formatDate(effectiveActual);
            if (e.getScheduledCod() != null) {
                long variance = ChronoUnit.DAYS.between(e.getScheduledCod(), effectiveActual);
                if (variance == 0) {
                    status += " (on schedule)";
                } else if (variance > 0) {
                    status += " (" + variance + " day" + (variance == 1 ? "" : "s") + " late)";
                } else {
                    status += " (" + (-variance) + " day" + (variance == -1 ? "" : "s") + " early)";
                }
            }
            w.setDerivedCodStatus(status);
        }
    }

    /**
     * DSRA/ISRA, priced off the reserve engine rather than left as the
     * printed phrase alone. {@code repayStart}/{@code repayEnd} are the same
     * modelled dates {@link #apply} already computes for the repayment
     * window, so the schedule this prices off never disagrees with what the
     * panel shows for "Repayment starts"/"Repayment ends".
     *
     * <p>DSRA: a recognisable reserve period ({@code parseReservePeriods})
     * prices the figure as usual; text that's present but doesn't parse gets
     * "Not Calculated" rather than a blank indistinguishable from "nothing
     * entered"; blank text stays {@code null} (a dash).
     *
     * <p>ISRA has three distinct outcomes, not two: (1) the letter's own ISRA
     * clause parses — a genuine contractual figure, {@code
     * derivedIsraIsContractual = true}; (2) an ISRA clause is present but
     * doesn't parse — "Not Calculated", contractual left unset, and it never
     * falls back to DSRA under a real (if unparseable) clause; (3) no ISRA
     * clause at all — if DSRA parses, show DSRA's own interest component as
     * a reference figure, clearly marked {@code derivedIsraIsContractual =
     * false} so the UI never implies a separate contractual requirement that
     * was never stated.
     */
    private void applyReserves(BorrowerSanctionEntity e, BorrowerSanctionWrapper w,
            LocalDate signed, LocalDate repayStart, LocalDate repayEnd) {
        BigDecimal debt = e.getDebtAmount() != null ? e.getDebtAmount() : e.getSanctionedAmount();
        BigDecimal roi = resolveRoi(e);
        if (debt == null || roi == null) return;

        // Repayment frequency drives both the schedule's period length and
        // how many periods a covenant phrase like "next two quarters"
        // resolves to — never hardcoded quarterly, so a Monthly sanction
        // reserves against monthly periods, not 3-month ones. OTHER with no
        // valid custom interval yet has nothing to price against.
        Integer monthsPerPeriod = resolveMonthsPerPeriod(e);
        if (monthsPerPeriod == null) return;

        // Capitalized: the moratorium's own accrued interest is folded into
        // the balance DSRA/ISRA are priced off, same as it's folded into the
        // balance the amortizing phase actually repays — a lender reserving
        // against debt service must reserve against what will really be
        // owed, not the pre-capitalization principal.
        boolean capitalized = "CAPITALIZED".equals(e.getInterestDuringMoratorium());
        // buildQuarterEndSchedule, not buildSchedule — the same engine the
        // Repayment Schedule tab uses (EOMONTH dates, percentage-driven
        // principal), so DSRA/ISRA here can never silently disagree with
        // what that tab shows for the identical sanction.
        List<LoanReserveCalculator.Period> schedule = reserveCalc.buildQuarterEndSchedule(
                debt, roi, signed, repayStart, repayEnd, monthsPerPeriod, capitalized,
                parseRepaymentProfile(e.getRepaymentProfileJson()));

        String dsraText = e.getDsra();
        Integer dsraPeriods = SanctionValueParser.parseReservePeriods(dsraText, monthsPerPeriod);
        if (dsraPeriods != null) {
            w.setDerivedDsraAmount(dsraPeriods == 0 ? "Nil"
                    : SanctionValueParser.formatCrore(reserveCalc.sumDebtService(schedule, dsraPeriods)));
        } else if (!SanctionValueParser.isBlank(dsraText)) {
            w.setDerivedDsraAmount("Not Calculated");
        }

        String israText = e.getIsra();
        Integer israPeriods = SanctionValueParser.parseReservePeriods(israText, monthsPerPeriod);
        if (israPeriods != null) {
            w.setDerivedIsraAmount(israPeriods == 0 ? "Nil"
                    : SanctionValueParser.formatCrore(reserveCalc.sumInterest(schedule, israPeriods)));
            w.setDerivedIsraIsContractual(true);
        } else if (!SanctionValueParser.isBlank(israText)) {
            w.setDerivedIsraAmount("Not Calculated");
        } else if (dsraPeriods != null) {
            w.setDerivedIsraAmount(dsraPeriods == 0 ? "Nil"
                    : SanctionValueParser.formatCrore(reserveCalc.sumInterest(schedule, dsraPeriods)));
            w.setDerivedIsraIsContractual(false);
        }
    }

    /** The date after which an unrenewed sanction lapses. */
    public LocalDate sanctionValidTill(LocalDate signed) {
        return signed == null ? null : signed.plusMonths(DEFAULT_VALIDITY_MONTHS);
    }

    /**
     * Fill the registry-sheet columns the letter left blank but that follow
     * arithmetically from what it did print. Printed wins: this only ever writes
     * a wrapper field that is still null, and records what it filled in
     * {@code computedFields} so the UI can mark the value as calculated.
     *
     * <p>Call it after {@link #apply}. It deliberately never touches
     * {@code derivedRepaymentStart} / {@code derivedRepaymentEnd} — those stay
     * the modelled dates, so a divergence from the contractual ones printed in
     * the letter remains visible.
     */
    public void fillGaps(BorrowerSanctionEntity e, BorrowerSanctionWrapper w) {
        if (e == null || w == null) return;

        BigDecimal cost = e.getProjectCost();
        BigDecimal debt = e.getDebtAmount();

        // Debt is the sanctioned amount unless the letter splits them out —
        // a facility that is one tranche of a larger consortium debt.
        if (debt == null && e.getSanctionedAmount() != null) {
            debt = e.getSanctionedAmount();
            fill(w, "debtAmount", SanctionValueParser.formatCrore(debt));
        }

        BigDecimal equity = e.getEquityAmount();
        if (equity == null && cost != null && debt != null) {
            equity = cost.subtract(debt);
            fill(w, "equityAmount", SanctionValueParser.formatCrore(equity));
        }

        BigDecimal debtPct = e.getDebtPct();
        if (debtPct == null && cost != null && debt != null && cost.signum() != 0) {
            debtPct = debt.multiply(HUNDRED).divide(cost, 1, RoundingMode.HALF_UP);
            fill(w, "debtPct", SanctionValueParser.formatPct(debtPct));
        }

        if (e.getEquityPct() == null) {
            BigDecimal equityPct = null;
            if (equity != null && cost != null && cost.signum() != 0) {
                equityPct = equity.multiply(HUNDRED).divide(cost, 1, RoundingMode.HALF_UP);
            } else if (debtPct != null) {
                equityPct = HUNDRED.subtract(debtPct);
            }
            fill(w, "equityPct", SanctionValueParser.formatPct(equityPct));
        }

        // ── ROI ──
        // A number printed as part of "Rate of Interest" is what the letter
        // actually says, so it wins over the base + spread build-up below —
        // this is what backfills roiPct for letters that only ever stated one
        // composite rate (existing records included: this runs on every read,
        // not just at import, so it applies retroactively too).
        BigDecimal roi = e.getRoiPct();
        if (roi == null) {
            roi = firstPercent(e.getInterestRateText());
            if (roi != null) fill(w, "roiPct", SanctionValueParser.formatPct(roi));
        }

        BigDecimal base   = e.getBaseRatePct();
        BigDecimal spread = e.getSpreadPct();
        if (base != null && spread != null) {
            BigDecimal built = base.add(spread);
            if (roi == null) {
                fill(w, "roiPct", SanctionValueParser.formatPct(built));
            } else if (roi.subtract(built).abs().compareTo(ROI_TOLERANCE) > 0) {
                w.setDerivedRoiCheck("Does not reconcile — base + spread = "
                        + SanctionValueParser.formatPct(built));
            } else {
                w.setDerivedRoiCheck("Reconciles");
            }
        }

        // ── DSRA / ISRA amounts — editable, but start from the calculated figure ──
        // Same "printed wins, else computed" precedence as every gap-fill
        // above, except the "printed" side here is a reviewer's own typed
        // override (dsraAmount/israAmount) rather than something the letter
        // stated. Runs after apply() has already priced derivedDsraAmount/
        // derivedIsraAmount off the (corrected) reserve engine.
        fill(w, "dsraAmount", w.getDerivedDsraAmount());
        fill(w, "israAmount", w.getDerivedIsraAmount());
    }

    /** First "9.75%"-shaped figure in free text, or null if there isn't one. */
    private static final Pattern PERCENT = Pattern.compile("([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%");

    private static BigDecimal firstPercent(String text) {
        if (text == null) return null;
        Matcher m = PERCENT.matcher(text);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The interest rate actually in force: a directly stated roiPct wins,
     * else the first percentage printed in the free-text description, else
     * the base + spread build-up. Mirrors the precedence {@link #fillGaps}
     * applies when backfilling roiPct, so this figure and the persisted
     * roiPct are never inconsistent with each other.
     */
    private static BigDecimal resolveRoi(BorrowerSanctionEntity e) {
        BigDecimal roi = e.getRoiPct();
        if (roi == null) roi = firstPercent(e.getInterestRateText());
        if (roi == null && e.getBaseRatePct() != null && e.getSpreadPct() != null) {
            roi = e.getBaseRatePct().add(e.getSpreadPct());
        }
        return roi;
    }

    /**
     * Months in one repayment period for the sanction's repaymentFrequency —
     * the single place "3" (a quarter) used to be hardcoded at every DSRA/
     * ISRA/schedule call site. Mirrors the frontend's
     * repaymentFrequencyMonths() in sanctionDerive.js 1:1.
     *
     * <p>Returns null for OTHER with no valid custom interval set — callers
     * must skip pricing rather than silently falling back to quarterly, so
     * an incomplete "Other" selection never masks itself as a real answer.
     * Returns 3 (the interval every schedule used before this field existed)
     * for a null/unrecognised frequency, so old rows without the column
     * populated keep behaving exactly as they always did.
     */
    static Integer resolveMonthsPerPeriod(BorrowerSanctionEntity e) {
        String freq = e.getRepaymentFrequency();
        if (freq == null) return 3;
        return switch (freq) {
            case "MONTHLY" -> 1;
            case "BI_MONTHLY" -> 2;
            case "QUARTERLY" -> 3;
            case "HALF_YEARLY" -> 6;
            case "YEARLY" -> 12;
            case "OTHER" -> e.getRepaymentFrequencyOtherMonths() != null
                    && e.getRepaymentFrequencyOtherMonths() > 0 ? e.getRepaymentFrequencyOtherMonths() : null;
            default -> 3;
        };
    }

    /**
     * "[2.5,1.4,2.5,...]" → the same list, or null for blank/malformed JSON —
     * LoanReserveCalculator.buildQuarterEndSchedule already falls back to an
     * equal split whenever this is null (or its length no longer matches the
     * schedule), so a bad value here behaves exactly like no value at all.
     */
    private static List<BigDecimal> parseRepaymentProfile(String json) {
        if (SanctionValueParser.isBlank(json)) return null;
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) return null;
        List<BigDecimal> out = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            try {
                out.add(new BigDecimal(part.trim()));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    /** Write a wrapper field only if it is still empty, and note that we did. */
    private void fill(BorrowerSanctionWrapper w, String key, String value) {
        if (value == null) return;
        switch (key) {
            case "debtAmount"   -> { if (!SanctionValueParser.isBlank(w.getDebtAmount()))   return; w.setDebtAmount(value); }
            case "equityAmount" -> { if (!SanctionValueParser.isBlank(w.getEquityAmount())) return; w.setEquityAmount(value); }
            case "debtPct"      -> { if (!SanctionValueParser.isBlank(w.getDebtPct()))      return; w.setDebtPct(value); }
            case "equityPct"    -> { if (!SanctionValueParser.isBlank(w.getEquityPct()))    return; w.setEquityPct(value); }
            case "roiPct"       -> { if (!SanctionValueParser.isBlank(w.getRoiPct()))       return; w.setRoiPct(value); }
            case "dsraAmount"   -> { if (!SanctionValueParser.isBlank(w.getDsraAmount()))   return; w.setDsraAmount(value); }
            case "israAmount"   -> { if (!SanctionValueParser.isBlank(w.getIsraAmount()))   return; w.setIsraAmount(value); }
            default -> { return; }
        }
        w.getComputedFields().add(key);
    }

    /**
     * Check the printed debt:equity against what the two amounts actually imply.
     * A one-point tolerance absorbs the rounding real letters carry.
     */
    private String ratioCheck(BigDecimal cost, BigDecimal loan, String printed) {
        if (cost == null || loan == null || cost.signum() == 0) return null;

        BigDecimal debtPct = loan.multiply(BigDecimal.valueOf(100))
                                 .divide(cost, 1, RoundingMode.HALF_UP);

        if (SanctionValueParser.isBlank(printed)) {
            return "Implies " + debtPct.stripTrailingZeros().toPlainString() + " : "
                 + BigDecimal.valueOf(100).subtract(debtPct).stripTrailingZeros().toPlainString();
        }

        String[] parts = printed.split("[:/]");
        if (parts.length != 2) return "Could not read the printed ratio";

        try {
            BigDecimal statedDebt = new BigDecimal(parts[0].replaceAll("[^0-9.]", ""));
            if (statedDebt.subtract(debtPct).abs().compareTo(BigDecimal.ONE) <= 0) {
                return "Reconciles";
            }
            return "Does not reconcile — amounts imply "
                 + debtPct.stripTrailingZeros().toPlainString() + "% debt";
        } catch (Exception e) {
            return "Could not read the printed ratio";
        }
    }
}
