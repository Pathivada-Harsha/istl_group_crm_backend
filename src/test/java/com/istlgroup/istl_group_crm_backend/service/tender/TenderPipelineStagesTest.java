package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Each pipeline stage on its own, using the exact wording of the reference
 * documents. The corpus test proves the whole thing end to end; these prove
 * <em>why</em> it works, so a regression names the stage that broke.
 */
class TenderPipelineStagesTest {

    // ── Stage 1 ──────────────────────────────────────────────────────────────

    @Nested
    class Cleaning {

        /**
         * TREDA repeats a three-line header and a footer on all 105 pages.
         *
         * <p>The body lines here differ by more than their digits on purpose:
         * signatures are taken with digits blanked, which is what collapses
         * "Page 3 of 105" and "Page 47 of 105" onto one line rather than 105.
         */
        @Test
        void dropsLinesThatRepeatOnMostPages() {
            String[] bodies = {"alpha", "bravo", "charlie", "delta", "echo",
                               "foxtrot", "golf", "hotel", "india", "juliet"};
            List<List<String>> pages = new ArrayList<>();
            for (int p = 1; p <= bodies.length; p++) {
                pages.add(List.of(
                        "e-TENDER FOR SPV POWER PLANTS AT BLOCK OFFICES IN TRIPURA",
                        "body line " + bodies[p - 1],
                        "SIGNATURE OF THE BIDDER WITH SEAL & DATE",
                        "Page " + p + " of 10"));
            }
            TenderText cleaned = TenderTextCleaner.clean(TenderText.ofPages(pages));

            assertEquals(bodies.length, cleaned.lines().size(), "one surviving line per page");
            assertFalse(cleaned.flat().contains("SPV POWER PLANTS"), cleaned.flat());
            assertFalse(cleaned.flat().contains("SIGNATURE OF THE BIDDER"), cleaned.flat());
            assertFalse(cleaned.flat().contains("Page 4 of 10"), cleaned.flat());
        }

        /**
         * A two-page IREPS tender repeats its header on both pages, and that
         * header is the only place the tender number is stated as a label/value
         * pair. Short documents have no budget problem to solve, so nothing is
         * dropped on a frequency rule.
         */
        @Test
        void leavesShortDocumentsAlone() {
            List<String> header = List.of(
                    "SR-CONST-HQ-ELECTRICAL/SOUTHERN RLY",
                    "Tender No: E-HQ-CN-EOT09-2026-27 Closing Date/Time: 17/08/2026 15:00");
            TenderText cleaned = TenderTextCleaner.clean(
                    TenderText.ofPages(List.of(header, header)));

            assertTrue(cleaned.flat().contains("E-HQ-CN-EOT09-2026-27"), cleaned.flat());
            assertTrue(cleaned.flat().contains("SOUTHERN RLY"), cleaned.flat());
        }

        /** PDF extraction pads columns; a label and its value end up one space apart. */
        @Test
        void collapsesTheColumnPadding() {
            assertEquals("Estimated cost Rs.12,66,67,376/-",
                    TenderTextCleaner.squeeze("  Estimated   cost      Rs.12,66,67,376/-  "));
        }
    }

    // ── Stage 2 ──────────────────────────────────────────────────────────────

    @Nested
    class LocatingTheSummary {

        /**
         * A boilerplate mention buried in an annexure must not outrank the real
         * summary. Page 3 holds the bid information sheet; page 40 merely says
         * the words.
         */
        @Test
        void ranksTheSummaryPageAboveABoilerplateMention() {
            List<List<String>> pages = new ArrayList<>();
            pages.add(List.of("DETAILED e-TENDER DOCUMENT"));
            pages.add(List.of("Certified that this DNIe-T contains 105 pages"));
            pages.add(List.of("BID INFORMATION SHEET",
                    "1. Name of work Design, survey, manufacture",
                    "2. Estimated cost Rs.12,66,67,376/-",
                    "4. Bid Security / EMD Rs. 31,66,684/-",
                    "12. Bid Submission End 03/07/2026 at 03:00 PM"));
            for (int p = 4; p <= 40; p++) {
                pages.add(List.of("the decision of Tendering Authority shall be final"));
            }
            TenderSummaryLocator.Block best =
                    TenderSummaryLocator.summaryBlock(TenderText.ofPages(pages));

            assertTrue(best.fromPage() <= 3 && best.toPage() >= 3,
                    "expected the block to cover page 3, was " + best);
        }

