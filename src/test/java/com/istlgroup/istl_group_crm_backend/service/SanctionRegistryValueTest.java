package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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

        /**
         * The failure mode that motivated this fix: a rupee figure followed
         * by its own spelled-out check-amount in parentheses — a standard
         * Indian banking convention — must not have "Crore"/"Lakh" inside
         * that restatement mistaken for the figure's own unit. Before the
         * fix, this scaled 5,22,24,00,000 by another 10,000,000×.
         */
        @Test
        void aSpelledOutRestatementInParenthesesDoesNotRescaleTheFigure() {
            assertEquals(new BigDecimal("5222400000.00"), SanctionValueParser.parseMoneyCrore(
                    "5,22,24,00,000/- (Rupees Five Hundred Twenty Two Crore Twenty Four Lakh only)"));
            assertEquals("₹522.24 Cr", SanctionValueParser.formatCrore(
                    SanctionValueParser.parseMoneyCrore(
                            "5,22,24,00,000/- (Rupees Five Hundred Twenty Two Crore Twenty Four Lakh only)")));
        }

        /** A unit word right next to the number still scales, restatement or not. */
        @Test
        void anExplicitUnitStillWinsEvenNextToAParentheticalNote() {
            assertEquals(new BigDecimal("7680000000.00"), SanctionValueParser.parseMoneyCrore(
                    "Rs. 768.00 Crore (approx. Rs. 5.12 Crore/MW)"));
        }

        @Test
        void moreUnitVariantsAllParseTheSameWay() {
            assertEquals(new BigDecimal("2050000000.00"), SanctionValueParser.parseMoneyCrore("Rs. 205.00 Crore"));
            assertEquals(new BigDecimal("2050000000.00"), SanctionValueParser.parseMoneyCrore("₹205 Cr"));
            assertEquals(new BigDecimal("201756000.00"), SanctionValueParser.parseMoneyCrore("Rs. 2017.56 Lakhs"));
            assertEquals(new BigDecimal("2050000000.00"), SanctionValueParser.parseMoneyCrore("2050000000"));
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
            assertEquals("Term Loan", m.get("instrument"));
            assertEquals("75", m.get("pledgeOfSharesPct"));
            assertEquals("1.12", m.get("minDscr"));
            assertEquals("Nil", m.get("isra"));
            assertEquals("30 April 2025", m.get("disbursementDate"));
            assertEquals("30 September 2026", m.get("repaymentStartDate"));
            assertEquals("30 September 2041", m.get("repaymentEndDate"));
            assertEquals("24.5", m.get("plfPct"));
        }

        /**
         * Not every letter lays the covenant out as a clean "Min. DSCR: 1.12x"
         * row — plenty state it as a sentence, sometimes without the "Min" prefix
         * or the abbreviation at all. The extractor must read those the same way.
         */
        @Test
        void readsDscrStatedAsProse() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                The borrower shall maintain a Minimum Debt Service Coverage Ratio
                (DSCR) of 1.20x throughout the tenor of the loan.
                """);

            assertEquals("1.20", m.get("minDscr"));
        }

        /**
         * Some letters state both covenants in one sentence — the average over
         * the tenor and the floor for any individual year are different numbers
         * and must land in different fields, not overwrite each other.
         */
        @Test
        void distinguishesAverageDscrFromMinimumDscr() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                The Borrower shall maintain a minimum average DSCR of 1.15x over
                the loan tenor, and a minimum DSCR of 1.10x in any individual
                year, computed on the basis of audited financial statements.
                """);

            assertEquals("1.15", m.get("avgDscr"));
            assertEquals("1.10", m.get("minDscr"));
        }

        /**
         * A registry-style letter prints "DSRA: ... ISRA: ... Cash Sweep: ..."
         * back to back, so the original patterns could stop a value at whichever
         * fixed label came next. A letter written as narrative bullets — each
         * covenant its own heading-then-sentence, in an order the sheet doesn't
         * assume, with no known label following the last one — must still be
         * read, closing the value at its own sentence's full stop instead.
         */
        @Test
        void readsCovenantsWrittenAsNarrativeBullets() {
            // The "•" here is deliberate, not decorative: a letter generated by
            // script often types a literal bullet character in front of each
            // clause rather than using Word's list-formatting feature — which
            // would keep the glyph out of the extracted text entirely. Left in,
            // it used to sit exactly where the end-of-sentence terminator looks
            // for the next clause's capital letter, and silently broke the match.
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                •	Cash Sweep Clause: In the event the trailing 12-month DSCR falls
                below 1.33x, 100% of surplus cash accruals after meeting Scheduled
                Debt Service and O&M reserve requirements shall be swept towards
                mandatory prepayment of the Term Loan, in order of maturity.
                •	Debt-Equity and Leverage: The Borrower shall not permit its
                Debt:Equity ratio to exceed 72:28 without prior written consent
                of the Lender.
                •	DSCR Reserve Account: The Borrower shall maintain a Debt Service
                Reserve Account (DSRA) equivalent to the next two quarters'
                Scheduled Debt Service at all times from the date of first
                disbursement.
                """);

            assertTrue(m.get("cashSweep") != null
                    && m.get("cashSweep").toString().contains("trailing 12-month DSCR falls"),
                    "cashSweep was: " + m.get("cashSweep"));
            assertTrue(m.get("dsra") != null
                    && m.get("dsra").toString().contains("next two quarters"),
                    "dsra was: " + m.get("dsra"));
        }

        @Test
        void readsBareDscrWithoutAMinPrefix() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                DSCR of not less than 1.15 shall be maintained at all times.
                """);

            assertEquals("1.15", m.get("minDscr"));
        }

        /**
         * None of these specimen letters print a labelled "Instrument" row —
         * the facility type only ever appears in the subject line. The covering
         * paragraph repeats a similarly-shaped phrase ("sanction of a Rupee Term
         * Loan in favour of <borrower> ... for part-financing") with a much
         * longer borrower name between the instrument and its "for" — that must
         * not be what gets captured instead of the subject line's clean phrasing.
         */
        @Test
        void readsInstrumentFromTheSubjectLineWhenNoLabelExists() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Subject: Sanction of Rupee Term Loan for 75 MWac Solar Power Project
                Dear Sir/Madam,
                We are pleased to convey sanction of a Rupee Term Loan in favour of
                Aruna Kirana Solar Power Private Limited (the "Borrower") for
                part-financing the above-captioned project (the "Project").
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Aruna Kirana Solar Power Private Limited Project: A project
                """);

            assertEquals("Term Loan", m.get("instrument"));
        }

        /** An explicit "Instrument:" label still wins over the subject line. */
        @Test
        void explicitInstrumentLabelWinsOverTheSubjectLineFallback() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Subject: Sanction of Rupee Term Loan for a project
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Non-Convertible Debentures Security
                """);

            // "Non-Convertible Debentures" is the label's raw wording; the
            // canonical vocabulary calls the same instrument "NCD".
            assertEquals("NCD", m.get("instrument"));
        }

        /**
         * Auto-fill only ever writes one of the registry's fixed instrument
         * names — never the letter's raw wording — so the same facility isn't
         * recorded three different ways across letters. The more specific
         * instruments (FCTL, ECB) must win over the generic "term loan" match
         * that "Rupee Term Loan" would otherwise catch everything with.
         */
        @Test
        void normalisesInstrumentWordingToTheFixedVocabulary() {
            assertEquals("FCTL", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Foreign Currency Term Loan Security
                """).get("instrument"));

            assertEquals("ECB", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: External Commercial Borrowing Security
                """).get("instrument"));

            assertEquals("LC", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Letter of Credit Security
                """).get("instrument"));

            assertEquals("Bank Guarantee", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Bank Guarantee Security
                """).get("instrument"));

            assertEquals("Cash Credit", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Cash Credit Security
                """).get("instrument"));

            assertEquals("Overdraft (OD)", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Overdraft Security
                """).get("instrument"));

            assertEquals("Bridge Loan", extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Bridge Loan Security
                """).get("instrument"));
        }

        /**
         * A letter whose wording matches none of the fixed vocabulary is left
         * blank rather than auto-filled with an unrecognised raw phrase — the
         * reviewer picks the right one by hand.
         */
        @Test
        void leavesInstrumentBlankWhenNothingMatchesTheFixedVocabulary() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Instrument: Structured Mezzanine Facility Security
                """);

            assertTrue(m.get("instrument") == null && !m.containsKey("instrument"));
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

        @Test
        void readsInterestServicedDuringMoratorium() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Interest accruing during the moratorium shall be serviced monthly
                by the borrower.
                """);

            assertEquals("SERVICED", m.get("interestDuringMoratorium"));
        }

        @Test
        void readsInterestCapitalizedDuringMoratorium() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Interest accrued during the moratorium period shall be capitalized
                and added to the principal outstanding.
                """);

            assertEquals("CAPITALIZED", m.get("interestDuringMoratorium"));
        }

        /** No mention at all — never a guessed default; that only happens downstream in BorrowerService.withMeta. */
        @Test
        void interestDuringMoratoriumOmittedWhenNotStated() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                """);

            assertTrue(m.get("interestDuringMoratorium") == null
                    && !m.containsKey("interestDuringMoratorium"));
        }
    }

    @Nested
    @DisplayName("DSRA/ISRA bundled into a differently-labelled DOCX table cell")
    class DocxBundledCovenant {

        private final SanctionDocExtractor extractor = new SanctionDocExtractor();

        private byte[] docxWithOneRow(String label, String value) throws Exception {
            try (XWPFDocument doc = new XWPFDocument()) {
                doc.createParagraph().createRun().setText("Some Lender Ltd.");
                XWPFTable table = doc.createTable(1, 2);
                XWPFTableRow row = table.getRow(0);
                row.getCell(0).setText(label);
                row.getCell(1).setText(value);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.write(out);
                return out.toByteArray();
            }
        }

        /**
         * The Meghdoot letter's shape: a "Debt Service coverage ratio" cell
         * packs a minimum/average DSCR bullet and the DSRA covenant together,
         * with no row anywhere labelled just "DSRA". Before this fix, the
         * whole-document flattened-text scan {@code extractDsra} ran against
         * had to reach a recognised stop-word (ISRA / Cash Sweep / a sentence
         * boundary) within 200 characters — but the next table row here
         * ("Plant Load Factor") is itself a long paragraph, so that boundary
         * was never found within range and the match failed outright.
         */
        @Test
        void dsraBundledUnderADifferentlyLabelledCellIsStillFound() throws Exception {
            byte[] bytes = docxWithOneRow("Debt Service coverage ratio",
                    "Minimum Debt Service Coverage Ratio (DSCR): The Borrower shall maintain a minimum "
                            + "average DSCR of 1.20x over the loan tenor, and a minimum DSCR of 1.15x in any "
                            + "individual year, computed on the basis of audited financial statements "
                            + "DSCR Reserve Account: The Borrower shall maintain a Debt Service Reserve "
                            + "Account (DSRA) equivalent to the next two quarters' Scheduled Debt Service "
                            + "at all times from the date of first disbursement");

            var m = extractor.extractDocx(bytes);

            assertTrue(m.get("dsra") != null && m.get("dsra").toString().contains("next two quarters"),
                    "dsra was: " + m.get("dsra"));
            // The bundled DSCR figures still come from the existing flattened-text fallback.
            assertEquals("1.15", m.get("minDscr"));
            assertEquals("1.20", m.get("avgDscr"));
        }

        @Test
        void aCellWithNoDsraMentionAtAllStaysUnset() throws Exception {
            byte[] bytes = docxWithOneRow("Plant Load Factor",
                    "The Project shall be designed and operated to achieve a base-case PLF of not "
                            + "less than 23.8%.");

            var m = extractor.extractDocx(bytes);

            assertTrue(m.get("dsra") == null && !m.containsKey("dsra"));
        }
    }

    @Nested
    @DisplayName("a newer template's combined 'Clause / Clause Details' table")
    class NewTemplateTable {

        private final SanctionDocExtractor extractor = new SanctionDocExtractor();

        private byte[] docxWithRows(String[][] rows) throws Exception {
            try (XWPFDocument doc = new XWPFDocument()) {
                doc.createParagraph().createRun().setText("Rashtra Vikas Bank Limited");
                XWPFTable table = doc.createTable(rows.length, 2);
                for (int i = 0; i < rows.length; i++) {
                    table.getRow(i).getCell(0).setText(rows[i][0]);
                    table.getRow(i).getCell(1).setText(rows[i][1]);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.write(out);
                return out.toByteArray();
            }
        }

        /**
         * Debt and equity, stated only inside one combined "Means of Finance"
         * cell rather than their own labelled rows — the shape the newer
         * template's letters actually use. Each side must be read off its own
         * keyword, not a first/second positional guess: the amounts here are
         * printed in Debt-then-Equity order, but the extractor must not be
         * relying on that order to tell them apart.
         */
        @Test
        void meansOfFinanceSplitsIntoDebtAndEquity() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Total Project Cost", "Rs. 768.00 Crore (approx. Rs. 5.12 Crore/MW)"},
                    {"Means of Finance", "Rupee Term Loan (Bank): Rs. 522.24 Crore (68%) "
                            + "Promoter's Equity / Unsecured Loan: Rs. 245.76 Crore (32%) "
                            + "Total: Rs. 768.00 Crore"},
            });

            var m = extractor.extractDocx(bytes);

            assertEquals("522.24 Crore", m.get("debtAmount"));
            assertEquals("68", m.get("debtPct"));
            assertEquals("245.76 Crore", m.get("equityAmount"));
            assertEquals("32", m.get("equityPct"));
            // And it must actually reconcile once parsed and scaled.
            assertEquals(new BigDecimal("5222400000.00"),
                    SanctionValueParser.parseMoneyCrore(m.get("debtAmount").toString()));
            assertEquals(new BigDecimal("2457600000.00"),
                    SanctionValueParser.parseMoneyCrore(m.get("equityAmount").toString()));
        }

        /**
         * A properly labelled "Debt (Rs. Cr's)" row elsewhere still wins over
         * whatever a Means of Finance cell would otherwise have supplied —
         * the same printed-wins precedence every other field in this class
         * follows.
         */
        @Test
        void anExplicitDebtRowIsNotOverriddenByMeansOfFinance() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Debt (Rs. Cr's)", "500.00"},
                    {"Means of Finance", "Rupee Term Loan (Bank): Rs. 522.24 Crore (68%) "
                            + "Promoter's Equity: Rs. 245.76 Crore (32%) Total: Rs. 768.00 Crore"},
            });

            var m = extractor.extractDocx(bytes);

            assertEquals("500.00 Cr", m.get("debtAmount"));
        }

        /**
         * The row-processing-order bug this fixes: a Security clause that
         * merely names the DSRA account in passing must not be allowed to
         * plant a wrong "dsra" value before the table walk ever reaches the
         * real, exactly-labelled "Debt Service Reserve Account" row further
         * down. Before the two-pass fix, whichever row came first won,
         * regardless of which one was actually correct.
         */
        @Test
        void anIncidentalDsraMentionInSecurityDoesNotPreemptTheRealDsraRow() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Security", "First pari passu charge over all revenues, receivables and cash flows "
                            + "of the Project, and over the Escrow/Trust & Retention Account, the DSRA and "
                            + "all other Project accounts, operated per the waterfall mechanism in the "
                            + "Escrow Agreement."},
                    {"Debt Service Reserve Account", "DSRA equivalent to the next two quarters' "
                            + "principal and interest obligations at all times, to be funded prior to or "
                            + "within 6 months of COD."},
            });

            var m = extractor.extractDocx(bytes);

            assertTrue(m.get("dsra") != null && m.get("dsra").toString().contains("next two quarters"),
                    "dsra was: " + m.get("dsra"));
        }

        /**
         * A letter that splits Tenor and Moratorium into two rows instead of
         * one combined sentence — the Tenor cell alone states no moratorium
         * count at all.
         */
        @Test
        void moratoriumReadFromItsOwnRowWhenSeparateFromTenor() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Tenor", "204 months (17.0 years) door-to-door, inclusive of moratorium"},
                    {"Moratorium", "6 months from the date of first disbursement or COD, whichever is earlier"},
            });

            var m = extractor.extractDocx(bytes);

            assertEquals("204 months (17.0 years) door-to-door, inclusive of moratorium", m.get("tenorText"));
            assertEquals("6", m.get("moratoriumMonths"));
        }

        /** A combined-sentence letter (the older template) is untouched — no separate row, no key. */
        @Test
        void moratoriumMonthsIsAbsentWhenTenorAlreadyStatesItInline() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Tenor", "18 years including moratorium of 9 months"},
            });

            var m = extractor.extractDocx(bytes);

            assertTrue(m.get("moratoriumMonths") == null && !m.containsKey("moratoriumMonths"));
        }

        /** "Module / Technology" and "Promoter / Sponsor" are read the same as their older, single-word labels. */
        @Test
        void differentlyWordedLabelsStillMapToTheSameFields() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Module / Technology", "Mono PERC bifacial modules of 545 Wp rating; "
                            + "33 x 2.5 MW WTGs (wind component)"},
                    {"Promoter / Sponsor", "Kaveri Clean Power Holdings Private Limited "
                            + "(holding 100% of paid-up equity of the Borrower as on date)"},
            });

            var m = extractor.extractDocx(bytes);

            assertTrue(m.get("technology") != null && m.get("technology").toString().contains("Mono PERC"));
            assertTrue(m.get("promoterName") != null
                    && m.get("promoterName").toString().startsWith("Kaveri Clean Power Holdings"));
        }

        /** A pledge row stated as a full covenant sentence still yields a clean percentage. */
        @Test
        void pledgePercentageIsExtractedFromACovenantSentence() throws Exception {
            byte[] bytes = docxWithRows(new String[][] {
                    {"Pledge of Shares", "Pledge of 51% of the paid-up equity share capital of the "
                            + "Borrower held by the Promoter, in favour of the Bank."},
            });

            var m = extractor.extractDocx(bytes);

            assertEquals("51%", m.get("pledgeOfSharesPct"));
        }
    }

    @Nested
    @DisplayName("ROI: preferring the all-in rate over an earlier spread percentage")
    class RoiAnchoring {

        private final SanctionDocExtractor extractor = new SanctionDocExtractor();

        /**
         * The newer template states the spread before the effective rate in
         * the same sentence — "first % in the sentence" must not win here.
         */
        @Test
        void presentlyWorkingOutToWinsOverAnEarlierSpread() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Rate of Interest: RLLR plus a spread of 2.32% p.a., reset quarterly,
                presently working out to 9.88% p.a. floating. Tenor is 204 months.
                """);

            assertEquals("9.88%", m.get("roiPct"));
        }

        /** A letter with only one percentage in the sentence is unaffected — same result either way. */
        @Test
        void aSingleStatedPercentageStillWinsWithNoAnchorPhrase() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Rate of Interest: 10.05% p.a. (floating, linked to T-bill benchmark + spread)
                Tenor is 18 years.
                """);

            assertEquals("10.05%", m.get("roiPct"));
        }

        @Test
        void effectiveRateAnchorAlsoWins() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Rate of Interest: Base rate 7.25% plus spread 2.50%, effective rate 9.75%
                p.a. Tenor is 16 years.
                """);

            assertEquals("9.75%", m.get("roiPct"));
        }
    }

    @Nested
    @DisplayName("DSCR: a combined label must not let the average figure satisfy the minimum")
    class DscrCombinedLabel {

        private final SanctionDocExtractor extractor = new SanctionDocExtractor();

        /**
         * The newer template's shape: a table row literally labelled "Debt
         * Service Coverage Ratio (DSCR)" whose value states the average
         * figure first and the minimum figure second, in one string. Before
         * this fix, the label text itself (not preceded by "average") was an
         * unqualified match, and its lazy gap swallowed the average figure
         * before ever reaching the correctly-qualified minimum.
         */
        @Test
        void theMinimumFigureWinsEvenWhenTheAverageIsCapturedByTheRowLabel() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                Debt Service Coverage Ratio (DSCR) : Average DSCR of 1.18x over the loan
                tenor; minimum DSCR in any year not to fall below 1.14x
                """);

            assertEquals("1.14", m.get("minDscr"));
            assertEquals("1.18", m.get("avgDscr"));
        }

        /** Existing V1-style single-sentence phrasing is unaffected. */
        @Test
        void separateMinimumAndAverageClausesStillWork() {
            var m = extractor.extractFromPdfText("""
                Some Lender Ltd.
                Ref. No.: X/1 Date: 14 March 2025
                Borrower: Quiet Co Project: A project
                The Borrower shall maintain a minimum average DSCR of 1.18x over the loan
                tenor, and a minimum DSCR of 1.13x in any individual year.
                """);

            assertEquals("1.13", m.get("minDscr"));
            assertEquals("1.18", m.get("avgDscr"));
        }
    }
}
