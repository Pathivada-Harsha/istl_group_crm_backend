package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * A reducing-balance loan amortization schedule, and the sums a DSRA/ISRA
 * reserve requirement is priced off. Generic over servicing frequency and
 * loan-specific values on purpose — nothing here is sanction-letter-shaped;
 * {@link SanctionDerivedCalculator} is what maps letter fields onto its
 * inputs. Stateless: callers always pass the schedule they want summed, so a
 * future feature that tracks actual repayments could recompute against a
 * later starting balance without any change here.
 */
@Component
public class LoanReserveCalculator {

    private static final int DAYS_IN_YEAR = 365;

    /** One step of the schedule. Interest-only periods carry a zero principalDue. */
    public record Period(LocalDate start, LocalDate end, BigDecimal principalDue, BigDecimal interestDue) {
        public BigDecimal scheduledDebtService() {
            return principalDue.add(interestDue);
        }
    }

    /**
     * Interest-only on the outstanding balance while {@code [start, repayStart)},
     * then equal-principal instalments with interest on the declining balance
     * from {@code repayStart} to {@code repayEnd}. Every period's interest is
     * {@code averageBalance × annualRoiPct/100 × actualDays/365} — a real
     * actual/365 reducing-balance calculation, not a flat estimate.
     *
     * <p>Interest-served moratorium (the existing behaviour every caller before
     * capitalization support relied on) — the amortizing phase always repays
     * {@code debtAmount} untouched.
     */
    public List<Period> buildSchedule(BigDecimal debtAmount, BigDecimal annualRoiPct,
            LocalDate start, LocalDate repayStart, LocalDate repayEnd, int monthsPerPeriod) {
        return buildSchedule(debtAmount, annualRoiPct, start, repayStart, repayEnd, monthsPerPeriod, false);
    }

