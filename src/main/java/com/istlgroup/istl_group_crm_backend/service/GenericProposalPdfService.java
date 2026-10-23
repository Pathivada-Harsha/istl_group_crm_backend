package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.docx.AmountInWords;
import com.istlgroup.istl_group_crm_backend.service.pdf.GenericProposalCopy;
import com.istlgroup.istl_group_crm_backend.service.pdf.GenericProposalTheme;
import com.istlgroup.istl_group_crm_backend.service.pdf.ProposalPdfTheme;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GenericProposalDocRequest;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.VerticalAlignment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the standard business proposal - the document every organization that is
 * not selling solar gets - straight to PDF with iText.
 *
 * <p>Unlike the solar document, this one has no Word skeleton behind it and no fixed
 * artwork: the PDF <b>is</b> the deliverable, and its only image is the logo the
 * rendering organization uploaded in its own settings. Nothing in it names an
 * industry - the wording is "proposed solution" and "bill of materials", never
 * "solar system" - so the same renderer serves an EPC contractor, an IoT integrator
 * or anyone else.
 *
 * <p>Structure, in order: cover, introduction, about us, proposed solution, scope of
 * work, scope of supply, commercial summary, payment terms, warranty and defect
 * liability, validity and acceptance. Every section is skipped whole when its source
 * field is blank - an empty heading in a client-facing quote reads as an oversight.
 *
 * <p>Typography and geometry live in {@link GenericProposalTheme}, headings and the
 * two boilerplate sentences in {@link GenericProposalCopy}.
 */
@Service
@Slf4j
public class GenericProposalPdfService {

    private final GenericProposalCopy copy;

    public GenericProposalPdfService(GenericProposalCopy copy) {
        this.copy = copy;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int DEFAULT_VALIDITY_DAYS = 15;

    /**
     * The rendering organization's identity, resolved from the session by
     * {@code GenericProposalDocService} - never from the request.
     *
     * @param orgName the organization's display name
     * @param logo    its uploaded logo bytes, or null when it has not uploaded one
     */
    public record Branding(String orgName, byte[] logo) { }

    /** What the cover states about the tracked proposal record. */
    public record DocumentRef(String proposalNo, int version, LocalDate issuedOn) { }

    public byte[] render(GenericProposalDocRequest req,
                         SolarProposalDocService.Money money,
                         Branding brand,
                         DocumentRef ref) throws CustomException {
        return render(req, money, brand, ref, null);
    }

    /**
     * {@link #render(GenericProposalDocRequest, SolarProposalDocService.Money, Branding, DocumentRef)}
     * with a signature image above the organization's signing line — the chosen authorized
     * signatory's, already checked by the caller to belong to this proposal's company.
     * Null leaves the line blank for a hand signature, as it always was.
     */
    public byte[] render(GenericProposalDocRequest req,
                         SolarProposalDocService.Money money,
                         Branding brand,
                         DocumentRef ref,
                         byte[] signatureImage) throws CustomException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
            Document doc = new Document(pdf, GenericProposalTheme.PAGE);
            doc.setMargins(GenericProposalTheme.MARGIN_TOP, GenericProposalTheme.MARGIN_RIGHT,
                           GenericProposalTheme.MARGIN_BOTTOM, GenericProposalTheme.MARGIN_LEFT);

            // One theme (and therefore one pair of PdfFonts) per document - see the
            // class doc on ProposalPdfTheme for why this must not be shared.
            GenericProposalTheme th = new GenericProposalTheme();
            boolean rupee = th.hasRupeeGlyph();
            if (!rupee) {
                log.warn("[PROPOSAL-PDF] embedded font has no U+20B9; amounts fall back to 'Rs.'");
            }

            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new FooterHandler(th, brand.orgName()));

            String title = notBlank(req.getTitle()) ? req.getTitle().trim() : "Business Proposal";
            pdf.getDocumentInfo().setTitle(title);
            pdf.getDocumentInfo().setAuthor(nvl(brand.orgName()));