        /** Best block, then the opening pages, then the whole document. */
        @Test
        void fallsBackToTheOpeningPagesThenTheDocument() {
            List<List<String>> pages = new ArrayList<>();
            pages.add(List.of("cover"));
            pages.add(List.of("index"));
            pages.add(List.of("NIT HEADER", "Name of Work X", "Estimated Cost Rs. 5,00,000",
                    "Earnest Money Rs. 25,000", "Tender No ABC/1"));
            for (int p = 4; p <= 12; p++) pages.add(List.of("clause " + p));

            List<TenderSummaryLocator.Block> blocks =
                    TenderSummaryLocator.rank(TenderText.ofPages(pages));

            assertEquals(3, blocks.size());
            assertEquals("summary block", blocks.get(0).label());
            assertEquals("opening pages", blocks.get(1).label());
            assertEquals("whole document", blocks.get(2).label());
        }
    }

    // ── capture termination ──────────────────────────────────────────────────

    @Nested
    class CaptureTermination {

        /**
         * The IREPS grid puts two label/value pairs on one line. Without the
         * terminator vocabulary the tender type reads
         * "Open Bidding System Two Packet System".
         */
        @Test
        void stopsAtTheNextRecognisedLabel() {
            Map<String, String> values = valuesOn(
                    "Tender Type Open Bidding System Two Packet System");
            assertEquals("Open", values.get(TenderLabels.TENDER_TYPE));
        }

        @Test
        void stopsAtALabelItDoesNotItselfExtract() {
            assertEquals("68918769.50",
                    valuesOn("Advertised Value 68918769.50 Tendering Section WORKS")
                            .get(TenderLabels.ESTIMATED));
            assertEquals("(Rs.) 534000.00",
                    valuesOn("Earnest Money (Rs.) 534000.00 Validity of Offer ( Days) 180")
                            .get(TenderLabels.EMD));
        }

        /** "Tender Closing Date Time" must not be read as "Closing Date" + "Time". */
        @Test
        void prefersTheLongerLabelAtTheSameStart() {
            Map<String, String> values = valuesOn(
                    "Tender Closing Date Time 17/08/2026 15:00 "
                  + "Date Time Of Uploading Tender 08/07/2026 17:12");
            assertEquals("17/08/2026 15:00", values.get(TenderLabels.SUBMISSION));
            assertEquals("08/07/2026 17:12", values.get(TenderLabels.PUBLISH));
        }

        /**
         * A label centred in a tall cell prints its own first line above itself.
         * Taking only the line the label sits on returns a fragment.
         */
        @Test
        void stitchesACellWhoseValueStartsAboveItsLabel() {
            TenderText doc = TenderText.fromPlainText(String.join("\n",
                    "1. NIT HEADER",
                    "Implementation of Rooftop Solar PV System At Various Locations of Madurai "
                            + "(MDU) station of Madurai division,",
                    "Name of Work Southern Railway, with the Objective of Generating and Supplying "
                            + "Energy to Railway Installations through RESCO",
                    "Mode",
                    "Bidding type Normal Tender"));
            String name = TenderRecords.build(doc, 1, 1).stream()
                    .filter(r -> r.labelKey().equals(TenderLabels.TENDER_NAME))
                    .findFirst().orElseThrow().value();

            assertTrue(name.startsWith("Implementation of Rooftop Solar PV System"), name);
            assertTrue(name.endsWith("through RESCO Mode"), name);
        }

        /** A label mentioned inside a sentence is not a label/value pair. */
        @Test
        void marksALabelInsideASentenceAsProse() {
            TenderText doc = TenderText.fromPlainText(
                    "1. Certified that this DNIe-T contains 105 (one hundred five) pages numbered");
            assertTrue(TenderRecords.build(doc, 1, 1).get(0).inProse());

            TenderText grid = TenderText.fromPlainText("DNIe-T No.F.6 (542)/TREDA/NCES/2025-26/2110,");
            assertFalse(TenderRecords.build(grid, 1, 1).get(0).inProse());
        }

        private Map<String, String> valuesOn(String line) {
            Map<String, String> out = new LinkedHashMap<>();
            for (TenderRecords.Record r : TenderRecords.build(TenderText.fromPlainText(line), 1, 1)) {
                out.putIfAbsent(r.labelKey(), r.value());
            }
            return out;
        }
    }

    // ── value grammars ───────────────────────────────────────────────────────

    @Nested
    class Money {

