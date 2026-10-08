package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Kind;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Check;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Evidence;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Expect;

/**
 * Source text is found in the PDF however extraction mangled its layout, and a
 * value is verified only when the passage it cites really states it.
 */
class TenderExcelVerificationTest {

    /** Two pages, written the way PDF text extraction hands them over. */
    private static final TenderText PDF = TenderText.fromPagedText(String.join("\n",
            "NOTICE INVITING EXPRESSION OF INTEREST",
            "This Notice for Inviting EOIs shall be perpetually open up to: 31/03/2026 up to 15:00",
            "Hours. The supply of goods is covered under the proj-",
            "ect scope.",
            "\f",
            "4. For participating in EOI, vendor has to submit non-refundable processing",
            "fees of Rs.59,000/- (Rs.50,000/- plus GST @ 18% = Rs.9,000/-).",
            "Beneficiary Bank IFSC Code: CNRB0006999",
            "A Average Turnover greater or equal More than 10 MWp in SPV Power Plants",
            "to Rs. 100 Cr. or"));

    private static final Evidence EV = Evidence.of(PDF);

    @Nested
    class Locate {
        private final TenderClauseFinder finder = new TenderClauseFinder(PDF);

        @Test
        void toleratesCaseSpacingAndPunctuation() {
            TenderClauseFinder.Clause c = finder.locate("beneficiary bank  IFSC code - cnrb0006999");
            assertNotNull(c);
            assertEquals("Beneficiary Bank IFSC Code: CNRB0006999", c.text());
            assertEquals(2, c.page());
        }

        @Test
        void toleratesALineBreakInsideTheQuote() {
            assertNotNull(finder.locate("submit non-refundable processing fees of Rs.59,000/-"));
        }

        @Test
        void toleratesAWordHyphenatedAcrossLines() {
            assertNotNull(finder.locate("covered under the project scope"));
        }

        @Test
        void toleratesFiguresWrittenWithDifferentSpacing() {
            assertNotNull(finder.locate("Rs. 59,000/-"));
            assertNotNull(finder.locate("Rs 59000"));
        }

        @Test
        void followsWordsAcrossInterleavedTableColumns() {
            TenderClauseFinder.Clause c = finder.locate("Average Turnover greater or equal to Rs. 100 Cr.");
            assertNotNull(c);
            assertTrue(c.text().contains("100 Cr"), c.text());
        }

        @Test
        void refusesWhatIsNotThere() {
            assertNull(finder.locate("processing fees of Rs.69,000/-"));
            assertNull(finder.locate("pply"), "a quote must match whole words, not the inside of \"supply\"");
            assertNull(finder.locate(""));
        }
    }

    @Nested
    class Values {

        @Test
        void moneyMatchesTheWayTheTenderWritesIt() {
            assertEquals(TenderExcelVerifier.VERIFIED,
                    TenderExcelVerifier.field(Kind.MONEY, "59000", "processing fees of Rs.59,000/-", EV).status());
            Check crore = TenderExcelVerifier.field(Kind.MONEY, "1000000000",
                    "Average Turnover greater or equal to Rs. 100 Cr.", EV);
            assertEquals(TenderExcelVerifier.VERIFIED, crore.status(), crore.reason());
        }

        @Test
        void datesMatchDayFirst() {
            Check c = TenderExcelVerifier.field(Kind.DATE, "2026-03-31", "perpetually open up to: 31/03/2026", EV);
            assertEquals(TenderExcelVerifier.VERIFIED, c.status());
            assertEquals(1, c.page());
            assertEquals(TenderExcelVerifier.MISMATCH,
                    TenderExcelVerifier.field(Kind.DATE, "2026-03-30", "perpetually open up to: 31/03/2026", EV).status());
        }

        @Test
        void aWrongFigureWithARealSourceIsAMismatch() {
            Check c = TenderExcelVerifier.field(Kind.MONEY, "69000", "processing fees of Rs.59,000/-", EV);
            assertEquals(TenderExcelVerifier.MISMATCH, c.status());
            assertTrue(c.reason().contains("69000"));
        }

        @Test
        void aValueWithNoMatchingTextIsNeverVerified() {
            assertEquals(TenderExcelVerifier.UNVERIFIED,
                    TenderExcelVerifier.field(Kind.MONEY, "59000", null, EV).status(), "no source");
            assertEquals(TenderExcelVerifier.UNVERIFIED,
                    TenderExcelVerifier.field(Kind.MONEY, "59000", "a fee of fifty nine thousand", EV).status(),
                    "source not in the PDF");
            Check none = TenderExcelVerifier.field(Kind.MONEY, "59000", "Rs.59,000/-",
                    Evidence.unavailable("The PDF is a scan."));
            assertEquals(TenderExcelVerifier.UNVERIFIED, none.status());
            assertEquals("The PDF is a scan.", none.reason());
            assertNull(TenderExcelVerifier.field(Kind.MONEY, "", "Rs.59,000/-", EV), "a blank value has nothing to verify");
        }

        @Test
        void textMustBeWhatThePassageSays() {
            assertEquals(TenderExcelVerifier.VERIFIED, TenderExcelVerifier.field(Kind.TEXT, "CNRB0006999",
                    "Beneficiary Bank IFSC Code: CNRB0006999", EV).status());
            assertEquals(TenderExcelVerifier.MISMATCH, TenderExcelVerifier.field(Kind.TEXT, "SBIN0001234",
                    "Beneficiary Bank IFSC Code: CNRB0006999", EV).status());
        }

        @Test
        void aChoiceNeedsOnlyItsSourceFound() {
            // "No" is the reader's classification of "non-refundable", not a quote.
            assertEquals(TenderExcelVerifier.VERIFIED, TenderExcelVerifier.field(Kind.CHOICE, "No",
                    "non-refundable processing", EV).status());
        }

        @Test
        void aRowIsCheckedValueByValue() {
            Check ok = TenderExcelVerifier.row("More than 10 MWp in SPV Power Plants",
                    List.of(new Expect("Required Value", Kind.NUMBER, "10")), EV);
            assertEquals(TenderExcelVerifier.VERIFIED, ok.status());
            Check bad = TenderExcelVerifier.row("More than 10 MWp in SPV Power Plants",
                    List.of(new Expect("Required Value", Kind.NUMBER, "15")), EV);
            assertEquals(TenderExcelVerifier.MISMATCH, bad.status());
            assertTrue(bad.reason().startsWith("Required Value:"));
        }
    }
}