            LocalDate validUntil = validUntil(req, ref);

            cover(doc, th, req, brand, ref, title, validUntil);
            narrative(doc, th, req);
            scopeOfWork(doc, th, req);
            billOfMaterials(doc, th, req);
            commercial(doc, th, req, money, rupee);
            paymentTerms(doc, th, req);
            warranty(doc, th, req);
            acceptance(doc, th, req, brand, validUntil, signatureImage);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Generic proposal PDF render failed for lead {}", req.getLeadId(), e);
            throw new CustomException("Failed to build the proposal document: " + e.getMessage());
        }
    }

    // =========================================================================
    // 1. Cover
    // =========================================================================

    private void cover(Document doc, GenericProposalTheme th, GenericProposalDocRequest req,
                       Branding brand, DocumentRef ref, String title, LocalDate validUntil) {
        // The organization's own logo, and nothing else. An organization that has not
        // uploaded one simply leads with its name - never with another org's artwork.
        boolean drew = false;
        if (brand.logo() != null && brand.logo().length > 0) {
            try {
                Image img = new Image(ImageDataFactory.create(brand.logo()));
                img.scaleToFit(170f, 62f);
                img.setHorizontalAlignment(HorizontalAlignment.CENTER);
                doc.add(new Div().add(img)
                        .setMarginTop(30).setMarginBottom(10)
                        .setHorizontalAlignment(HorizontalAlignment.CENTER));
                drew = true;
            } catch (Exception e) {
                log.warn("[PROPOSAL-PDF] could not draw the organization logo: {}", e.getMessage());
            }
        }
        if (!drew) doc.add(new Paragraph(" ").setMarginTop(40).setMarginBottom(0));

        doc.add(th.strong(nvl(brand.orgName()), GenericProposalTheme.ORG_NAME)
                .setFontColor(GenericProposalTheme.ACCENT)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(4).setMarginBottom(0));

        doc.add(new Div().setHeight(2f).setWidth(120f)
                .setBackgroundColor(GenericProposalTheme.ACCENT)
                .setHorizontalAlignment(HorizontalAlignment.CENTER)
                .setMarginTop(14).setMarginBottom(60));

        doc.add(th.text("BUSINESS PROPOSAL", GenericProposalTheme.BODY_TEXT)
                .setFontColor(GenericProposalTheme.MUTED)
                .setCharacterSpacing(2.5f)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(10));

        doc.add(th.strong(title, GenericProposalTheme.TITLE)
                .setFontColor(GenericProposalTheme.INK)
                .setTextAlignment(TextAlignment.CENTER)
                .setMultipliedLeading(1.25f)
                .setMarginBottom(50));

        // Reference block: what this document is, in the four facts a buyer checks.
        Table meta = new Table(GenericProposalTheme.widths(1f, 2f)).useAllAvailableWidth();
        metaRow(meta, th, copy.get("cover.proposalNo"), nvl(ref.proposalNo()));
        metaRow(meta, th, copy.get("cover.date"), ref.issuedOn().format(DATE));
        metaRow(meta, th, copy.get("cover.validUntil"), validUntil.format(DATE));
        metaRow(meta, th, copy.get("cover.version"), "v" + ref.version());
        doc.add(meta);

        doc.add(th.label(copy.get("cover.preparedFor")).setMarginTop(28).setMarginBottom(4));
        doc.add(th.strong(nvl(req.getClientName()), GenericProposalTheme.SECTION)
                .setFontColor(GenericProposalTheme.INK).setMarginBottom(2));
        if (notBlank(req.getClientAddress())) {
            doc.add(th.body(req.getClientAddress().trim())
                    .setTextAlignment(TextAlignment.LEFT).setMarginBottom(2));
        }
        if (notBlank(req.getSiteLocation())) {
            doc.add(th.body(copy.get("cover.site") + ": " + req.getSiteLocation().trim())
                    .setTextAlignment(TextAlignment.LEFT));
        }

        doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
    }

    private void metaRow(Table t, GenericProposalTheme th, String label, String value) {
        t.addCell(metaCell(th, th.label(label)));
        t.addCell(metaCell(th, th.text(value, GenericProposalTheme.BODY_TEXT)
                .setFontColor(GenericProposalTheme.INK)
                .setTextAlignment(TextAlignment.RIGHT)));
    }

    private Cell metaCell(GenericProposalTheme th, Paragraph p) {
        return th.cell(p)
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(GenericProposalTheme.RULE, GenericProposalTheme.BORDER_W))
                .setPaddingTop(6f).setPaddingBottom(6f);
    }

    // =========================================================================
    // 2-3. Introduction, About Us, Proposed Solution
    // =========================================================================

    private void narrative(Document doc, GenericProposalTheme th, GenericProposalDocRequest req) {
        section(doc, th, copy.get("intro.heading"), req.getDescription());
        section(doc, th, copy.get("about.heading"), req.getAboutUs());
        section(doc, th, copy.get("solution.heading"), req.getAboutSystem());
    }

    /** Heading plus prose, or nothing at all when the source field is blank. */
    private void section(Document doc, GenericProposalTheme th, String heading, String text) {
        if (!notBlank(text)) return;
        doc.add(th.sectionHeading(heading));
        for (String para : paragraphs(text)) doc.add(th.body(para));
    }

    // =========================================================================
    // 4. Scope of work - the lead's Technical Scope lines
    // =========================================================================

    private void scopeOfWork(Document doc, GenericProposalTheme th, GenericProposalDocRequest req) {
        List<GenericProposalDocRequest.ScopeRow> rows = req.getScopeRows();
        if (rows == null || rows.isEmpty()) return;

        Table t = new Table(GenericProposalTheme.widths(1.1f, 3.3f, 4.2f, 1f, 1f))
                .useAllAvailableWidth();
        headerCells(t, th, copy.get("table.scope.sl"), copy.get("table.scope.activity"),
                copy.get("table.scope.spec"), copy.get("table.scope.qty"), copy.get("table.scope.unit"));

        int i = 1;
        for (GenericProposalDocRequest.ScopeRow r : rows) {
            if (r == null || (!notBlank(r.getActivity()) && !notBlank(r.getSpecification()))) continue;
            String spec = nvl(r.getSpecification()).trim();
            if (notBlank(r.getNotes())) {
                spec = spec.isEmpty() ? r.getNotes().trim() : spec + " - " + r.getNotes().trim();
            }
            bodyCells(t, th, new String[] { String.valueOf(i++), nvl(r.getActivity()), spec,
                    nvl(r.getQuantity()), nvl(r.getUnit()) }, 2);
        }
        // Every row could have been blank, in which case the heading would head nothing.
        if (i == 1) return;
        doc.add(th.sectionHeading(copy.get("scope.heading")));
        doc.add(t);
    }

    // =========================================================================
    // 5. Scope of supply - the lead's BOM, without rates
    // =========================================================================

    private void billOfMaterials(Document doc, GenericProposalTheme th, GenericProposalDocRequest req) {
        List<GenericProposalDocRequest.BomRow> rows = req.getBomRows();
        if (rows == null || rows.isEmpty()) return;

        Table t = new Table(GenericProposalTheme.widths(1.1f, 2.9f, 3.6f, 1.8f, 1f, 1f))
                .useAllAvailableWidth();
        headerCells(t, th, copy.get("table.bom.sl"), copy.get("table.bom.component"),
                copy.get("table.bom.spec"), copy.get("table.bom.make"),
                copy.get("table.bom.qty"), copy.get("table.bom.unit"));

        int i = 1;
        for (GenericProposalDocRequest.BomRow r : rows) {
            if (r == null || (!notBlank(r.getComponent()) && !notBlank(r.getSpecification()))) continue;
            // No rate or amount columns: the offer is quoted as one lump sum below, and
            // per-line rates are internal costing, not a client-facing figure.
            bodyCells(t, th, new String[] { String.valueOf(i++), nvl(r.getComponent()),
                    nvl(r.getSpecification()), nvl(r.getMake()),
                    nvl(r.getQuantity()), nvl(r.getUnit()) }, 2);
        }
        if (i == 1) return;
        doc.add(th.sectionHeading(copy.get("supply.heading")));
        doc.add(t);
    }

    // =========================================================================
    // 6. Commercial summary - the one figure the buyer is looking for
    // =========================================================================

    private void commercial(Document doc, GenericProposalTheme th, GenericProposalDocRequest req,
                            SolarProposalDocService.Money money, boolean rupee) {
        // The whole block is kept together: a heading on one page and the total on the
        // next is the one break that would actually mislead a reader. It is a heading,
        // a line of prose and three rows, so it always fits.
        Div block = new Div().setKeepTogether(true);
        block.add(th.sectionHeading(copy.get("commercial.heading")));

        if (notBlank(req.getWorkDescription())) {
            // keepWithNext chains heading -> description -> table, which is what actually
            // holds the block together; keepTogether alone gives way when the block does
            // not fit the space left on the current page.
            block.add(th.body(req.getWorkDescription().trim())
                    .setMarginBottom(10).setKeepWithNext(true));
        }

        Table t = new Table(GenericProposalTheme.widths(2.6f, 1f)).useAllAvailableWidth();
        priceRow(t, th, copy.get("price.base"), amount(money.base, rupee), false);
        priceRow(t, th, copy.get("price.gst", SolarProposalDocService.percent(money.gstPercent)),
                amount(money.gst, rupee), false);
        priceRow(t, th, copy.get("price.total"), amount(money.total, rupee), true);
        block.add(t);

        block.add(th.runs(GenericProposalTheme.BODY_TEXT,
                        ProposalPdfTheme.Run.b(copy.get("price.words") + ": "),
                        ProposalPdfTheme.Run.i(AmountInWords.rupees(money.total)))
                .setFontColor(GenericProposalTheme.INK)
                .setMarginTop(8));
        doc.add(block);
    }

    private void priceRow(Table t, GenericProposalTheme th, String label, String value, boolean total) {
        float size = total ? GenericProposalTheme.TOTAL : GenericProposalTheme.BODY_TEXT;
        Paragraph l = total ? th.strong(label, size) : th.text(label, size);
        Paragraph v = th.strong(value, size).setTextAlignment(TextAlignment.RIGHT);
        var colour = total ? GenericProposalTheme.ACCENT : GenericProposalTheme.INK;
        t.addCell(priceCell(th, l.setFontColor(colour), total));
        t.addCell(priceCell(th, v.setFontColor(colour), total));
    }

    private Cell priceCell(GenericProposalTheme th, Paragraph p, boolean total) {
        Cell c = th.cell(p)
                .setBorder(new SolidBorder(GenericProposalTheme.RULE, GenericProposalTheme.BORDER_W))
                .setPadding(8f);
        return total ? c.setBackgroundColor(GenericProposalTheme.BAND) : c;
    }

    /** The rupee sign when the embedded face can draw it, "Rs. " when it cannot. */
    private static String amount(BigDecimal v, boolean rupee) {
        return rupee ? SolarProposalDocService.inr(v) : "Rs. " + SolarProposalDocService.grouped(v);
    }

    // =========================================================================
    // 7-8. Payment terms, warranty and defect liability
    // =========================================================================

    private void paymentTerms(Document doc, GenericProposalTheme th, GenericProposalDocRequest req) {
        if (!notBlank(req.getPaymentTerms())) return;
        doc.add(th.sectionHeading(copy.get("payment.heading")));
        addClauses(doc, th, req.getPaymentTerms());
    }

    private void warranty(Document doc, GenericProposalTheme th, GenericProposalDocRequest req) {
        if (!notBlank(req.getDefectLiabilityPeriod()) && !notBlank(req.getWarrantyNotes())) return;
        doc.add(th.sectionHeading(copy.get("warranty.heading")));
        if (notBlank(req.getDefectLiabilityPeriod())) addClauses(doc, th, req.getDefectLiabilityPeriod());
        if (notBlank(req.getWarrantyNotes())) addClauses(doc, th, req.getWarrantyNotes());
    }

    /**
     * Terms authored one per line become bullets; a single block stays one justified
     * paragraph. Preparers write both ways and neither should read as a mistake.
     */
    private void addClauses(Document doc, GenericProposalTheme th, String text) {
        List<String> lines = paragraphs(text);
        if (lines.size() == 1) {
            doc.add(th.body(lines.get(0)));
            return;
        }
        for (String line : lines) doc.add(th.bullet(stripBullet(line)));
    }

    // =========================================================================
    // 9. Validity and acceptance
    // =========================================================================

    private void acceptance(Document doc, GenericProposalTheme th, GenericProposalDocRequest req,
                            Branding brand, LocalDate validUntil, byte[] signatureImage) {
        doc.add(th.sectionHeading(copy.get("validity.heading")));
        doc.add(th.body(copy.get("validity.text",
                String.valueOf(validityDays(req)), validUntil.format(DATE))));
        doc.add(th.body(copy.get("acceptance.text")).setMarginBottom(30));

        // Kept together so the two blocks can never land on different pages.
        Table sign = new Table(GenericProposalTheme.widths(1f, 1f)).useAllAvailableWidth();
        sign.addCell(signatory(th, copy.get("sign.for") + " " + nvl(brand.orgName()),
                new String[] { nvl(req.getPreparedByName()), nvl(req.getPreparedByDesignation()),
                        contact(req) }, signatureImage));
        sign.addCell(signatory(th, copy.get("sign.acceptedFor") + " " + nvl(req.getClientName()),
                new String[] { copy.get("sign.name"), copy.get("sign.designation"),
                        copy.get("sign.date") }, null));
        doc.add(new Div().setKeepTogether(true).add(sign));
    }

    private Cell signatory(GenericProposalTheme th, String heading, String[] lines, byte[] image) {
        Cell c = new Cell()
                .setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.TOP)
                .setPaddingRight(20f);
        Image signature = null;
        if (image != null && image.length > 0) {
            try {
                signature = new Image(ImageDataFactory.create(image));
                signature.scaleToFit(130f, 48f);
            } catch (Exception e) {
                log.warn("[PROPOSAL-PDF] signatory image could not be drawn: {}", e.getMessage());
            }
        }
        // The same 52pt either way, so a signed and an unsigned block line up.
        c.add(th.strong(heading, GenericProposalTheme.BODY_TEXT)
                .setFontColor(GenericProposalTheme.INK).setMarginBottom(signature == null ? 52 : 4));
        if (signature != null) c.add(new Div().setHeight(48f).add(signature));
        c.add(new Div().setHeight(0.7f).setWidth(170f)
                .setBackgroundColor(GenericProposalTheme.INK).setMarginBottom(6));
        c.add(th.label(copy.get("sign.signature")).setMarginBottom(8));
        for (String line : lines) {
            if (notBlank(line)) {
                c.add(th.text(line, GenericProposalTheme.BODY_TEXT)
                        .setFontColor(GenericProposalTheme.INK)
                        .setMargin(0).setMultipliedLeading(1.3f));
            }
        }
        return c;
    }

    private static String contact(GenericProposalDocRequest req) {
        List<String> parts = new ArrayList<>();
        if (notBlank(req.getPreparedByPhone())) parts.add(req.getPreparedByPhone().trim());
        if (notBlank(req.getPreparedByEmail())) parts.add(req.getPreparedByEmail().trim());
        return String.join(" | ", parts);
    }

    // =========================================================================
    // Footer - the organization's name and a page number. No address block: the
    // organization registry does not hold one, and inventing one is not an option.
    // =========================================================================

    private static final class FooterHandler implements IEventHandler {
        private final GenericProposalTheme th;
        private final String orgName;

        FooterHandler(GenericProposalTheme th, String orgName) {
            this.th = th;
            this.orgName = orgName == null ? "" : orgName;
        }

        @Override
        public void handleEvent(Event event) {
            try {
                PdfDocumentEvent ev = (PdfDocumentEvent) event;
                PdfDocument pdfDoc = ev.getDocument();
                PdfPage page = ev.getPage();
                Rectangle ps = page.getPageSize();
                // newContentStreamBefore: the rule paints UNDER the flowed content.
                PdfCanvas pdfCanvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
                Canvas canvas = new Canvas(pdfCanvas, ps);

                float x = GenericProposalTheme.MARGIN_LEFT;
                float w = ps.getWidth() - GenericProposalTheme.MARGIN_LEFT - GenericProposalTheme.MARGIN_RIGHT;

                pdfCanvas.saveState()
                        .setFillColor(GenericProposalTheme.RULE)
                        .rectangle(x, 42f, w, 0.6f)
                        .fill()
                        .restoreState();

                canvas.add(new Div().setFixedPosition(x, 27f, w)
                        .add(th.label(orgName).setMargin(0)));
                canvas.add(new Div().setFixedPosition(x, 27f, w)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .add(th.label("Page " + pdfDoc.getPageNumber(page)).setMargin(0)));
                canvas.close();
            } catch (Exception e) {
                log.warn("[PROPOSAL-PDF] footer render issue: {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private void headerCells(Table t, GenericProposalTheme th, String... heads) {
        // addHeaderCell repeats the row automatically on every page the table spans.
        for (String h : heads) {
            t.addHeaderCell(th.cell(th.strong(h, GenericProposalTheme.TABLE_CELL)
                            .setFontColor(GenericProposalTheme.INK)
                            .setTextAlignment(TextAlignment.CENTER))
                    .setBorder(new SolidBorder(GenericProposalTheme.RULE, GenericProposalTheme.BORDER_W))
                    .setBackgroundColor(GenericProposalTheme.BAND)
                    .setPadding(6f));
        }
    }

    /** One table row. {@code proseColumn} is the column that carries free text. */
    private void bodyCells(Table t, GenericProposalTheme th, String[] values, int proseColumn) {
        for (int i = 0; i < values.length; i++) {
            TextAlignment al = (i == proseColumn || i == 1) ? TextAlignment.LEFT : TextAlignment.CENTER;
            t.addCell(th.cell(th.text(values[i], GenericProposalTheme.TABLE_CELL)
                            .setFontColor(GenericProposalTheme.INK)
                            .setMultipliedLeading(1.25f)
                            .setTextAlignment(al))
                    .setBorder(new SolidBorder(GenericProposalTheme.RULE, GenericProposalTheme.BORDER_W))
                    .setPadding(6f)
                    .setKeepTogether(true));   // a row must not split across pages
        }
    }

    private int validityDays(GenericProposalDocRequest req) {
        return req.getValidityDays() != null && req.getValidityDays() > 0
                ? req.getValidityDays() : DEFAULT_VALIDITY_DAYS;
    }

    private LocalDate validUntil(GenericProposalDocRequest req, DocumentRef ref) {
        LocalDate from = ref.issuedOn();
        if (notBlank(req.getQuoteValidFrom())) {
            try {
                from = LocalDate.parse(req.getQuoteValidFrom().trim().substring(0, 10));
            } catch (Exception ignore) {
                // An unparseable date falls back to the issue date rather than failing
                // the render - the document must still say something true.
            }
        }
        return from.plusDays(validityDays(req));
    }

    /** Splits authored text into lines, dropping blanks. */
    private static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!line.isBlank()) out.add(line.trim());
        }
        if (out.isEmpty() && !text.isBlank()) out.add(text.trim());
        return out;
    }

    /** Preparers often type their own bullet character; do not print two. */
    private static String stripBullet(String s) {
        return s.replaceFirst("^\\s*[-*•·]\\s*", "").trim();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
