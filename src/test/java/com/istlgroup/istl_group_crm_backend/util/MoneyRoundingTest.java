package com.istlgroup.istl_group_crm_backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The round-off arithmetic every financial document goes through: nearest whole
 * rupee with .50 away from zero, the +/- 1.00 band a user may nudge an incoming
 * document by, and the read path that must never re-round a stored total.
 */
class MoneyRoundingTest {

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static MoneyRounding.RoundedTotal auto(String exact) {
        return MoneyRounding.auto(new BigDecimal(exact));
    }

    @Nested
    @DisplayName("auto — outgoing documents")
    class Auto {

        @Test
        void halfRoundsUp() {
            MoneyRounding.RoundedTotal t = auto("10499.50");
            assertMoney("10500.00", t.finalTotal());
            assertMoney("0.50", t.roundOff());
            assertMoney("10499.50", t.exactTotal());
        }

        @Test
        void belowHalfRoundsDown() {
            MoneyRounding.RoundedTotal t = auto("10499.49");
            assertMoney("10499.00", t.finalTotal());
            assertMoney("-0.49", t.roundOff());
            assertMoney("10499.49", t.exactTotal());
        }

        @Test
        void justAboveHalfRoundsUp() {
            MoneyRounding.RoundedTotal t = auto("10499.51");
            assertMoney("10500.00", t.finalTotal());
            assertMoney("0.49", t.roundOff());
        }

        /** HALF_UP, not banker's rounding: .50 always goes away from zero. */
        @Test
        void halfAlwaysGoesUpNeverToEven() {
            assertMoney("10500.00", auto("10499.50").finalTotal());
            assertMoney("10501.00", auto("10500.50").finalTotal());
        }

        @Test
        void wholeRupeeHasNoRoundOff() {
            MoneyRounding.RoundedTotal t = auto("10500.00");
            assertMoney("10500.00", t.finalTotal());
            assertMoney("0.00", t.roundOff());
        }

        /**
         * Normalise to the paisa FIRST, then round to the rupee. 10499.495 becomes
         * 10499.50 and then 10500.00; rounding straight to the rupee in one step
         * would give the same answer here, but pinning the order matters because
         * exact_total is stored and must be the 2dp figure the user can see.
         */
        @Test
        void subPaisaInputIsNormalisedBeforeRounding() {
            MoneyRounding.RoundedTotal t = auto("10499.495");
            assertMoney("10499.50", t.exactTotal());
            assertMoney("10500.00", t.finalTotal());
            assertMoney("0.50", t.roundOff());
        }

        /** A credit note rounds away from zero too, and |roundOff| stays <= 1. */
        @Test
        void negativeTotalRoundsAwayFromZero() {
            MoneyRounding.RoundedTotal t = auto("-10499.50");
            assertMoney("-10500.00", t.finalTotal());
            assertMoney("-0.50", t.roundOff());
        }

        @Test
        void nullIsZero() {
            MoneyRounding.RoundedTotal t = MoneyRounding.auto(null);
            assertMoney("0.00", t.exactTotal());
            assertMoney("0.00", t.finalTotal());
            assertMoney("0.00", t.roundOff());
        }

        /** The invariant the whole feature rests on. */
        @Test
        void finalAlwaysEqualsExactPlusRoundOff() {
            for (String v : new String[]{"10499.49", "10499.50", "10500.00", "0.01", "-7.25"}) {
                MoneyRounding.RoundedTotal t = auto(v);
                assertMoney(t.finalTotal().toPlainString(), t.exactTotal().add(t.roundOff()));
            }
        }
    }

    @Nested
    @DisplayName("withOverride — incoming documents")
    class Override {

        @Test
        void acceptsNinetyNinePaise() {
            MoneyRounding.RoundedTotal t = MoneyRounding.withOverride(
                    new BigDecimal("10499.49"), new BigDecimal("0.99"));
            assertMoney("10499.49", t.exactTotal());
            assertMoney("0.99", t.roundOff());
            assertMoney("10500.48", t.finalTotal());
        }

        /**
         * The final total is deliberately NOT forced onto a whole rupee here: an
         * incoming bill is a transcription of what the vendor printed.
         */
        @Test
        void finalMayCarryPaise() {
            MoneyRounding.RoundedTotal t = MoneyRounding.withOverride(
                    new BigDecimal("10499.49"), new BigDecimal("0.30"));
            assertMoney("10499.79", t.finalTotal());
        }

        @Test
        void boundsAreInclusive() {
            assertMoney("10500.49", MoneyRounding.withOverride(
                    new BigDecimal("10499.49"), new BigDecimal("1.00")).finalTotal());
            assertMoney("10498.49", MoneyRounding.withOverride(
                    new BigDecimal("10499.49"), new BigDecimal("-1.00")).finalTotal());
        }

        @Test
        void zeroIsAllowed() {
            MoneyRounding.RoundedTotal t = MoneyRounding.withOverride(
                    new BigDecimal("10499.49"), BigDecimal.ZERO);
            assertMoney("0.00", t.roundOff());
            assertMoney("10499.49", t.finalTotal());
        }

