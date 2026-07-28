package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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

    public void apply(BorrowerSanctionEntity e, BorrowerSanctionWrapper w) {
        if (e == null || w == null) return;

        BigDecimal cost   = e.getProjectCost();
        BigDecimal loan   = e.getSanctionedAmount();
        LocalDate  signed = e.getSanctionDate();
        Integer    tenor  = e.getTenorMonths();
        Integer    mora   = e.getMoratoriumMonths();

        // ── equity contribution and the ratio it implies ──
        if (cost != null && loan != null) {
            BigDecimal equity = cost.subtract(loan);
            w.setDerivedEquityContribution(SanctionValueParser.formatCrore(equity));
            w.setDerivedRatioCheck(ratioCheck(cost, loan, e.getDebtEquityRatio()));
        }

        // ── the repayment window ──
        if (signed != null) {
            LocalDate moratoriumEnd = mora != null ? signed.plusMonths(mora) : signed;
            w.setDerivedMoratoriumEnd(SanctionValueParser.formatDate(moratoriumEnd));

            // First instalment falls one quarter after the moratorium ends,
            // matching the quarterly servicing these facilities assume.
            w.setDerivedRepaymentStart(SanctionValueParser.formatDate(moratoriumEnd.plusMonths(3)));

            if (tenor != null) {
                w.setDerivedRepaymentEnd(SanctionValueParser.formatDate(signed.plusMonths(tenor)));
            }
            w.setDerivedSanctionValidTill(
                    SanctionValueParser.formatDate(signed.plusMonths(DEFAULT_VALIDITY_MONTHS)));
        }

        if (tenor != null) {
            w.setDerivedTotalTenorMonths(tenor + " months");
        }

        // ── indicative first-year interest ──
        if (loan != null && e.getInterestRatePct() != null) {
            BigDecimal interest = loan
                    .multiply(e.getInterestRatePct())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            w.setDerivedFirstYearInterest(SanctionValueParser.formatCrore(interest) + " approx.");
        }

        // ── COD, the one derived value that moves on its own ──
        if (e.getScheduledCod() != null) {
            LocalDate today = LocalDate.now();
            long days = ChronoUnit.DAYS.between(e.getScheduledCod(), today);
            if (days > 0) {
                w.setDerivedCodStatus("Overdue by " + days + " day" + (days == 1 ? "" : "s"));
            } else if (days == 0) {
                w.setDerivedCodStatus("Due today");
            } else {
                w.setDerivedCodStatus("In " + (-days) + " days");
            }
        }
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

        // ── ROI = base rate + spread ──
        BigDecimal base   = e.getBaseRatePct();
        BigDecimal spread = e.getSpreadPct();
        if (base != null && spread != null) {
            BigDecimal built = base.add(spread);
            if (e.getRoiPct() == null) {
                fill(w, "roiPct", SanctionValueParser.formatPct(built));
            } else if (e.getRoiPct().subtract(built).abs().compareTo(ROI_TOLERANCE) > 0) {
                w.setDerivedRoiCheck("Does not reconcile — base + spread = "
                        + SanctionValueParser.formatPct(built));
            } else {
                w.setDerivedRoiCheck("Reconciles");
            }
        }
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