    /**
     * Same as the six-argument {@link #buildSchedule}, with the moratorium's
     * own accrued interest optionally folded into the balance the amortizing
     * phase repays — the DSRA/ISRA-pricing counterpart of what
     * {@link #buildQuarterEndSchedule} already does for the Repayment
     * Schedule tab, so a Capitalized sanction reserves against the larger
     * balance it will actually owe once repayment starts, not the
     * pre-capitalization principal.
     */
    public List<Period> buildSchedule(BigDecimal debtAmount, BigDecimal annualRoiPct,
            LocalDate start, LocalDate repayStart, LocalDate repayEnd, int monthsPerPeriod,
            boolean capitalizeMoratoriumInterest) {
        List<Period> schedule = new ArrayList<>();
        if (debtAmount == null || annualRoiPct == null || start == null
                || repayStart == null || repayEnd == null || !repayEnd.isAfter(start)) {
            return schedule;
        }

        BigDecimal rate = annualRoiPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal balance = debtAmount;

        // ── interest-only phase: moratorium plus the gap to the first instalment ──
        LocalDate cursor = start;
        while (cursor.isBefore(repayStart)) {
            LocalDate next = cursor.plusMonths(monthsPerPeriod);
            if (next.isAfter(repayStart)) next = repayStart;
            schedule.add(interestOnlyPeriod(cursor, next, balance, rate));
            cursor = next;
        }

        // Capitalize: fold the moratorium's own accrued interest into the
        // balance the amortizing phase repays, same formula
        // buildQuarterEndSchedule uses for the Repayment Schedule tab.
        if (capitalizeMoratoriumInterest) {
            BigDecimal moratoriumInterest = schedule.stream()
                    .map(Period::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
            balance = balance.add(moratoriumInterest);
        }

        // ── amortizing phase: equal principal, interest on the declining balance ──
        int amortizingPeriods = countPeriods(repayStart, repayEnd, monthsPerPeriod);
        if (amortizingPeriods > 0) {
            BigDecimal principalPerPeriod = balance.divide(
                    BigDecimal.valueOf(amortizingPeriods), 10, RoundingMode.HALF_UP);
            cursor = repayStart;
            for (int i = 0; i < amortizingPeriods; i++) {
                boolean last = i == amortizingPeriods - 1;
                LocalDate next = last ? repayEnd : cursor.plusMonths(monthsPerPeriod);
                // The last instalment absorbs whatever rounding drift is left,
                // so the schedule always closes the balance to exactly zero.
                BigDecimal principal = last ? balance : principalPerPeriod;
                schedule.add(amortizingPeriod(cursor, next, balance, principal, rate));
                balance = balance.subtract(principal);
                cursor = next;
            }
        }
        return schedule;
    }

    /**
     * Same reducing-balance schedule as {@link #buildSchedule}, but for the
     * Repayment Schedule tab: EOMONTH-style period-end dates instead of
     * buildSchedule's day-preserving plusMonths stepping. Equal-split
     * principal — {@code repaymentPercents} is null, or its length no longer
     * matches the schedule (e.g. after a frequency change).
     */
    public List<Period> buildQuarterEndSchedule(BigDecimal debtAmount, BigDecimal annualRoiPct,
            LocalDate start, LocalDate repayStart, LocalDate repayEnd, int monthsPerPeriod,
            boolean capitalizeMoratoriumInterest) {
        return buildQuarterEndSchedule(debtAmount, annualRoiPct, start, repayStart, repayEnd,
                monthsPerPeriod, capitalizeMoratoriumInterest, null);
    }

    /**
     * The single engine both the Repayment Schedule tab and the DSRA/ISRA
     * point figures are priced off — one calculation, not two that could
     * silently disagree. EOMONTH-style period-end dates (matching the
     * reference Excel's own {@code EOMONTH()} stepping exactly, month-end to
     * month-end regardless of how many days each month holds).
     *
     * <p>Principal is {@code amortizingBaseAmount × repaymentPercents[i] /
     * 100} for every period, never a division of the declining balance —
     * {@code amortizingBaseAmount} is {@code debtAmount}, or the capitalized
     * balance when {@code capitalizeMoratoriumInterest} folded the
     * moratorium's own interest into it first, so a Capitalized sanction's
     * percentages are still applied against what it will actually owe once
     * repayment starts. {@code repaymentPercents} is the reviewer's own
     * profile when its length matches the schedule; otherwise (null, or a
     * stale length after e.g. a frequency change) an equal 100/N split is
     * generated here, the last period absorbing the remainder exactly as the
     * reference Excel's own final-row formula does — never a fixed
     * percentage nobody actually set.
     */
    public List<Period> buildQuarterEndSchedule(BigDecimal debtAmount, BigDecimal annualRoiPct,
            LocalDate start, LocalDate repayStart, LocalDate repayEnd, int monthsPerPeriod,
            boolean capitalizeMoratoriumInterest, List<BigDecimal> repaymentPercents) {
        List<Period> schedule = new ArrayList<>();
        if (debtAmount == null || annualRoiPct == null || start == null
                || repayStart == null || repayEnd == null || !repayEnd.isAfter(start)) {
            return schedule;
        }

        BigDecimal rate = annualRoiPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal balance = debtAmount;

        LocalDate cursor = start;
        while (cursor.isBefore(repayStart)) {
            LocalDate next = cursor.plusMonths(monthsPerPeriod).with(TemporalAdjusters.lastDayOfMonth());
            if (next.isAfter(repayStart)) next = repayStart;
            schedule.add(interestOnlyPeriod(cursor, next, balance, rate));
            cursor = next;
        }

        // Capitalize: fold the moratorium's own accrued interest into the
        // balance the amortizing phase repays — and prices its percentages
        // against. Never hardcoded — it's the sum of the interest-only
        // periods just built above, from the same interest formula every
        // other period in this schedule uses.
        if (capitalizeMoratoriumInterest) {
            BigDecimal moratoriumInterest = schedule.stream()
                    .map(Period::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
            balance = balance.add(moratoriumInterest);
        }
        BigDecimal amortizingBaseAmount = balance;

        int amortizingPeriods = countPeriods(repayStart, repayEnd, monthsPerPeriod);
        if (amortizingPeriods > 0) {
            List<BigDecimal> percents = (repaymentPercents != null && repaymentPercents.size() == amortizingPeriods)
                    ? repaymentPercents : defaultRepaymentPercents(amortizingPeriods);
            cursor = repayStart;
            for (int i = 0; i < amortizingPeriods; i++) {
                boolean last = i == amortizingPeriods - 1;
                LocalDate next = last ? repayEnd
                        : cursor.plusMonths(monthsPerPeriod).with(TemporalAdjusters.lastDayOfMonth());
                BigDecimal principal = amortizingBaseAmount
                        .multiply(percents.get(i)).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                schedule.add(amortizingPeriod(cursor, next, balance, principal, rate));
                balance = balance.subtract(principal);
                cursor = next;
            }
        }
        return schedule;
    }

    /**
     * Equal 100/N split: 100/periods for every period except the last, which
     * absorbs whatever remains so the total is always exactly 100 — mirrors
     * the reference Excel's own final-row {@code =100%-SUM(...)} formula
     * rather than a fixed final percentage. Only used when the reviewer
     * hasn't entered (or has invalidated, e.g. via a frequency change) a
     * repayment percentage profile of their own.
     */
    public List<BigDecimal> defaultRepaymentPercents(int periods) {
        List<BigDecimal> out = new ArrayList<>();
        if (periods <= 0) return out;
        BigDecimal share = BigDecimal.valueOf(100).divide(BigDecimal.valueOf(periods), 10, RoundingMode.HALF_UP);
        BigDecimal sumOfOthers = BigDecimal.ZERO;
        for (int i = 0; i < periods - 1; i++) {
            out.add(share);
            sumOfOthers = sumOfOthers.add(share);
        }
        out.add(BigDecimal.valueOf(100).subtract(sumOfOthers));
        return out;
    }

    private Period interestOnlyPeriod(LocalDate from, LocalDate to, BigDecimal balance, BigDecimal rate) {
        long days = ChronoUnit.DAYS.between(from, to);
        BigDecimal interest = balance.multiply(rate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(DAYS_IN_YEAR), 2, RoundingMode.HALF_UP);
        return new Period(from, to, BigDecimal.ZERO, interest);
    }

    private Period amortizingPeriod(LocalDate cursor, LocalDate next,
            BigDecimal opening, BigDecimal principal, BigDecimal rate) {
        BigDecimal closing = opening.subtract(principal);
        BigDecimal avgBalance = opening.add(closing).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(cursor, next);
        BigDecimal interest = avgBalance.multiply(rate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(DAYS_IN_YEAR), 2, RoundingMode.HALF_UP);
        return new Period(cursor, next, principal.setScale(2, RoundingMode.HALF_UP), interest);
    }

    private int countPeriods(LocalDate from, LocalDate to, int monthsPerPeriod) {
        long months = ChronoUnit.MONTHS.between(from, to);
        return (int) Math.max(1, Math.round(months / (double) monthsPerPeriod));
    }

    /**
     * Sum of Principal + Interest (Scheduled Debt Service) over the first N
     * periods once repayment actually begins — the DSRA input.
     *
     * <p>Deliberately not the first N periods of {@code schedule} outright:
     * when a moratorium precedes {@code repayStart}, those leading periods
     * carry zero principal, so counting them would price the "debt service"
     * reserve off pure interest-only periods — silently collapsing it to the
     * same figure as {@link #sumInterest}, understating the real reserve a
     * lender needs once instalments start. {@link #amortizingOnly} skips
     * straight to the periods that actually carry principal.
     */
    public BigDecimal sumDebtService(List<Period> schedule, int periods) {
        return sum(amortizingOnly(schedule), periods, Period::scheduledDebtService);
    }

    /** Sum of Interest only over the first N periods once repayment actually begins — the ISRA input. */
    public BigDecimal sumInterest(List<Period> schedule, int periods) {
        return sum(amortizingOnly(schedule), periods, Period::interestDue);
    }

    /** The interest-only (moratorium) leg of a schedule carries no principal and isn't real debt service yet. */
    private List<Period> amortizingOnly(List<Period> schedule) {
        return schedule.stream().filter(p -> p.principalDue().signum() > 0).toList();
    }

    private BigDecimal sum(List<Period> schedule, int periods, Function<Period, BigDecimal> pick) {
        BigDecimal total = BigDecimal.ZERO;
        int n = Math.min(periods, schedule.size());
        for (int i = 0; i < n; i++) {
            total = total.add(pick.apply(schedule.get(i)));
        }
        return total;
    }
}