        @Test
        void readsBothDialects() {
            assertEquals("126667376", TenderValues.money("Rs.12,66,67,376/- (Rupees twelve crore)"));
            assertEquals("112882269", TenderValues.money("₹11,28,82,269"));
            assertEquals("201756000", TenderValues.money("Rs.2017.56 Lakhs"));
            assertEquals("269000000", TenderValues.money("Rs.26.90 Crore"));
            assertEquals("68918769.5", TenderValues.money("68918769.50"));
            assertEquals("534000", TenderValues.money("534000.00"));
        }

        @Test
        void takesTheScaleFromAColumnHeaderWhenTheFigureCarriesNone() {
            assertEquals("201756000", TenderValues.money("(Rs. in lakhs) excluding GST 2017.56"));
        }

        /**
         * A magnitude word that punctuation separated from its own figure. Left
         * unnoticed, "2017.56/- in Lakhs" is stored as two thousand rupees.
         */
        @Test
        void noticesAStrandedScaleWord() {
            assertTrue(TenderValues.hasStrayScaleWord("Estimated Cost 2017.56/- in Lakhs"));
            assertTrue(TenderValues.hasStrayScaleWord("Estimated Cost 45.00 (Crore)"));
            assertFalse(TenderValues.hasStrayScaleWord("Advertised Value 68918769.50"));
            assertFalse(TenderValues.hasStrayScaleWord("Estimated Cost Rs.2017.56 Lakhs"),
                    "adjacent: the amount pattern already converted it");
        }
    }

    @Nested
    class Dates {

        /** Indian documents are day-first. There is no month-first reading. */
        @Test
        void readsDayFirstAlways() {
            assertEquals("2026-07-08", TenderValues.toIso("08/07/2026"));
            assertEquals("2026-08-17", TenderValues.toIso("17/08/2026"));
            assertEquals("2026-01-15", TenderValues.toIso("15.01.2026"));
        }

        @Test
        void rejectsAnImpossibleDateRatherThanSwappingIt() {
            assertNull(TenderValues.toIso("15/25/2026"));
            assertNull(TenderValues.toIso("31/02/2026"));
        }

        @Test
        void dropsATrailingTimeComponent() {
            assertEquals("2026-08-17", TenderValues.date("17/08/2026 15:00"));
            assertEquals("2026-07-03", TenderValues.date("03/07/2026 at 03:00 PM"));
        }

        @Test
        void readsTheWordedSpellings() {
            assertEquals("2026-01-15", TenderValues.toIso("15-Jan-2026"));
            assertEquals("2026-01-15", TenderValues.toIso("15 January 2026"));
            assertEquals("2026-01-15", TenderValues.toIso("January 15, 2026"));
            assertEquals("2026-01-15", TenderValues.toIso("2026-01-15"));
        }
    }

    @Nested
    class FinancialYear {

        /** TREDA's reference says 2025-26; the document was published June 2026. */
        @Test
        void comesFromTheReferenceNotThePublicationDate() {
            assertEquals("2025-26",
                    TenderValues.fyFromReference("F.6 (542)/TREDA/NCES/2025-26/2110"));
            assertEquals("2026-27", TenderValues.fyFromReference("E-HQ-CN-EOT09-2026-27"));
            assertNull(TenderValues.fyFromReference("DPA/ELEC/2025/17"));
        }

        @Test
        void ignoresAYearPairThatIsNotAFinancialYear() {
            assertNull(TenderValues.fyFromReference("PKG/2025-31/AA"));
        }
    }

    // ── Stage 4 ──────────────────────────────────────────────────────────────

    @Nested
    class Validation {

        @Test
        void discardsATenderNumberThatIsOnlyADate() {
            assertFalse(TenderFieldValidator.checkTenderNumber("12/06/2026").ok());
            assertTrue(TenderFieldValidator.checkTenderNumber("F.6 (542)/TREDA/NCES/2025-26/2110").ok());
        }

        /** Both defects from the reference documents, in one place. */
        @Test
        void discardsAnAuthorityThatIsASentenceFragment() {
            assertFalse(TenderFieldValidator.checkAuthority(
                    "in a Sealed Envelope superscripting name of bidder,").ok());
            assertFalse(TenderFieldValidator.checkAuthority(
                    "the undersigned has ensured that the issue of this tender does not violate "
                  + "provisions of GFR").ok());
            assertTrue(TenderFieldValidator.checkAuthority(
                    "TRIPURA RENEWABLE ENERGY DEVELOPMENT AGENCY").ok());
            assertTrue(TenderFieldValidator.checkAuthority("SR-CONST-HQ-ELECTRICAL/SOUTHERN RLY").ok());
        }

