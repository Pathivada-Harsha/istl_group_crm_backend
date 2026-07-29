package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.BorrowerSanctionEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BorrowerSanctionWrapper;

/**
 * The registry-sheet columns, in and out.
 *
 * <p>Two things are worth pinning down here rather than discovering in
 * production. First, the crore convention: the sheet quotes money in crore, so
 * a bare number scales by 10⁷ — and getting that wrong is silent, not loud.
 * Second, the printed-wins rule: a value the letter stated must survive
 * untouched, a value it omitted may be worked out, and a blank must never
 * become a zero.
 */
class SanctionRegistryValueTest {

    @Nested
    @DisplayName("money quoted in crore")
    class Money {

        @Test
        void bareNumberIsCrore() {
            assertEquals(new BigDecimal("1537500000.00"),
                    SanctionValueParser.parseMoneyCrore("153.75"));
        }

        @Test
        void explicitUnitWins() {
            assertEquals(new BigDecimal("1537500000.00"),
                    SanctionValueParser.parseMoneyCrore("Rs. 153.75 Crore"));
            assertEquals(new BigDecimal("20000000.00"),
                    SanctionValueParser.parseMoneyCrore("200 Lakh"));
        }

        /** Nothing here costs a hundred thousand crore, so this was rupees. */
        @Test
        void alreadyInRupeesIsLeftAlone() {
            assertEquals(new BigDecimal("1537500000.00"),
                    SanctionValueParser.parseMoneyCrore("1537500000"));
            assertEquals(new BigDecimal("20500000.00"),
                    SanctionValueParser.parseMoneyCrore("2,05,00,000"));
        }

        @Test
        void roundTripsThroughTheDisplayForm() {
            BigDecimal rupees = SanctionValueParser.parseMoneyCrore("153.75");
            assertEquals("₹153.75 Cr", SanctionValueParser.formatCrore(rupees));
        }

        @Test
        void aMissIsNullNotZero() {
            assertNull(SanctionValueParser.parseMoneyCrore(""));
            assertNull(SanctionValueParser.parseMoneyCrore("to be advised"));
        }
    }

    @Nested
    @DisplayName("percentages and multiples")
    class Rates {

        /** A bare number is what a value copied off the sheet looks like. */
        @Test
        void parsesWithAndWithoutTheSign() {
            assertEquals(new BigDecimal("75"), SanctionValueParser.parsePct("75"));
            assertEquals(new BigDecimal("9.75"), SanctionValueParser.parsePct("9.75%"));
            assertEquals(new BigDecimal("7.25"), SanctionValueParser.parsePct("7.25 % p.a."));
        }

        /** Zero is a real spread on a concessional facility, not a blank. */
        @Test
        void zeroSurvivesAsAValue() {
            BigDecimal zero = SanctionValueParser.parsePct("0");
            assertEquals(BigDecimal.ZERO, zero.stripTrailingZeros());
            assertEquals("0%", SanctionValueParser.formatPct(zero));
        }

        @Test
        void trailingZerosAreTrimmedOnRender() {
            assertEquals("9.75%", SanctionValueParser.formatPct(new BigDecimal("9.750")));
            assertEquals("75%", SanctionValueParser.formatPct(new BigDecimal("75.000")));
            assertEquals("100%", SanctionValueParser.formatPct(new BigDecimal("100.000")));
        }

        @Test
        void coverageMultipleDropsAndRegainsItsUnit() {
            assertEquals(new BigDecimal("1.12"), SanctionValueParser.parseMultiple("1.12x"));
            assertEquals(new BigDecimal("1.12"), SanctionValueParser.parseMultiple("1.12 times"));
            assertEquals("1.12x", SanctionValueParser.formatMultiple(new BigDecimal("1.120")));
        }

        @Test
        void aMissIsNullNotZero() {
            assertNull(SanctionValueParser.parsePct(""));
            assertNull(SanctionValueParser.parseMultiple("as per covenant"));
        }
    }

