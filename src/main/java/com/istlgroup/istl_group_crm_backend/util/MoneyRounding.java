package com.istlgroup.istl_group_crm_backend.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Round-off on financial documents: the one place that decides what a document's
 * grand total is.
 *
 * Every financial document stores three values:
 *
 *   exactTotal   the sum of the line items, before rounding
 *   roundOff     finalTotal - exactTotal, always within [-1.00, +1.00]
 *   finalTotal   the grand total, rounded to the nearest whole rupee (.50 up)
 *
 * The finalTotal goes into the total column each table already had
 * (invoices.total_amount, purchase_orders.total_value, ...), so "total" everywhere
 * downstream — the generated balance_amount columns, the SUM() aggregates, the
 * dashboards, the reports, the exports — means the final rounded figure with no
 * further change. exactTotal and roundOff live in two new columns beside it.
 * See round_off_migration.sql for why it is arranged that way.
 *
 * Two tiers of document:
 *   • OUTGOING (invoices, POs, order book, proposals) round automatically —
 *     {@link #auto}. Whatever total or round-off the client sent is ignored.
 *   • INCOMING (vendor bills, inventory bills, vendor quotations, project
 *     expenses) pre-fill the automatic value but let the user nudge it to match
 *     what the vendor actually printed — {@link #withOverride}, bounded to
 *     +/- {@link #MAX_ROUND_OFF}.
 *
 * Round-off is never part of taxable value or GST. Callers must apply it to the
 * grand total only, and leave their subtotal and tax figures exact.
 *
 * Pure and stateless — no Spring, directly unit-testable.
 */
public final class MoneyRounding {

    private MoneyRounding() {}

    /** How far a user may nudge an incoming document's round-off, inclusive. */
    public static final BigDecimal MAX_ROUND_OFF = new BigDecimal("1.00");

    /** Money is held to the paisa everywhere in this system. */
    private static final int MONEY_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The three values every financial document stores. */
    public record RoundedTotal(BigDecimal exactTotal, BigDecimal roundOff, BigDecimal finalTotal) {}

    /** Null-safe normalisation to the paisa. The entry point for any raw amount. */
    public static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * base * pct / 100, to the paisa.
     *
     * Use this for every tax and discount line rather than a bare
     * divide(BigDecimal.valueOf(100)). The bare form does not throw — 100 is
     * 2^2 * 5^2, so the quotient always terminates — but it returns a scale of
     * numerator.scale() + 2, which on an 18,6 column is a number carried to 26
     * decimal places. exactTotal would then be an over-precise figure that
     * (finalTotal - exactTotal) is measured against, and the round-off would
     * carry digits nobody intended.
     */
    public static BigDecimal percentOf(BigDecimal base, BigDecimal pct) {
        if (base == null || pct == null || pct.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        return base.multiply(pct).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * OUTGOING documents: round to the nearest whole rupee, .50 away from zero.
     *
     * setScale(0, HALF_UP) is exactly the stated rule. Note it rounds away from
     * zero on both sides, so a credit of -10499.50 becomes -10500.00 and the
     * magnitude of the round-off still never exceeds 1.
     */
    public static RoundedTotal auto(BigDecimal exactTotal) {
        BigDecimal exact = money(exactTotal);
        BigDecimal rounded = exact.setScale(0, RoundingMode.HALF_UP).setScale(MONEY_SCALE);
        return new RoundedTotal(exact, rounded.subtract(exact), rounded);
    }

    /**
     * INCOMING documents: honour the user's round-off, or fall back to {@link #auto}
     * when they did not supply one.
     *
     * The final total is deliberately NOT forced back onto a whole rupee here. An
     * incoming document is a transcription of what a vendor printed, and that may
     * land on any paisa value within the permitted band — a bill whose exact total
     * is 10,499.49 may legitimately be booked at 10,500.48.
     *
     * @throws ResponseStatusException 400 when the requested round-off is outside
     *         +/- {@link #MAX_ROUND_OFF} or carries more than paisa precision.
     */
    public static RoundedTotal withOverride(BigDecimal exactTotal, BigDecimal requestedRoundOff) {
        if (requestedRoundOff == null) {
            return auto(exactTotal);
        }
        validateRoundOff(requestedRoundOff);

        BigDecimal exact = money(exactTotal);
        BigDecimal roundOff = requestedRoundOff.setScale(MONEY_SCALE);
        return new RoundedTotal(exact, roundOff, exact.add(roundOff));
    }

    /**
     * READ path: rebuild the trio from what is stored on the document, for a detail
     * view, a PDF or an export.
     *
     * This deliberately never rounds anything. A renderer that recomputed the total
     * could disagree with the database, which is the bug the feature exists to kill
     * — so the only number a renderer is allowed to print is the stored one.
     *
     * A null exactTotal means the document predates the feature (or PART 3 of the
     * migration has not run yet), and the honest reading is "this total was never
     * rounded": exactTotal = the stored total, roundOff = 0. Handling it here is
     * what makes a partially applied migration safe, and what stops an old document
     * rendering a round-off of minus its entire total.
     */
    public static RoundedTotal ofStored(BigDecimal storedExact,
                                        BigDecimal storedRoundOff,
                                        BigDecimal storedFinal) {
        BigDecimal finalTotal = money(storedFinal);
        if (storedExact == null) {
            return new RoundedTotal(finalTotal, BigDecimal.ZERO.setScale(MONEY_SCALE), finalTotal);
        }
        return new RoundedTotal(money(storedExact), money(storedRoundOff), finalTotal);
    }

    /**
     * Rejects a client-supplied round-off that is out of band.
     *
     * Thrown as an unchecked ResponseStatusException, which GlobalExceptionHandler
     * already maps to the same {error, message} body as the rest of the API. The
     * alternative, CustomException, extends Exception — it is checked, so throwing
     * it from a leaf utility would push `throws CustomException` onto every bill,
     * quotation, inventory-bill and expense service method and then onto every
     * controller that calls them. Please don't "fix" this back.
     */
    private static void validateRoundOff(BigDecimal roundOff) {
        if (roundOff.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Round off cannot be finer than a paisa (got " + roundOff.toPlainString() + ")");
        }
        if (roundOff.abs().compareTo(MAX_ROUND_OFF) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Round off must be between -" + MAX_ROUND_OFF.toPlainString()
                            + " and " + MAX_ROUND_OFF.toPlainString()
                            + " (got " + roundOff.toPlainString() + ")");
        }
    }
}
