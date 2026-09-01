package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;

/**
 * The DSRA/ISRA reserve engine: a reducing-balance amortization schedule
 * (actual/365, equal principal, interest on the declining average balance),
 * and the reserve-period phrase parser that decides how many of its periods
 * a covenant is priced off.
 */
class LoanReserveCalculatorTest {

    private final LoanReserveCalculator calc = new LoanReserveCalculator();

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Nested
    @DisplayName("reserve-period phrase parsing")
    class ReservePeriods {

        @Test
        void wordNumberWithQuarters() {
            assertEquals(1, SanctionValueParser.parseReservePeriods("equivalent to one quarter's debt service", 3));
            assertEquals(2, SanctionValueParser.parseReservePeriods(
                    "shall maintain a Debt Service Reserve Account (DSRA) equivalent to the next two "
                            + "quarters' Scheduled Debt Service at all times from the date of first disbursement.",
                    3));
        }

        @Test
        void monthsConvertToPeriodsAtTheGivenFrequency() {
            assertEquals(2, SanctionValueParser.parseReservePeriods("6 months' interest service", 3));
            assertEquals(1, SanctionValueParser.parseReservePeriods("3 months' debt service", 3));
        }

        @Test
        void digitNumbersWorkToo() {
            assertEquals(3, SanctionValueParser.parseReservePeriods("3 quarters", 3));
        }

        @Test
        void nilIsAZeroReserveNotAMiss() {
            assertEquals(0, SanctionValueParser.parseReservePeriods("Nil", 3));
            assertEquals(0, SanctionValueParser.parseReservePeriods("Not required", 3));
        }

        @Test
        void blankOrUnrecognisableTextIsNeverGuessed() {
            assertNull(SanctionValueParser.parseReservePeriods("", 3));
            assertNull(SanctionValueParser.parseReservePeriods(null, 3));
            assertNull(SanctionValueParser.parseReservePeriods("As per RBI guidelines from time to time", 3));
        }
    }

    @Nested
    @DisplayName("amortization schedule")
    class Schedule {

        /**
         * No moratorium, four clean quarters over a leap year (2024) — chosen so
         * the actual/365 day counts (91, 91, 92, 92 = 366) land on a total
         * interest figure worth hand-checking exactly, not just eyeballing.
         */
        @Test
        void equalPrincipalWithDecliningInterestOverFourQuarters() {
            List<LoanReserveCalculator.Period> schedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 3);

            assertEquals(4, schedule.size());
            assertMoney("250000.00", schedule.get(0).principalDue());
            assertMoney("26178.08", schedule.get(0).interestDue());
            assertMoney("18698.63", schedule.get(1).interestDue());
            assertMoney("11342.47", schedule.get(2).interestDue());
            // Last period absorbs any rounding drift, so its principal still
            // closes the balance to exactly zero.
            assertMoney("250000.00", schedule.get(3).principalDue());
            assertMoney("3780.82", schedule.get(3).interestDue());

            BigDecimal totalInterest = schedule.stream()
                    .map(LoanReserveCalculator.Period::interestDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertMoney("60000.00", totalInterest);
        }

        /** During the interest-only phase, no principal moves and interest prices off the full balance. */
        @Test
        void interestOnlyPhaseChargesTheFullBalance() {
            List<LoanReserveCalculator.Period> schedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), LocalDate.of(2024, 7, 1), 3);

            assertEquals(2, schedule.size());

            LoanReserveCalculator.Period moratorium = schedule.get(0);
            assertMoney("0.00", moratorium.principalDue());
            assertMoney("29917.81", moratorium.interestDue());