    @Nested
    @DisplayName("printed wins, computed fills the gap")
    class FillGaps {

        private final SanctionDerivedCalculator calc = new SanctionDerivedCalculator();

        private BorrowerSanctionEntity entity() {
            BorrowerSanctionEntity e = new BorrowerSanctionEntity();
            e.setProjectCost(SanctionValueParser.parseMoneyCrore("205"));
            e.setSanctionedAmount(SanctionValueParser.parseMoneyCrore("153.75"));
            return e;
        }

        @Test
        void debtAndEquityFollowFromCostAndLoan() {
            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            calc.fillGaps(entity(), w);

            assertEquals("₹153.75 Cr", w.getDebtAmount());
            assertEquals("₹51.25 Cr", w.getEquityAmount());
            assertEquals("75%", w.getDebtPct());
            assertEquals("25%", w.getEquityPct());
            assertTrue(w.getComputedFields().containsAll(
                    java.util.List.of("debtAmount", "equityAmount", "debtPct", "equityPct")));
        }

        @Test
        void aPrintedDebtIsNotOverwritten() {
            BorrowerSanctionEntity e = entity();
            e.setDebtAmount(SanctionValueParser.parseMoneyCrore("120"));

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            w.setDebtAmount("₹120.00 Cr");           // as toWrapper would have set it
            calc.fillGaps(e, w);

            assertEquals("₹120.00 Cr", w.getDebtAmount());
            assertTrue(w.getComputedFields().stream().noneMatch("debtAmount"::equals));
        }

        @Test
        void roiIsBuiltFromBasePlusSpreadWhenTheLetterIsSilent() {
            BorrowerSanctionEntity e = entity();
            e.setBaseRatePct(new BigDecimal("7.25"));
            e.setSpreadPct(new BigDecimal("2.50"));

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            calc.fillGaps(e, w);

            assertEquals("9.75%", w.getRoiPct());
            assertTrue(w.getComputedFields().contains("roiPct"));
            assertNull(w.getDerivedRoiCheck());   // nothing printed to disagree with
        }

        @Test
        void aPrintedRoiThatAgreesReconciles() {
            BorrowerSanctionEntity e = entity();
            e.setBaseRatePct(new BigDecimal("7.25"));
            e.setSpreadPct(new BigDecimal("2.50"));
            e.setRoiPct(new BigDecimal("9.75"));

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            w.setRoiPct("9.75%");
            calc.fillGaps(e, w);

            assertEquals("Reconciles", w.getDerivedRoiCheck());
            assertEquals("9.75%", w.getRoiPct());
        }

        @Test
        void aPrintedRoiThatDisagreesIsFlaggedButKept() {
            BorrowerSanctionEntity e = entity();
            e.setBaseRatePct(new BigDecimal("7.25"));
            e.setSpreadPct(new BigDecimal("2.50"));
            e.setRoiPct(new BigDecimal("9.50"));

            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            w.setRoiPct("9.5%");
            calc.fillGaps(e, w);

            assertEquals("9.5%", w.getRoiPct());   // the letter still wins
            assertEquals("Does not reconcile — base + spread = 9.75%", w.getDerivedRoiCheck());
        }

        /**
         * The modelled repayment window is a different question from the
         * contractual one, and fillGaps must not quietly answer both.
         */
        @Test
        void modelledRepaymentDatesAreLeftAlone() {
            BorrowerSanctionWrapper w = new BorrowerSanctionWrapper();
            w.setDerivedRepaymentStart("30 Sep 2026");
            calc.fillGaps(entity(), w);
            assertEquals("30 Sep 2026", w.getDerivedRepaymentStart());
        }
    }

    @Nested
    @DisplayName("reading the registry-sheet columns out of a letter")
    class PdfExtraction {

        private final SanctionDocExtractor extractor = new SanctionDocExtractor();

