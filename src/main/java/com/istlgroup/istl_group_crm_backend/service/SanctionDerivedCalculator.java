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
 * can update them live as the user edits. If you change a rule here, change it
 * there too — the backend value is the one that is persisted-adjacent and shown
 * on the detail page.
 */
@Component
public class SanctionDerivedCalculator {

    /** Letters in the sample set state a six-month validity. */
    private static final int DEFAULT_VALIDITY_MONTHS = 6;

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