            LoanReserveCalculator.Period firstInstalment = schedule.get(1);
            assertMoney("1000000.00", firstInstalment.principalDue());
            assertMoney("14958.90", firstInstalment.interestDue());
        }

        @Test
        void dsraIsPrincipalPlusInterestIsraIsInterestOnly() {
            List<LoanReserveCalculator.Period> schedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 3);

            assertMoney("544876.71", calc.sumDebtService(schedule, 2));
            assertMoney("44876.71", calc.sumInterest(schedule, 2));
        }

        /**
         * A moratorium precedes repayment here — two interest-only quarters
         * (Jan-Apr, Apr-Jul 2024), each carrying the full ₹1,000,000 balance
         * at 12%, 91 days: 1,000,000 × 0.12 × 91 / 365 = 29,917.81 each (the
         * same figure {@code interestOnlyPhaseChargesTheFullBalance} above
         * already pins). {@code sumDebtService}/{@code sumInterest} for the
         * first 2 periods must skip straight past those two and price off
         * the first 2 real instalments instead — before this fix they
         * indexed {@code schedule} from the start, so a DSRA/ISRA period
         * that fit inside the moratorium collapsed DSRA to exactly
         * {@code sumInterest}'s figure (59,835.62 either way here), silently
         * losing the principal component the reserve is meant to cover.
         */
        @Test
        void reservePeriodsSkipTheModeratoriumAndPriceOffRealInstalments() {
            List<LoanReserveCalculator.Period> schedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 7, 1), LocalDate.of(2025, 7, 1), 3);

            assertEquals(6, schedule.size());
            assertMoney("0.00", schedule.get(0).principalDue());
            assertMoney("29917.81", schedule.get(0).interestDue());
            assertMoney("0.00", schedule.get(1).principalDue());
            assertMoney("29917.81", schedule.get(1).interestDue());

            // First 2 real instalments (Jul-Oct, Oct-Jan): 250,000 principal
            // each, interest 26,465.75 then 18,904.11 on the declining balance.
            assertMoney("545369.86", calc.sumDebtService(schedule, 2));
            assertMoney("45369.86", calc.sumInterest(schedule, 2));
        }

        @Test
        void aReservePeriodLongerThanTheScheduleJustCapsAtTheEnd() {
            List<LoanReserveCalculator.Period> schedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 3);

            assertEquals(calc.sumDebtService(schedule, 4), calc.sumDebtService(schedule, 100));
        }

        @Test
        void missingInputsReturnAnEmptySchedule() {
            assertTrue(calc.buildSchedule(null, new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 3).isEmpty());
        }
    }

    /**
     * {@code buildQuarterEndSchedule} — the Repayment Schedule tab's own
     * variant. Never called by {@code SanctionDerivedCalculator}; {@code
     * buildSchedule} above keeps its day-preserving dates for every existing
     * DSRA/ISRA figure, unchanged.
     */
    @Nested
    @DisplayName("quarter-end schedule and capitalization")
    class QuarterEndScheduleAndCapitalization {

        /** Every instalment date lands on the end of its month, not the day-of-month of the previous one. */
        @Test
        void quarterEndDatesSnapToMonthEnd() {
            List<LoanReserveCalculator.Period> schedule = calc.buildQuarterEndSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 3, false);

            assertEquals(4, schedule.size());
            assertEquals(LocalDate.of(2024, 4, 30), schedule.get(0).end());
            assertEquals(LocalDate.of(2024, 7, 31), schedule.get(1).end());
            assertEquals(LocalDate.of(2024, 10, 31), schedule.get(2).end());
            // The final period always closes exactly on repayEnd, regardless
            // of month-end stepping.
            assertEquals(LocalDate.of(2025, 1, 1), schedule.get(3).end());

            BigDecimal totalPrincipal = schedule.stream()
                    .map(LoanReserveCalculator.Period::principalDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertMoney("1000000.00", totalPrincipal);
        }

        /**
         * Capitalization folds the moratorium's own accrued interest into the
         * principal the amortizing phase repays — never a hardcoded figure,
         * derived from the exact same moratorium leg
         * {@code interestOnlyPhaseChargesTheFullBalance} already pins at
         * 29,917.81 (same debt/rate/date range, so it reproduces unchanged
         * regardless of the amortizing phase's own EOMONTH stepping).
         *
         * <p>The total lands on 1,029,917.80, one cent short of the
         * mathematically "pure" 1,029,917.81 — each of the four equal
         * instalments (257,479.4525) rounds down independently to
         * 257,479.45, and four independent roundings don't sum back to the
         * unrounded total. This is a real, expected artifact of pricing
         * every period to the cent, not a bug — the same rounding rule
         * {@code buildSchedule}'s own pinned tests already rely on
         * elsewhere.
         */
        @Test
        void capitalizationAddsTheMoratoriumsOwnInterestToThePrincipal() {
            List<LoanReserveCalculator.Period> schedule = calc.buildQuarterEndSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), LocalDate.of(2025, 4, 1), 3, true);

            assertMoney("29917.81", schedule.get(0).interestDue());

            BigDecimal totalPrincipal = schedule.stream()
                    .map(LoanReserveCalculator.Period::principalDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertMoney("1029917.80", totalPrincipal);
        }

        /** Serviced (the default): the amortizing principal never grows, whatever interest accrued during the moratorium. */
        @Test
        void servicedInterestNeverTouchesPrincipal() {
            List<LoanReserveCalculator.Period> schedule = calc.buildQuarterEndSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), LocalDate.of(2025, 4, 1), 3, false);

            BigDecimal totalPrincipal = schedule.stream()
                    .map(LoanReserveCalculator.Period::principalDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertMoney("1000000.00", totalPrincipal);
        }

        /**
         * A single-instalment, no-moratorium schedule never invokes the
         * EOMONTH stepping at all (its one period's end is repayEnd itself,
         * taken verbatim) — so buildSchedule and buildQuarterEndSchedule
         * produce byte-identical output here, proving both call the same
         * underlying interest arithmetic rather than two copies that happen
         * to agree today.
         */
        @Test
        void reusesTheSameInterestFormulaAsBuildSchedule() {
            List<LoanReserveCalculator.Period> viaBuildSchedule = calc.buildSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), 3);
            List<LoanReserveCalculator.Period> viaQuarterEnd = calc.buildQuarterEndSchedule(
                    new BigDecimal("1000000"), new BigDecimal("12"),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), 3, false);

            assertEquals(viaBuildSchedule, viaQuarterEnd);
        }
    }

    @Nested
    @DisplayName("wired into SanctionDerivedCalculator")
    class EndToEnd {

        private final SanctionDerivedCalculator derived = new SanctionDerivedCalculator();

        /**
         * The Meghdoot letter: Sanction Date 02 Jul 2025, 17y tenor incl. a
         * 9-month moratorium, 10.10% ROI, Rs. 172.20 Cr debt, DSRA equivalent
         * to "the next two quarters' Scheduled Debt Service", no ISRA clause.
         */
        /**
         * No ISRA clause in this letter — so derivedIsraAmount is not blank,
         * but the calculated interest component of the DSRA reserve instead,
         * clearly marked as not contractual. This is a deliberate change from
         * the earlier behavior (which left derivedIsraAmount null here) — see
         * SanctionDerivedCalculator.applyReserves.
         */
        @Test
        void dsraOnlyLetterGetsTheCalculatedInterestComponentOfDsraAsIsra() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            // The repayment window is anchored on COD; no Actual COD Date is
            // set here, so the planned one stands in for it.
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("Debt Service Reserve Account (DSRA) equivalent to the next two quarters' "
                    + "Scheduled Debt Service at all times from the date of first disbursement");
            // isra intentionally left unset — no ISRA clause in this letter.

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertTrue(w.getDerivedDsraAmount() != null && w.getDerivedDsraAmount().startsWith("₹"),
                    "derivedDsraAmount was: " + w.getDerivedDsraAmount());
            assertTrue(w.getDerivedIsraAmount() != null && w.getDerivedIsraAmount().startsWith("₹"),
                    "derivedIsraAmount was: " + w.getDerivedIsraAmount());
            assertEquals(Boolean.FALSE, w.getDerivedIsraIsContractual());
        }

        /** DSRA text is present but not a recognisable reserve-period phrase — flagged, not blank. */
        @Test
        void dsraTextThatDoesNotParseIsFlaggedNotCalculated() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("As per RBI guidelines from time to time");

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("Not Calculated", w.getDerivedDsraAmount());
            assertNull(w.getDerivedIsraIsContractual());
        }

        /** No DSRA text at all stays a dash — never confused with "present but unparseable". */
        @Test
        void blankDsraStaysADashNotNotCalculated() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            // dsra intentionally left unset.

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertNull(w.getDerivedDsraAmount());
        }

        /**
         * When the letter states BOTH covenants, ISRA prices off its own
         * period count — one quarter's interest, not the two quarters' worth
         * DSRA is stated over — and is marked contractual.
         */
        @Test
        void contractualIsraWinsOverTheDsraFallback() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("two quarters' Scheduled Debt Service");
            e.setIsra("one quarter's Interest Service");

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals(Boolean.TRUE, w.getDerivedIsraIsContractual());
            assertTrue(w.getDerivedIsraAmount() != null && w.getDerivedIsraAmount().startsWith("₹"),
                    "derivedIsraAmount was: " + w.getDerivedIsraAmount());
        }

        /** An ISRA clause that's present but unparseable never falls back to DSRA's figure. */
        @Test
        void unparseableIsraNeverFallsBackToDsra() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("two quarters' Scheduled Debt Service");
            e.setIsra("as mutually agreed between the parties");

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("Not Calculated", w.getDerivedIsraAmount());
            assertNull(w.getDerivedIsraIsContractual());
        }

        @Test
        void anExplicitNilClauseReadsAsNilNotAMissingValue() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("One quarter's Scheduled Debt Service");
            e.setIsra("Nil");

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("Nil", w.getDerivedIsraAmount());
        }

        /**
         * dsraAmount/israAmount are the reviewer-editable figures — blank
         * until someone types over the suggestion, at which point fillGaps
         * (mirroring debtAmount/equityAmount's own precedence) leaves the
         * saved override alone instead of recalculating over it.
         */
        @Test
        void dsraAndIsraAmountsGapFillFromTheCalculatedFigureUntilManuallyOverridden() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setTenorMonths(204);
            e.setMoratoriumMonths(9);
            e.setRoiPct(new BigDecimal("10.10"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("172.20"));
            e.setDsra("two quarters' Scheduled Debt Service");
            e.setIsra("one quarter's Interest Service");

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);
            derived.fillGaps(e, w);

            assertEquals(w.getDerivedDsraAmount(), w.getDsraAmount());
            assertEquals(w.getDerivedIsraAmount(), w.getIsraAmount());

            // A reviewer's own figure, once saved, is printed and wins —
            // fillGaps never overwrites a non-blank value. Mirrors
            // BorrowerService.toWrapper's own mapping order: the entity's
            // persisted override is copied onto the wrapper before apply()/
            // fillGaps() run, exactly as production code does.
            e.setDsraAmount(new BigDecimal("999999.00"));
            BorrowerSanctionWrapper w2 = new BorrowerSanctionWrapper();
            w2.setDsraAmount(SanctionValueParser.formatCrore(e.getDsraAmount()));
            derived.apply(e, w2);
            derived.fillGaps(e, w2);

            assertEquals("₹0.10 Cr", w2.getDsraAmount());
            assertNotEquals(w2.getDerivedDsraAmount(), w2.getDsraAmount());
        }

        /**
         * Policy choice, not a data gap: until a real Actual COD Date is
         * entered, the planned date stands in for it, so status reads
         * "Achieved on ... (on schedule)" rather than "Overdue".
         */
        @Test
        void plannedCodStandsInForActualUntilOneIsRecorded() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            // actualCod intentionally left unset.

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("02 Jul 2026", w.getDerivedActualCod());
            assertEquals("Achieved on 02 Jul 2026 (on schedule)", w.getDerivedCodStatus());
        }

        @Test
        void aRealActualCodStillWinsAndReportsVarianceAgainstThePlan() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 7, 2));
            e.setScheduledCod(LocalDate.of(2026, 7, 2));
            e.setActualCod(LocalDate.of(2026, 7, 20));

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("20 Jul 2026", w.getDerivedActualCod());
            assertEquals("Achieved on 20 Jul 2026 (18 days late)", w.getDerivedCodStatus());
        }

        /**
         * The modelled repayment window is anchored on COD, not the sanction
         * date — moratorium is a holiday measured from the project's revenue
         * start. Repayment start = effective COD + moratorium (no extra gap);
         * repayment end = repayment start + the remaining amortizing tenor.
         */
        @Test
        void repaymentWindowIsAnchoredOnEffectiveCodNotSanctionDate() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setSanctionDate(LocalDate.of(2025, 1, 1));
            e.setScheduledCod(LocalDate.of(2026, 1, 1));
            e.setTenorMonths(180);
            e.setMoratoriumMonths(6);

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            derived.apply(e, w);

            assertEquals("01 Jul 2026", w.getDerivedMoratoriumEnd());
            assertEquals("01 Jul 2026", w.getDerivedRepaymentStart());
            assertEquals("01 Jan 2041", w.getDerivedRepaymentEnd());
        }
    }
}