        @Test
        void stripsTheEmailPrefixTheSourceGluesOn() {
            assertEquals("tredatenders25@gmail.com",
                    TenderFieldValidator.stripEmailPrefix("email-tredatenders25@gmail.com"));
            assertEquals("tredatenders25@gmail.com",
                    TenderFieldValidator.stripEmailPrefix("e-mail-tredatenders25@gmail.com"));
            assertTrue(TenderFieldValidator.checkEmail("email-tredatenders25@gmail.com").ok());
            assertFalse(TenderFieldValidator.checkEmail("email-not an address").ok());
        }

        @Test
        void discardsAFormFieldEnumerationDressedUpAsAnAddress() {
            assertFalse(TenderFieldValidator.checkAddress("c) Mobile No. d) Email e) Fax").ok());
            assertTrue(TenderFieldValidator.checkAddress(
                    "Vigyan Bhawan, Pandit Nehru Complex, Gorkhabasti, Agartala, Tripura").ok());
        }

        @Test
        void discardsAFinancialYearThatContradictsTheReference() {
            TenderFieldValidator.Context ctx =
                    new TenderFieldValidator.Context("F.6 (542)/TREDA/NCES/2025-26/2110", 2026, 2026);
            assertFalse(TenderFieldValidator.checkFinancialYear("2026-27", ctx).ok());
            assertTrue(TenderFieldValidator.checkFinancialYear("2025-26", ctx).ok());
        }

        @Test
        void discardsADateOutsideTheDocumentsOwnWindow() {
            TenderFieldValidator.Context ctx = new TenderFieldValidator.Context(null, 2026, 2026);
            assertTrue(TenderFieldValidator.checkDate("2026-08-17", ctx).ok());
            assertFalse(TenderFieldValidator.checkDate("2019-08-17", ctx).ok());
        }

        /**
         * An EMD outside the band means one of the pair is wrong with no way to
         * tell which, so the EMD goes — it is the cheaper of the two to re-enter.
         */
        @Test
        void discardsAnEmdThatCannotBeAShareOfTheEstimate() {
            Map<String, ExtractedField> fields = new LinkedHashMap<>();
            fields.put("estimatedValue", field("estimatedValue", "137837539"));
            fields.put("emdAmount", field("emdAmount", "1068000"));            // 0.77%
            TenderFieldValidator.crossCheck(fields, (f, why) -> { });
            assertTrue(fields.containsKey("emdAmount"), "0.77% is inside the band");

            fields.put("emdAmount", field("emdAmount", "34810"));              // 0.03%
            TenderFieldValidator.crossCheck(fields, (f, why) -> { });
            assertFalse(fields.containsKey("emdAmount"), "0.03% is the document fee, not the EMD");
        }

        @Test
        void discardsADateThatFallsOutOfOrder() {
            Map<String, ExtractedField> fields = new LinkedHashMap<>();
            fields.put("submissionDeadline", field("submissionDeadline", "2026-07-03"));
            fields.put("technicalOpeningDate", field("technicalOpeningDate", "2026-06-19"));
            TenderFieldValidator.crossCheck(fields, (f, why) -> { });

            assertFalse(fields.containsKey("technicalOpeningDate"),
                    "a technical opening cannot precede the submission deadline");
            assertTrue(fields.containsKey("submissionDeadline"));
        }

        private ExtractedField field(String name, String value) {
            return new ExtractedField(name, "test", value, 1, value, ExtractedField.REGEX, true);
        }
    }

    // ── Stage 5 ──────────────────────────────────────────────────────────────

    @Nested
    class Gate {

        /**
         * The whole point of the change: the gate used to count fields the regex
         * produced, so a parse full of garbage passed and the escalation path
         * behind it was unreachable.
         */
        @Test
        void needsTheIdentityAndOneDetailToSurvive() {
            assertTrue(TenderParseGate.isComplete(
                    List.of("tenderNumber", "tenderName", "issuingAuthority", "estimatedValue")));
            assertFalse(TenderParseGate.isComplete(
                    List.of("tenderNumber", "tenderName", "issuingAuthority")),
                    "identity alone is not a complete parse");
            assertFalse(TenderParseGate.isComplete(
                    List.of("tenderNumber", "tenderName", "estimatedValue", "state", "sector")),
                    "no authority means the identity is not established");
            assertFalse(TenderParseGate.isComplete(List.of()));
        }

        @Test
        void namesTheIdentityFieldsThatAreMissing() {
            assertEquals(List.of("issuingAuthority"),
                    TenderParseGate.missingCore(List.of("tenderNumber", "tenderName")));
        }
    }
}