        @Test
        void rejectsAboveOneRupee() {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> MoneyRounding.withOverride(new BigDecimal("10499.49"), new BigDecimal("1.50")));
            assertSame(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertEquals(true, ex.getReason().contains("1.00"),
                    () -> "message should name the limit, was: " + ex.getReason());
        }

        @Test
        void rejectsJustOverTheBound() {
            assertThrows(ResponseStatusException.class,
                    () -> MoneyRounding.withOverride(BigDecimal.TEN, new BigDecimal("1.01")));
            assertThrows(ResponseStatusException.class,
                    () -> MoneyRounding.withOverride(BigDecimal.TEN, new BigDecimal("-1.01")));
        }

        @Test
        void rejectsFinerThanAPaisa() {
            assertThrows(ResponseStatusException.class,
                    () -> MoneyRounding.withOverride(BigDecimal.TEN, new BigDecimal("0.999")));
        }

        /** Trailing zeros are scale, not precision: 0.990 is still 99 paise. */
        @Test
        void trailingZerosAreNotOverPrecision() {
            assertMoney("0.99", MoneyRounding.withOverride(
                    BigDecimal.TEN, new BigDecimal("0.990")).roundOff());
        }

        @Test
        void nullFallsBackToAuto() {
            MoneyRounding.RoundedTotal overridden =
                    MoneyRounding.withOverride(new BigDecimal("10499.49"), null);
            MoneyRounding.RoundedTotal automatic = auto("10499.49");
            assertMoney(automatic.finalTotal().toPlainString(), overridden.finalTotal());
            assertMoney(automatic.roundOff().toPlainString(), overridden.roundOff());
        }
    }

    @Nested
    @DisplayName("ofStored — the read path")
    class OfStored {

        /** A document that predates the feature: no round-off, total untouched. */
        @Test
        void nullExactTotalMeansNeverRounded() {
            MoneyRounding.RoundedTotal t =
                    MoneyRounding.ofStored(null, null, new BigDecimal("10499.49"));
            assertMoney("10499.49", t.exactTotal());
            assertMoney("0.00", t.roundOff());
            assertMoney("10499.49", t.finalTotal());
        }

        /**
         * It must never re-round. A renderer that recomputed could disagree with
         * the database, which is the bug the feature exists to prevent — so a
         * stored total with paise comes back with its paise intact.
         */
        @Test
        void returnsStoredValuesVerbatim() {
            MoneyRounding.RoundedTotal t = MoneyRounding.ofStored(
                    new BigDecimal("10499.49"), new BigDecimal("0.99"), new BigDecimal("10500.48"));
            assertMoney("10499.49", t.exactTotal());
            assertMoney("0.99", t.roundOff());
            assertMoney("10500.48", t.finalTotal());
        }

        @Test
        void allNullsAreZero() {
            MoneyRounding.RoundedTotal t = MoneyRounding.ofStored(null, null, null);
            assertMoney("0.00", t.finalTotal());
            assertMoney("0.00", t.roundOff());
        }
    }

    @Nested
    @DisplayName("percentOf")
    class PercentOf {

        @Test
        void computesTaxToThePaisa() {
            assertMoney("18.00", MoneyRounding.percentOf(new BigDecimal("100"), new BigDecimal("18")));
            assertMoney("5.00", MoneyRounding.percentOf(new BigDecimal("100"), new BigDecimal("5")));
        }

        /**
         * The regression test for the four bare divide(100) calls this replaced.
         * They never threw — 100 is 2^2*5^2, so the quotient always terminates —
         * what they did was return a scale of numerator.scale() + 2, up to 26
         * decimal places on an 18,6 column. The bug is the SCALE, so that is what
         * is asserted.
         */
        @Test
        void resultIsAlwaysScaleTwo() {
            assertEquals(2, MoneyRounding.percentOf(
                    new BigDecimal("1234.567890"), new BigDecimal("18.50")).scale());
            assertEquals(2, MoneyRounding.percentOf(
                    new BigDecimal("0.01"), new BigDecimal("7")).scale());
            assertEquals(2, MoneyRounding.percentOf(BigDecimal.ZERO, BigDecimal.ZERO).scale());
        }

        @Test
        void roundsHalfUp() {
            // 0.07 * 7% = 0.0049 -> 0.00 ; 0.08 * 7% = 0.0056 -> 0.01
            assertMoney("0.00", MoneyRounding.percentOf(new BigDecimal("0.07"), new BigDecimal("7")));
            assertMoney("0.01", MoneyRounding.percentOf(new BigDecimal("0.08"), new BigDecimal("7")));
        }

        @Test
        void nullsAndZeroPercentAreZero() {
            assertMoney("0.00", MoneyRounding.percentOf(null, new BigDecimal("18")));
            assertMoney("0.00", MoneyRounding.percentOf(new BigDecimal("100"), null));
            assertMoney("0.00", MoneyRounding.percentOf(new BigDecimal("100"), BigDecimal.ZERO));
        }
    }

    @Nested
    @DisplayName("money")
    class Money {

        @Test
        void normalisesToThePaisa() {
            assertEquals(2, MoneyRounding.money(new BigDecimal("1.005")).scale());
            assertMoney("1.01", MoneyRounding.money(new BigDecimal("1.005")));
            assertMoney("0.00", MoneyRounding.money(null));
        }
    }
}
