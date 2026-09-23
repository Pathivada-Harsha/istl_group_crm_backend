package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillItemEntity;
import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;

/**
 * A vendor bill is an INCOMING document: the round-off is pre-filled with the
 * automatic value but the user may nudge it within a rupee to land on the figure
 * the vendor printed.
 *
 * BillService.recalculateBillTotal is private, so what is exercised here is the
 * arithmetic it delegates to — the bill item's own subtotal/tax helpers plus
 * MoneyRounding.withOverride — and the status logic that keys off the result.
 * That is the part a regression would actually break.
 */
class BillRoundOffTest {

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static BillItemEntity item(String qty, String unitPrice, String taxPercent) {
        BillItemEntity i = new BillItemEntity();
        i.setQuantity(new BigDecimal(qty));
        i.setUnitPrice(new BigDecimal(unitPrice));
        i.setTaxPercent(new BigDecimal(taxPercent));
        return i;
    }

    /** What recalculateBillTotal sums: per-item subtotal + per-item tax. */
    private static BigDecimal exactTotalOf(List<BillItemEntity> items) {
        BigDecimal subtotal = items.stream()
                .map(BillItemEntity::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = items.stream()
                .map(BillItemEntity::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return subtotal.add(tax);
    }

    /** Applies the trio the way BillService does, so status can be asserted. */
    private static BillEntity billWith(List<BillItemEntity> items, String requestedRoundOff) {
        MoneyRounding.RoundedTotal t = MoneyRounding.withOverride(
                exactTotalOf(items),
                requestedRoundOff == null ? null : new BigDecimal(requestedRoundOff));
        BillEntity bill = new BillEntity();
        bill.setItems(new ArrayList<>(items));
        bill.setExactTotal(t.exactTotal());
        bill.setRoundOff(t.roundOff());
        bill.setTotalAmount(t.finalTotal());
        return bill;
    }

    @Nested
    @DisplayName("the override band")
    class Override {

        /** A bill whose lines come to 10,499.49 before rounding. */
        private final List<BillItemEntity> items = Arrays.asList(
                item("1", "8898.72", "18"),
                item("1", "600.00", "0"));

        @Test
        void exactTotalIsTheSumOfTheLines() {
            // 8898.72 + 1601.77 tax + 600.00
            assertMoney("11100.49", exactTotalOf(items));
        }

        @Test
        void ninetyNinePaiseIsAccepted() {
            BillEntity bill = billWith(items, "0.99");
            assertMoney("11100.49", bill.getExactTotal());
            assertMoney("0.99", bill.getRoundOff());
            assertMoney("11101.48", bill.getTotalAmount());
        }

        @Test
        void oneFiftyIsRejected() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> billWith(items, "1.50"));
            assertSame(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void noOverrideRoundsToTheWholeRupee() {
            BillEntity bill = billWith(items, null);
            assertMoney("-0.49", bill.getRoundOff());
            assertMoney("11100.00", bill.getTotalAmount());
        }

        /** The warehouse auto-bill path passes null, i.e. rounds automatically. */
        @Test
        void billWithNoItemsIsZeroNotAnError() {
            BillEntity bill = billWith(new ArrayList<>(), null);
            assertMoney("0.00", bill.getTotalAmount());
            assertMoney("0.00", bill.getRoundOff());
        }
    }

    @Nested
    @DisplayName("status against the final total")
    class Status {

        /**
         * The bug the feature exists to kill: a bill settled in round rupees used
         * to keep a sub-rupee residual and stay "Partially Paid" for ever.
         */
        @Test
        void payingTheRoundedTotalInFullMarksItPaid() {
            BillEntity bill = billWith(Arrays.asList(item("1", "8898.72", "18")), null);
            assertMoney("10500.00", bill.getTotalAmount());

            bill.setPaidAmount(new BigDecimal("10500.00"));
            bill.recalculateStatus();
            assertEquals("Paid", bill.getStatus());
        }

        @Test
        void partialPaymentIsPartiallyPaid() {
            BillEntity bill = billWith(Arrays.asList(item("1", "8898.72", "18")), null);
            bill.setPaidAmount(new BigDecimal("5000.00"));
            bill.recalculateStatus();
            assertEquals("Partially Paid", bill.getStatus());
        }

        @Test
        void nothingPaidIsPending() {
            BillEntity bill = billWith(Arrays.asList(item("1", "8898.72", "18")), null);
            bill.recalculateStatus();
            assertEquals("Pending", bill.getStatus());
        }

        /**
         * Editing a paid bill upward must take it off "Paid" — the invariant the
         * comment at BillService's update call site is protecting.
         */
        @Test
        void editingItemsUpwardFlipsPaidBackToPartiallyPaid() {
            BillEntity bill = billWith(Arrays.asList(item("1", "8898.72", "18")), null);
            bill.setPaidAmount(bill.getTotalAmount());
            bill.recalculateStatus();
            assertEquals("Paid", bill.getStatus());

            // Another line is added and the totals recomputed.
            BillEntity edited = billWith(Arrays.asList(
                    item("1", "8898.72", "18"), item("1", "1000.00", "18")), null);
            edited.setPaidAmount(bill.getTotalAmount());
            edited.recalculateStatus();
            assertEquals("Partially Paid", edited.getStatus());
        }

        /**
         * The balance a partially paid bill reports. bills.balance_amount is a
         * generated column over (total_amount - paid_amount), so with the FINAL
         * total in that column the balance follows for free — this pins the
         * arithmetic that column performs.
         */
        @Test
        void balanceIsFinalTotalMinusPaid() {
            BillEntity bill = billWith(Arrays.asList(item("1", "8898.72", "18")), null);
            BigDecimal paid = new BigDecimal("5000.00");
            assertMoney("5500.00", bill.getTotalAmount().subtract(paid));
        }
    }

    @Nested
    @DisplayName("bill item helpers")
    class ItemHelpers {

        @Test
        void taxIsScaleTwo() {
            assertEquals(2, item("3", "333.33", "18").getTaxAmount().scale());
        }

        /** These used to NPE: the fields are defaulted but remain settable to null. */
        @Test
        void nullQuantityOrPriceIsZeroNotAnNpe() {
            BillItemEntity i = new BillItemEntity();
            i.setQuantity(null);
            i.setUnitPrice(null);
            i.setTaxPercent(new BigDecimal("18"));
            assertMoney("0.00", i.getSubtotal());
            assertMoney("0.00", i.getTaxAmount());
            assertMoney("0.00", i.getTotalWithTax());
        }

        @Test
        void totalWithTaxIsSubtotalPlusTax() {
            BillItemEntity i = item("2", "100.00", "18");
            assertMoney("200.00", i.getSubtotal());
            assertMoney("36.00", i.getTaxAmount());
            assertMoney("236.00", i.getTotalWithTax());
        }
    }
}
