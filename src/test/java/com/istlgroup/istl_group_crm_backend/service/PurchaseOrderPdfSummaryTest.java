package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest.PoAdjustmentLine;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest.PoLineItem;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

/**
 * The PO document's summary block: Subtotal, GST per rate, adjustments, Round Off,
 * Total — in that order, with the rows above the total actually adding up to it.
 *
 * Generates a real PDF and reads the text back, because the thing worth pinning is
 * what a vendor sees on the page. Previously the document had no Subtotal row at
 * all, printed the adjustment lines BELOW "Total Amount" even though the total
 * already included them, and printed whatever grand total the browser sent.
 */
class PurchaseOrderPdfSummaryTest {

    private final PurchaseOrderPdfService service = new PurchaseOrderPdfService();

    private static PoLineItem line(int sNo, String desc, double qty, double price, double gstPct) {
        PoLineItem it = new PoLineItem();
        it.setSNo(sNo);
        it.setDescription(desc);
        it.setUnit("Nos");
        it.setQty(qty);
        it.setPricePerUnit(price);
        it.setAmount(qty * price);
        it.setGstPercent(gstPct);
        it.setGstAmount(qty * price * gstPct / 100);
        return it;
    }

    private static PoDocumentRequest request(List<PoLineItem> items, List<PoAdjustmentLine> adjustments) {
        PoDocumentRequest r = new PoDocumentRequest();
        r.setPoNo("PO-TEST-0001");
        r.setVendorName("Test Vendor Pvt Ltd");
        r.setItems(new ArrayList<>(items));
        r.setAdjustments(adjustments);
        // Deliberately absurd, to prove the PDF no longer prints it.
        r.setTotalAmount(999999.0);
        r.setGstAmount(888888.0);
        return r;
    }

    private String textOf(PoDocumentRequest r) throws Exception {
        byte[] pdf = service.generate(r);
        StringBuilder sb = new StringBuilder();
        try (PdfDocument doc = new PdfDocument(new PdfReader(new java.io.ByteArrayInputStream(pdf)))) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(i))).append('\n');
            }
        }
        return sb.toString();
    }

    /** Where a label appears in the extracted text, for asserting row order. */
    private static int at(String text, String label) {
        int i = text.indexOf(label);
        assertTrue(i >= 0, () -> "expected the PDF to contain \"" + label + "\"");
        return i;
    }

    @Test
    @DisplayName("mixed GST rates: all four lines present, in order, and they add up")
    void mixedRatesSummaryBlock() throws Exception {
        // 10,000.00 @ 5% = 500.00 ; 2,497.00 @ 18% = 449.46
        // subtotal 12,497.00 + GST 949.46 = 13,446.46 -> 13,446.00, round off -0.46
        String text = textOf(request(Arrays.asList(
                line(1, "Solar module", 1, 10000.00, 5),
                line(2, "Mounting structure", 1, 2497.00, 18)), null));

        assertTrue(text.contains("Subtotal"), "Subtotal row must exist — it never used to");
        assertTrue(text.contains("12,497.00"), () -> "subtotal figure missing from:\n" + text);

        assertTrue(text.contains("GST @ 5%"), "one GST row per rate");
        assertTrue(text.contains("GST @ 18%"), "one GST row per rate");
        assertTrue(text.contains("500.00"), "5% GST figure");
        assertTrue(text.contains("449.46"), "18% GST figure");

        assertTrue(text.contains("Round Off"), "Round Off row");
        assertTrue(text.contains("-0.46"), () -> "signed round off missing from:\n" + text);

        assertTrue(text.contains("Total Amount"), "Total row");
        assertTrue(text.contains("13,446"), () -> "rounded total missing from:\n" + text);

        // Order: Subtotal -> GST -> Round Off -> Total.
        assertTrue(at(text, "Subtotal") < at(text, "GST @ 5%"), "Subtotal comes first");
        assertTrue(at(text, "GST @ 18%") < at(text, "Round Off"), "GST before Round Off");
        assertTrue(at(text, "Round Off") < at(text, "Total Amount"), "Round Off before the Total");
    }

    @Test
    @DisplayName("the client's own total is never printed")
    void clientSuppliedTotalIsIgnored() throws Exception {
        String text = textOf(request(Arrays.asList(
                line(1, "Solar module", 1, 10000.00, 5),
                line(2, "Mounting structure", 1, 2497.00, 18)), null));

        assertFalse(text.contains("999,999"), "the payload's totalAmount must not reach the page");
        assertFalse(text.contains("888,888"), "the payload's gstAmount must not reach the page");
    }

    @Test
    @DisplayName("adjustments print above the total and are inside it")
    void adjustmentsAreIncludedInTheTotal() throws Exception {
        PoAdjustmentLine freight = new PoAdjustmentLine();
        freight.setLabel("Freight");
        freight.setAmount(1500.00);

        // subtotal 12,497.00 + GST 949.46 + 1,500.00 = 14,946.46 -> 14,946.00
        String text = textOf(request(Arrays.asList(
                line(1, "Solar module", 1, 10000.00, 5),
                line(2, "Mounting structure", 1, 2497.00, 18)),
                Arrays.asList(freight)));

        assertTrue(text.contains("Freight"), "the adjustment label");
        assertTrue(text.contains("14,946"), () -> "total must include the adjustment:\n" + text);
        // This is the ordering bug that used to make the block read wrong.
        assertTrue(at(text, "Freight") < at(text, "Total Amount"),
                "an adjustment is part of the total, so it prints above it");
    }

    @Test
    @DisplayName("no Round Off row when the total already lands on a whole rupee")
    void roundOffRowSuppressedAtZero() throws Exception {
        // 10,000.00 @ 18% = 1,800.00 -> 11,800.00 exactly.
        String text = textOf(request(Arrays.asList(
                line(1, "Solar module", 1, 10000.00, 18)), null));

        assertTrue(text.contains("11,800"), () -> "total missing from:\n" + text);
        assertFalse(text.contains("Round Off"),
                "a meaningless zero round-off must not be printed");
    }

    @Test
    @DisplayName("the printed total is the same figure PurchaseOrderService would store")
    void printedTotalMatchesWhatTheServiceWouldPersist() throws Exception {
        // With no adjustments both sides derive from the same line items with the
        // same arithmetic, so the document and the record cannot disagree.
        BigDecimal serviceExact = BigDecimal.ZERO;
        for (Object[] row : new Object[][]{{10000.00, 5.0}, {2497.00, 18.0}}) {
            BigDecimal base = MoneyRounding.money(BigDecimal.valueOf((Double) row[0]));
            serviceExact = serviceExact.add(base)
                    .add(MoneyRounding.percentOf(base, BigDecimal.valueOf((Double) row[1])));
        }
        BigDecimal serviceTotal = MoneyRounding.auto(serviceExact).finalTotal();
        assertEquals(0, new BigDecimal("13446.00").compareTo(serviceTotal),
                () -> "service total was " + serviceTotal);

        String text = textOf(request(Arrays.asList(
                line(1, "Solar module", 1, 10000.00, 5),
                line(2, "Mounting structure", 1, 2497.00, 18)), null));
        assertTrue(text.contains("13,446"), "the PDF prints the same total the service stores");
    }
}
