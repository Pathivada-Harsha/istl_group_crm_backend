package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;

/**
 * The single invoice total: the per-rate GST breakdown, and the hard rule that a
 * rendered invoice shows the STORED figures rather than re-deriving its own.
 *
 * The behaviour under test replaced two independent recomputations in
 * InvoicePdfService, both of which took the tax rate from items.get(0) and applied
 * it to the whole subtotal.
 */
class InvoiceTotalsTest {

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static InvoiceItemEntity item(String qty, String unitPrice, String taxPercent) {
        InvoiceItemEntity i = new InvoiceItemEntity();
        i.setQuantity(new BigDecimal(qty));
        i.setUnitPrice(new BigDecimal(unitPrice));
        if (taxPercent != null) i.setTaxPercent(new BigDecimal(taxPercent));
        return i;
    }

    private static InvoiceEntity stored(String exactTotal, String roundOff, String totalAmount) {
        InvoiceEntity inv = new InvoiceEntity();
        if (exactTotal != null) inv.setExactTotal(new BigDecimal(exactTotal));
        if (roundOff != null) inv.setRoundOff(new BigDecimal(roundOff));
        if (totalAmount != null) inv.setTotalAmount(new BigDecimal(totalAmount));
        return inv;
    }

    @Nested
    @DisplayName("mixed GST rates on one invoice")
    class MixedRates {

        /** A 5% line and an 18% line — the acceptance case. */
        private final List<InvoiceItemEntity> items = Arrays.asList(
                item("1", "10000.00", "5"),
                item("1", "2497.00", "18"));

        @Test
        void oneGroupPerRateInRateOrder() {
            InvoiceTotals.Totals t = InvoiceTotals.of(items);
            assertEquals(2, t.byRate().size());
            assertMoney("5", t.byRate().get(0).ratePercent());
            assertMoney("18", t.byRate().get(1).ratePercent());
        }

        @Test
        void eachRateGetsItsOwnTaxableValueAndTax() {
            InvoiceTotals.Totals t = InvoiceTotals.of(items);
            assertMoney("10000.00", t.byRate().get(0).taxableValue());
            assertMoney("500.00", t.byRate().get(0).tax());
            assertMoney("2497.00", t.byRate().get(1).taxableValue());
            assertMoney("449.46", t.byRate().get(1).tax());
        }

        @Test
        void breakdownTiesToTheTotals() {
            InvoiceTotals.Totals t = InvoiceTotals.of(items);
            assertMoney("12497.00", t.taxableSubtotal());
            assertMoney("949.46", t.taxTotal());
            assertMoney("13446.46", t.exactTotal());
            assertMoney("13446.00", t.finalTotal());
            assertMoney("-0.46", t.roundOff());
        }

        @Test
        void finalTotalIsAWholeRupee() {
            InvoiceTotals.Totals t = InvoiceTotals.of(items);
            assertEquals(0, t.finalTotal().remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO),
                    () -> "not a whole rupee: " + t.finalTotal());
        }

