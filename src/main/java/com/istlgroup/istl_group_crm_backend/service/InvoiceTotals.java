package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one invoice total: taxable subtotal, GST broken down per rate, and the
 * round-off trio.
 *
 * It exists because there used to be three answers to "what does this invoice come
 * to". InvoiceService summed the items one way; InvoicePdfService re-derived the
 * total twice more (once for the items table, once for the tax summary), each time
 * taking the tax rate from items.get(0).getTaxPercent() and halving it into
 * CGST/SGST — so an invoice with a 5% line and an 18% line printed a figure that
 * existed nowhere in the database, and an invoice with no items threw
 * IndexOutOfBoundsException.
 *
 * Now there is one formula, and the two paths through it are deliberately
 * different:
 *
 *   of(items)             the PERSIST path. Derives everything from the items and
 *                         rounds. What the service stores.
 *   ofStored(items, inv)  the RENDER path. Takes the per-rate breakdown from the
 *                         items, but the three totals from the stored columns, and
 *                         never rounds anything itself — so a PDF is arithmetically
 *                         incapable of printing a total the database disagrees with.
 *
 * Pure and stateless — no Spring, directly unit-testable.
 */
public final class InvoiceTotals {

    private InvoiceTotals() {}

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** Default when an item carries no unit. */
    private static final String DEFAULT_UNIT_TYPE = "Nos";

    /**
     * One GST rate's worth of an invoice.
     *
     * cgst is half the tax rounded to the paisa and sgst is the REMAINDER, not a
     * second independent halving. Rounding both halves separately lets them sum to
     * a paisa more or less than the tax they came from, which is how a tax summary
     * ends up failing to tie to its own total. As a residual, cgst + sgst == tax
     * always — a 0.01 tax splits 0.01 / 0.00.
     */
    public record RateLine(BigDecimal ratePercent,
                           BigDecimal taxableValue,
                           BigDecimal cgst,
                           BigDecimal sgst,
                           BigDecimal tax) {

        /** The half-rate to print against CGST and against SGST. */
        public BigDecimal halfRatePercent() {
            return ratePercent.divide(TWO, 2, RoundingMode.HALF_UP);
        }
    }

    /** Everything a screen, an export or a PDF needs to render an invoice's money. */
    public record Totals(BigDecimal taxableSubtotal,
                         List<RateLine> byRate,
                         BigDecimal taxTotal,
                         BigDecimal exactTotal,
                         BigDecimal roundOff,
                         BigDecimal finalTotal,
                         BigDecimal totalQuantity,
                         String unitType) {}

    /**
     * PERSIST path: what this invoice's items come to, rounded to the whole rupee.
     */
    public static Totals of(List<InvoiceItemEntity> items) {
        Breakdown b = breakdown(items);
        MoneyRounding.RoundedTotal t = MoneyRounding.auto(b.exactTotal);
        return assemble(b, t);
    }

    /**
     * RENDER path: the per-rate breakdown from the items, the three totals from the
     * stored columns. Rounds nothing.
     *
     * When an invoice predates the feature its stored exact_total is null (or equal
     * to its total) and roundOff comes back as zero, so a legacy invoice renders
     * with its original paise-bearing total and no round-off line — see
     * MoneyRounding.ofStored.
     */
    public static Totals ofStored(List<InvoiceItemEntity> items, InvoiceEntity invoice) {
        Breakdown b = breakdown(items);
        MoneyRounding.RoundedTotal t = MoneyRounding.ofStored(
                invoice == null ? null : invoice.getExactTotal(),
                invoice == null ? null : invoice.getRoundOff(),
                invoice == null ? null : invoice.getTotalAmount());
        return assemble(b, t);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    /** The item-derived part, shared by both paths. */
    private record Breakdown(BigDecimal taxableSubtotal,
                             List<RateLine> byRate,
                             BigDecimal taxTotal,
                             BigDecimal exactTotal,
                             BigDecimal totalQuantity,
                             String unitType) {}

    private static Totals assemble(Breakdown b, MoneyRounding.RoundedTotal t) {
        return new Totals(b.taxableSubtotal, b.byRate, b.taxTotal,
                t.exactTotal(), t.roundOff(), t.finalTotal(),
                b.totalQuantity, b.unitType);
    }

    /**
     * Groups the items by tax rate and works out the taxable value and GST for each.
     *
     * Tax is rounded to the paisa PER LINE and then summed, which is what
     * InvoiceService and QuotationService already did, so no existing total shifts.
     * The per-rate figures are sums of those same per-line values, so the breakdown
     * always ties exactly to the total it is printed next to.
     *
     * A null tax percent groups as 0%, NOT as 18%. The old 18% fallback meant a
     * genuinely zero-rated line printed as 9% + 9% GST on the invoice.
     */
    private static Breakdown breakdown(List<InvoiceItemEntity> items) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (items == null || items.isEmpty()) {
            return new Breakdown(zero, Collections.emptyList(), zero, zero,
                    BigDecimal.ZERO, DEFAULT_UNIT_TYPE);
        }

        // Sorted by rate so the printed breakdown is stable across renders.
        Map<BigDecimal, BigDecimal[]> groups = new TreeMap<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        String unitType = null;

        for (InvoiceItemEntity item : items) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal rate = item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO;

            BigDecimal lineTaxable = MoneyRounding.money(quantity.multiply(unitPrice));
            BigDecimal lineTax = MoneyRounding.percentOf(lineTaxable, rate);

            // [taxable, tax] per rate. stripTrailingZeros so 18 and 18.00 are one group.
            BigDecimal key = rate.stripTrailingZeros();
            BigDecimal[] acc = groups.computeIfAbsent(key, k -> new BigDecimal[]{zero, zero});
            acc[0] = acc[0].add(lineTaxable);
            acc[1] = acc[1].add(lineTax);

            totalQuantity = totalQuantity.add(quantity);
            if (unitType == null && item.getUnitType() != null && !item.getUnitType().isBlank()) {
                unitType = item.getUnitType();
            }
        }

        List<RateLine> byRate = new ArrayList<>(groups.size());
        BigDecimal taxableSubtotal = zero;
        BigDecimal taxTotal = zero;

        for (Map.Entry<BigDecimal, BigDecimal[]> e : groups.entrySet()) {
            BigDecimal taxable = e.getValue()[0];
            BigDecimal tax = e.getValue()[1];
            BigDecimal cgst = tax.divide(TWO, 2, RoundingMode.HALF_UP);

            byRate.add(new RateLine(e.getKey().setScale(2), taxable, cgst, tax.subtract(cgst), tax));
            taxableSubtotal = taxableSubtotal.add(taxable);
            taxTotal = taxTotal.add(tax);
        }

        return new Breakdown(taxableSubtotal, List.copyOf(byRate), taxTotal,
                taxableSubtotal.add(taxTotal), totalQuantity,
                unitType != null ? unitType : DEFAULT_UNIT_TYPE);
    }
}