        /** A letter laid out the way the registry sheet is. */
        private static final String LETTER = """
            Vindhya Infra Finance Ltd.
            Ref. No.: VIFL/PF/2025/1007 Date: 14 March 2025
            Borrower: Rising Sun Solar Pvt Ltd Project: 50 MWac ground-mounted solar
            Category: Utility-Scale Solar Location: Jodhpur, Rajasthan
            Total Project Cost: Rs. 205.00 Crore
            Debt: Rs. 153.75 Crore Equity: Rs. 51.25 Crore
            Debt (%): 75% Equity (%): 25%
            Base Rate: 7.25% Spread: 2.50% ROI: 9.75%
            Technology: Solar PV Village: Bhadla District: Jodhpur State: Rajasthan
            Instrument: Rupee Term Loan Security
            Co-Obligators: Rising Sun Holdings Pvt Ltd Pledge of shares of the borrower: 75%
            Min. DSCR: 1.12x
            DSRA: equivalent to one quarter's debt service ISRA: Nil
            Cash Sweep: 100% above 1.30x DSCR Sanction Date
            Disb. Date: 30 April 2025
            Repayment Start Date: 30 September 2026
            Repayment End date: 30 September 2041
            PLF: 24.5% Tariff: Rs. 2.53 per kWh
            """;

        @Test
        void readsTheNewColumns() {
            var m = extractor.extractFromPdfText(LETTER);

            assertEquals("Rs. 153.75 Crore", m.get("debtAmount"));
            assertEquals("Rs. 51.25 Crore", m.get("equityAmount"));
            assertEquals("75", m.get("debtPct"));
            assertEquals("25", m.get("equityPct"));
            assertEquals("7.25", m.get("baseRatePct"));
            assertEquals("2.50", m.get("spreadPct"));
            assertEquals("9.75", m.get("roiPct"));
            assertEquals("Solar PV", m.get("technology"));
            assertEquals("Bhadla", m.get("village"));
            assertEquals("Jodhpur", m.get("district"));
            assertEquals("Rupee Term Loan", m.get("instrument"));
            assertEquals("75", m.get("pledgeOfSharesPct"));
            assertEquals("1.12", m.get("minDscr"));
            assertEquals("Nil", m.get("isra"));
            assertEquals("30 April 2025", m.get("disbursementDate"));
            assertEquals("30 September 2026", m.get("repaymentStartDate"));
            assertEquals("30 September 2041", m.get("repaymentEndDate"));
            assertEquals("24.5", m.get("plfPct"));
        }

        /** The columns that were already read must not have moved. */
        @Test
        void stillReadsTheOriginalFields() {
            var m = extractor.extractFromPdfText(LETTER);

            assertEquals("VIFL/PF/2025/1007", m.get("refNo"));
            assertEquals("14 March 2025", m.get("sanctionDate"));
            assertEquals("Rs. 205.00 Crore", m.get("projectCost"));
            assertEquals("Vindhya Infra Finance Ltd.", m.get("lenderName"));
        }

        /**
         * The registry sheet heads the reference column "SL Ref. No" while the
         * letter calls it the reference number. One field, both spellings — a
         * second column would only give the same number two places to disagree.
         */
        @Test
        void slRefNoIsTheSameFieldAsRefNo() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                SL Ref. No: SL/2025/014
                Borrower: Quiet Co Project: A project
                """);

            assertEquals("SL/2025/014", m.get("refNo"));
            assertTrue(!m.containsKey("slRefNo"));
        }

        /**
         * A column the letter does not carry has no key at all. That absence is
         * what becomes a blank cell in the registry — never a zero, and never a
         * value inferred from a neighbouring field.
         */
        @Test
        void aColumnTheLetterOmitsIsAbsentEntirely() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                """);

            assertTrue(m.get("plfPct") == null && !m.containsKey("plfPct"));
            assertTrue(m.get("minDscr") == null && !m.containsKey("minDscr"));
            assertTrue(m.get("debtAmount") == null && !m.containsKey("debtAmount"));
        }
    }
}