        /**
         * Had the old first-item-rate logic still been in force, the whole 12497.00
         * subtotal would have been taxed at 5% (624.85) instead of 949.46 — a
         * printed total of 13121.85 against a saved total of 13446.00.
         */
        @Test
        void doesNotTaxEverythingAtTheFirstItemsRate() {
            InvoiceTotals.Totals t = InvoiceTotals.of(items);
            BigDecimal firstRateOnEverything =
                    t.taxableSubtotal().multiply(new BigDecimal("0.05"));
            assertTrue(t.taxTotal().compareTo(firstRateOnEverything) > 0,
                    "tax must reflect every rate, not just the first item's");
        }
    }

    @Nested
    @DisplayName("CGST/SGST split")
    class Split {

        @Test
        void halvesAlwaysSumBackToTheTax() {
            for (String price : new String[]{"0.05", "0.11", "2497.00", "10000.01"}) {
                InvoiceTotals.Totals t = InvoiceTotals.of(List.of(item("1", price, "18")));
                InvoiceTotals.RateLine line = t.byRate().get(0);
                assertMoney(line.tax().toPlainString(), line.cgst().add(line.sgst()));
            }
        }

        /**
         * SGST is the residual, not a second independent halving. Halving twice and
         * rounding each to the paisa turns a 0.01 tax into 0.01 + 0.01 = 0.02.
         */
        @Test
        void oddPaisaGoesToCgstAndLeavesSgstZero() {
            // 0.06 at 18% = 0.0108 -> 0.01
            InvoiceTotals.Totals t = InvoiceTotals.of(List.of(item("1", "0.06", "18")));
            InvoiceTotals.RateLine line = t.byRate().get(0);
            assertMoney("0.01", line.tax());
            assertMoney("0.01", line.cgst());
            assertMoney("0.00", line.sgst());
        }

        @Test
        void halfRateIsPrintedPerRate() {
            InvoiceTotals.Totals t = InvoiceTotals.of(List.of(item("1", "100", "5")));
            assertMoney("2.50", t.byRate().get(0).halfRatePercent());
        }
    }

    @Nested
    @DisplayName("ofStored — what a PDF prints")
    class OfStored {

        /**
         * The durable form of "printed total == saved total": an invoice whose
         * stored total deliberately disagrees with its items must render from the
         * STORED values. This is what makes it impossible for the PDF to invent a
         * number again.
         */
        @Test
        void totalsComeFromTheColumnsNotTheItems() {
            InvoiceTotals.Totals t = InvoiceTotals.ofStored(
                    List.of(item("1", "10000.00", "18")),
                    stored("99999.49", "0.51", "100000.00"));
            assertMoney("99999.49", t.exactTotal());
            assertMoney("0.51", t.roundOff());
            assertMoney("100000.00", t.finalTotal());
            // ...while the breakdown still describes the items themselves.
            assertMoney("10000.00", t.taxableSubtotal());
            assertMoney("1800.00", t.taxTotal());
        }

        /** A legacy invoice: round-off 0, paise-bearing total left exactly as is. */
        @Test
        void legacyInvoiceRendersUnchangedWithNoRoundOff() {
            InvoiceTotals.Totals t = InvoiceTotals.ofStored(
                    List.of(item("1", "10499.49", "0")),
                    stored(null, null, "10499.49"));
            assertMoney("10499.49", t.finalTotal());
            assertMoney("0.00", t.roundOff());
            assertMoney("10499.49", t.exactTotal());
            assertEquals(0, t.roundOff().signum(),
                    "a zero round-off is what suppresses the PDF's Round Off row");
        }
    }

    @Nested
    @DisplayName("edge cases that used to throw")
    class Edges {

        /** items.get(0) on an invoice with no items was an IndexOutOfBounds. */
        @Test
        void emptyAndNullItemLists() {
            for (List<InvoiceItemEntity> items : Arrays.asList(null, new ArrayList<InvoiceItemEntity>())) {
                InvoiceTotals.Totals t = InvoiceTotals.of(items);
                assertMoney("0.00", t.exactTotal());
                assertMoney("0.00", t.finalTotal());
                assertMoney("0.00", t.taxTotal());
                assertTrue(t.byRate().isEmpty());
                assertEquals("Nos", t.unitType());
            }
        }

        /**
         * A missing tax percent is 0%, not 18%. The old fallback printed a
         * genuinely zero-rated line as 9% + 9% GST.
         */
        @Test
        void nullTaxPercentIsZeroPercentNotEighteen() {
            InvoiceTotals.Totals t = InvoiceTotals.of(List.of(item("1", "1000.00", null)));
            assertMoney("0", t.byRate().get(0).ratePercent());
            assertMoney("0.00", t.taxTotal());
            assertMoney("1000.00", t.finalTotal());
        }

        /** 18 and 18.00 are the same rate and must not split into two rows. */
        @Test
        void sameRateWrittenDifferentlyIsOneGroup() {
            InvoiceTotals.Totals t = InvoiceTotals.of(Arrays.asList(
                    item("1", "100.00", "18"),
                    item("1", "100.00", "18.00")));
            assertEquals(1, t.byRate().size());
            assertMoney("200.00", t.byRate().get(0).taxableValue());
        }

        @Test
        void quantitiesAndUnitTypeAreCarried() {
            InvoiceItemEntity a = item("2", "100.00", "18");
            a.setUnitType("Kgs");
            InvoiceTotals.Totals t = InvoiceTotals.of(List.of(a, item("3", "100.00", "18")));
            assertMoney("5", t.totalQuantity());
            assertEquals("Kgs", t.unitType());
        }

        @Test
        void missingQuantityDefaultsToOne() {
            InvoiceItemEntity i = new InvoiceItemEntity();
            i.setUnitPrice(new BigDecimal("500.00"));
            i.setTaxPercent(BigDecimal.ZERO);
            assertMoney("500.00", InvoiceTotals.of(List.of(i)).finalTotal());
        }
    }

    @Nested
    @DisplayName("equivalence with the formula it replaced")
    class Equivalence {

        /**
         * InvoiceService.calculateTotalAmount summed, per line,
         * qty*price + round2(qty*price*rate/100). Grouping by rate must not change
         * a single-rate invoice's total, or every existing invoice would shift on
         * its next save.
         */
        @Test
        void singleRateInvoiceMatchesTheOldPerLineSum() {
            List<InvoiceItemEntity> items = Arrays.asList(
                    item("3", "333.33", "18"),
                    item("7", "11.11", "18"),
                    item("1", "0.07", "18"));

            BigDecimal oldWay = BigDecimal.ZERO;
            for (InvoiceItemEntity i : items) {
                BigDecimal sub = i.getQuantity().multiply(i.getUnitPrice());
                BigDecimal tax = sub.multiply(i.getTaxPercent())
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                oldWay = oldWay.add(sub).add(tax);
            }

            assertMoney(oldWay.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    InvoiceTotals.of(items).exactTotal());
        }
    }
}
