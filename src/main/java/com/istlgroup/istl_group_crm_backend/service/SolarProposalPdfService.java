package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.pdf.ProposalCopy;
import com.istlgroup.istl_group_crm_backend.service.pdf.ProposalPdfTheme;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.ListNumberingType;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the Solar proposal straight to PDF with iText.
 *
 * <p>This is a SECOND rendition of the same document {@link SolarProposalDocService}
 * produces as a .docx — not a replacement. The .docx remains the artifact sent to
 * clients; the PDF exists so the proposal can be previewed faithfully in the
 * browser. A .docx cannot be: its cover is a DrawingML grouped shape (wpg) and no
 * client-side renderer draws those, so the preview always lost the cover art.
 *
 * <p><b>Both renditions consume the identical token map</b> that
 * {@code SolarProposalDocService.generate()} already builds, so the PDF can never
 * show a different total, date or rounding than the Word file. All the conditional
 * logic (subsidy/ROI inclusion and its guards) is applied before those maps are
 * handed over — this class only reads {@code blocks}.
 *
 * <p>Typography is Poppins throughout; see {@link ProposalPdfTheme} for why, and
 * for the copyfit scale. Boilerplate prose comes from {@link ProposalCopy}, which
 * is generated from the Word skeleton so the two can't drift.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SolarProposalPdfService {

    private final ProposalCopy copy;

    /** Footer band, page-anchored in the skeleton's footer1.xml. */
    private static final String FOOTER_NAME = "SESOLA POWER PROJECTS PRIVATE LIMITED";
    private static final String FOOTER_ADDR =
            "8th Floor, Pranava Vaishnoi Business Park, Kothaguda Village, "
            + "Serilingampally Mandal, Ranga Reddy District, Telangana India 500084";
    private static final String FOOTER_CONTACT =
            "www.sesolaenergy.com | Email: info@sesolaenergy.com | M: +91 83400 20020";

    /**
     * @param tokens  the same {@code {{TOKEN}} -> value} map the .docx filler uses
     * @param blocks  {@code SUBSIDY} / {@code ROI} inclusion, already resolved
     * @param repeats {@code BOM -> } one map per row (SL, COMPONENT, SPEC, MAKE, QTY, UNIT)
     */
    public byte[] render(Map<String, String> tokens,
                         Map<String, Boolean> blocks,
                         Map<String, List<Map<String, String>>> repeats) throws CustomException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
            Document doc = new Document(pdf, PageSize.LETTER);
            doc.setMargins(ProposalPdfTheme.MARGIN_TOP, ProposalPdfTheme.MARGIN_RIGHT,
                           ProposalPdfTheme.MARGIN_BOTTOM, ProposalPdfTheme.MARGIN_LEFT);

            // One theme (and therefore one pair of PdfFonts) per document — see the
            // class doc on ProposalPdfTheme for why this must not be shared.
            ProposalPdfTheme th = new ProposalPdfTheme();
            if (!th.hasRupeeGlyph()) {
                log.warn("[SOLAR-PDF] embedded font has no U+20B9; rupee amounts will fall back to 'Rs.'");
            }

            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new HeaderFooterHandler(th));

            String docTitle = tok(tokens, "DOC_TITLE");
            if (!docTitle.isBlank()) pdf.getDocumentInfo().setTitle(docTitle);

            cover(doc, th, tokens);
            clientInfo(doc, th, tokens);
            doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

            companySections(doc, th);
            figures(doc, th);
            pricing(doc, th, tokens);

            if (Boolean.TRUE.equals(blocks.get("SUBSIDY"))) subsidy(doc, th, tokens);
            if (Boolean.TRUE.equals(blocks.get("ROI"))) roi(doc, th, tokens);

            noteAndWarranty(doc, th, tokens);
            billOfMaterial(doc, th, repeats == null ? null : repeats.get("BOM"));
            signatory(doc, th);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Solar proposal PDF render failed", e);
            throw new CustomException("Could not render the proposal PDF: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Running header + footer, drawn on every page
    // ═════════════════════════════════════════════════════════════════════════

    private static final class HeaderFooterHandler implements IEventHandler {
        private final ProposalPdfTheme th;
        private final byte[] logo;

        HeaderFooterHandler(ProposalPdfTheme th) {
            this.th = th;
            this.logo = ProposalPdfTheme.loadAsset("header-logo.jpg");
        }

        @Override
        public void handleEvent(Event event) {
            try {
                PdfDocumentEvent ev = (PdfDocumentEvent) event;
                PdfDocument pdfDoc = ev.getDocument();
                PdfPage page = ev.getPage();
                Rectangle ps = page.getPageSize();
                // newContentStreamBefore: the band paints UNDER the flowed content.
                PdfCanvas pdfCanvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
                Canvas canvas = new Canvas(pdfCanvas, ps);

                // Header: logo top-right, 2.178 x 0.485in, 0.207in down from the page edge.
                if (logo != null) {
                    try {
                        Image img = new Image(ImageDataFactory.create(logo));
                        img.scaleToFit(156.8f, 34.9f);
                        img.setFixedPosition(ps.getWidth() - ProposalPdfTheme.MARGIN_RIGHT - 156.8f,
                                             ps.getTop() - 14.9f - 34.9f);
                        canvas.add(img);
                    } catch (Exception ignore) { /* the logo is not load-bearing */ }
                }

                // Footer: a filled pale-green rectangle (the skeleton's "divider" is a
                // shape, not a rule), then the centred three-line address block.
                float fx = ProposalPdfTheme.MARGIN_LEFT;
                float fw = ps.getWidth() - ProposalPdfTheme.MARGIN_LEFT - ProposalPdfTheme.MARGIN_RIGHT;
                pdfCanvas.saveState()
                        .setFillColor(ProposalPdfTheme.FOOTER_RULE)
                        .rectangle(fx, 90.9f, fw, 1.45f)
                        .fill()
                        .restoreState();

                Div footer = new Div().setFixedPosition(fx, 30f, fw).setTextAlignment(TextAlignment.CENTER);
                footer.add(th.strong(FOOTER_NAME, ProposalPdfTheme.FOOTER_NAME)
                        .setFontColor(ProposalPdfTheme.NAVY_FOOTER).setMargin(0).setMultipliedLeading(1f));
                footer.add(th.text(FOOTER_ADDR, ProposalPdfTheme.FOOTER_ADDR)
                        .setMargin(0).setMultipliedLeading(1.1f));
                footer.add(th.text(FOOTER_CONTACT, ProposalPdfTheme.FOOTER_ADDR)
                        .setMargin(0).setMultipliedLeading(1.1f));
                canvas.add(footer);
                canvas.close();
            } catch (Exception e) {
                log.warn("[SOLAR-PDF] header/footer render issue: {}", e.getMessage());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1 — Cover
    // ═════════════════════════════════════════════════════════════════════════

    private void cover(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        // Downsampled to ~153 DPI for the 7.20in it is drawn at, and re-encoded as
        // JPEG (the source is fully opaque, so the PNG's alpha channel was dead
        // weight). 2.56 MB -> 0.14 MB, which keeps the whole PDF near 1 MB instead
        // of ~5 MB — it is stored in the same INSERT as the ~9 MB .docx.
        byte[] art = ProposalPdfTheme.loadAsset("cover.jpg");
        float top = PageSize.LETTER.getTop() - ProposalPdfTheme.MARGIN_TOP;

        if (art != null) {
            Image img = new Image(ImageDataFactory.create(art));
            img.scaleAbsolute(ProposalPdfTheme.COVER_W, ProposalPdfTheme.COVER_H);
            img.setFixedPosition(1,
                    ProposalPdfTheme.MARGIN_LEFT + (ProposalPdfTheme.CONTENT_WIDTH - ProposalPdfTheme.COVER_W) / 2f,
                    top - ProposalPdfTheme.COVER_H);
            doc.add(img);
        }

        String title = tok(tokens, "COVER_TITLE");
        String subtitle = tok(tokens, "COVER_SUBTITLE");

        overlay(doc, th.text(copy.get("cover.lead"), ProposalPdfTheme.COVER_LEAD),
                top, ProposalPdfTheme.COVER_LEAD_PCT, ProposalPdfTheme.COVER_LEAD);
        overlay(doc, th.strong(title, coverTitleSize(th.bold, title)),
                top, ProposalPdfTheme.COVER_TITLE_PCT, ProposalPdfTheme.COVER_TITLE);
        if (!subtitle.isBlank()) {
            overlay(doc, th.text("| " + subtitle + " |", ProposalPdfTheme.COVER_SUB),
                    top, ProposalPdfTheme.COVER_SUB_PCT, ProposalPdfTheme.COVER_SUB);
        }

        // The artwork is absolutely placed, so reserve its height in the flow.
        doc.add(new Paragraph().setMarginTop(ProposalPdfTheme.COVER_H).setMargin(0)
                .setHeight(ProposalPdfTheme.COVER_H));
    }

    /** Places one centred cover line at {@code pct} of the artwork height. */
    private void overlay(Document doc, Paragraph p, float artTop, float pct, float size) {
        float y = artTop - (pct * ProposalPdfTheme.COVER_H) - (size * 1.2f);
        doc.add(p.setFontColor(ProposalPdfTheme.NAVY_COVER)
                .setTextAlignment(TextAlignment.CENTER)
                .setFixedPosition(1, ProposalPdfTheme.MARGIN_LEFT, y, ProposalPdfTheme.CONTENT_WIDTH));
    }

    /** Client names run long; shrink rather than wrap or overflow the artwork. */
    private float coverTitleSize(PdfFont bold, String title) {
        float size = ProposalPdfTheme.COVER_TITLE;
        try {
            while (size > 14f && bold.getWidth(title, size) > ProposalPdfTheme.COVER_W - 40f) size -= 1f;
        } catch (Exception ignore) { /* keep the default */ }
        return size;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2 — Client info table
    // ═════════════════════════════════════════════════════════════════════════

    private void clientInfo(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        Table t = new Table(widths(5647, 5525))
                .useAllAvailableWidth()
                .setMarginTop(14)
                .setKeepTogether(true);

        infoRow(t, th, copy.get("table.client.client"), tok(tokens, "CLIENT_NAME"));
        infoRow(t, th, copy.get("table.client.location"), tok(tokens, "SITE_LOCATION"));
        infoRow(t, th, copy.get("table.client.capacity"), tok(tokens, "CAPACITY_LINE"));

        doc.add(t);
    }

    private void infoRow(Table t, ProposalPdfTheme th, String label, String value) {
        t.addCell(th.bordered(th.strong(label, ProposalPdfTheme.INFO_LABEL)
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(ProposalPdfTheme.GREEN_LABEL).setMinHeight(30f));
        t.addCell(th.bordered(th.strong(value, ProposalPdfTheme.INFO_VALUE)
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(ProposalPdfTheme.GREY_VALUE).setMinHeight(30f));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3-8 — Company boilerplate
    // ═════════════════════════════════════════════════════════════════════════

    /** Heading key, optional lead-in, bullet keys, optional closing prose. */
    private record Section(String heading, String lead, List<String> bullets, String closing) { }

    private void companySections(Document doc, ProposalPdfTheme th) {
        List<Section> sections = List.of(
            new Section("overview.heading", null, List.of(), null),
            new Section("expertise.heading", "expertise.lead",
                keys("expertise.bullet.", 6), "expertise.closing"),
            new Section("epc.heading", "epc.lead", keys("epc.bullet.", 5), "epc.closing"),
            new Section("rnd.heading", "rnd.lead", keys("rnd.bullet.", 4), "rnd.closing"),
            new Section("global.heading", "global.lead", keys("global.bullet.", 2), "global.closing"),
            new Section("safety.heading", null, List.of(), null)
        );

        // Overview is prose-only; render its four paragraphs with the skeleton's
        // mid-sentence emphasis.
        doc.add(th.heading(copy.get("overview.heading")));
        doc.add(emphasised(th, copy.get("overview.p1"),
                "Sesola Power Projects Pvt Ltd", "Sesola Energy"));
        doc.add(th.prose(copy.get("overview.p2")));
        doc.add(th.prose(copy.get("overview.p3")));
        doc.add(emphasised(th, copy.get("overview.p4"),
                "Sesola has cumulatively completed over 300 MW of solar power projects across India"));

        for (Section s : sections.subList(1, sections.size() - 1)) {
            doc.add(th.heading(copy.get(s.heading())));
            if (s.lead() != null) doc.add(th.prose(copy.get(s.lead())));
            if (!s.bullets().isEmpty()) doc.add(bullets(th, s.bullets()));
            if (s.closing() != null) doc.add(th.prose(copy.get(s.closing())));
        }

        // Safety has an extra lead-in between its prose and its bullets.
        doc.add(th.heading(copy.get("safety.heading")));
        doc.add(th.prose(copy.get("safety.p1")));
        doc.add(th.prose(copy.get("safety.lead")).setKeepWithNext(true));
        doc.add(bullets(th, keys("safety.bullet.", 5)));
        doc.add(emphasised(th, copy.get("safety.closing"), "zero-incident project execution"));
    }

    private List<String> keys(String prefix, int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) out.add(prefix + i);
        return out;
    }

    private com.itextpdf.layout.element.List bullets(ProposalPdfTheme th, List<String> copyKeys) {
        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List()
                .setListSymbol("•")
                .setSymbolIndent(ProposalPdfTheme.BULLET_SYMBOL_GAP)
                .setMarginLeft(ProposalPdfTheme.BULLET_INDENT)
                .setMarginBottom(6);
        for (String k : copyKeys) {
            list.add((ListItem) new ListItem()
                    .add(th.text(copy.get(k), ProposalPdfTheme.BODY).setMargin(0))
                    .setKeepTogether(true));
        }
        return list;
    }

    /**
     * Body prose with the skeleton's bold phrases restored. The phrases are matched
     * as substrings of the copy value, so if sales rewords the sentence the text
     * still renders — it just loses the emphasis rather than breaking.
     */
    private Paragraph emphasised(ProposalPdfTheme th, String full, String... boldParts) {
        List<ProposalPdfTheme.Run> runs = new ArrayList<>();
        String rest = full;
        for (String part : boldParts) {
            int at = rest.indexOf(part);
            if (at < 0) continue;
            if (at > 0) runs.add(ProposalPdfTheme.Run.of(rest.substring(0, at)));
            runs.add(ProposalPdfTheme.Run.b(part));
            rest = rest.substring(at + part.length());
        }
        if (!rest.isEmpty()) runs.add(ProposalPdfTheme.Run.of(rest));
        if (runs.isEmpty()) return th.prose(full);

        return th.runs(ProposalPdfTheme.BODY, runs.toArray(new ProposalPdfTheme.Run[0]))
                .setTextAlignment(TextAlignment.JUSTIFIED)
                .setMarginLeft(ProposalPdfTheme.BULLET_INDENT)
                .setMarginTop(0).setMarginBottom(6)
                .setMultipliedLeading(1.15f);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9-11 — Figures
    // ═════════════════════════════════════════════════════════════════════════

    private void figures(Document doc, ProposalPdfTheme th) {
        figure(doc, th, "figure.works.heading", "works.jpg", 424.8f, 306.7f);
        figure(doc, th, "figure.system.heading", "system.jpg", 396f, 221f);
        figure(doc, th, "figure.netmetering.heading", "net-metering.jpg", 460.8f, 240.5f);
    }

    /** Heading and image travel together, matching the skeleton's keepNext. */
    private void figure(Document doc, ProposalPdfTheme th, String headingKey,
                        String asset, float w, float h) {
        byte[] bytes = ProposalPdfTheme.loadAsset(asset);
        Div block = new Div().setKeepTogether(true);
        block.add(th.centredHeading(copy.get(headingKey), ProposalPdfTheme.FIGURE_HEAD));
        if (bytes != null) {
            Image img = new Image(ImageDataFactory.create(bytes));
            img.scaleAbsolute(w, h);
            img.setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
            block.add(new Div().add(img).setMarginBottom(8));
        }
        doc.add(block);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 12 — Financial pricing
    // ═════════════════════════════════════════════════════════════════════════

    private void pricing(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        doc.add(th.centredHeading(copy.get("pricing.heading"), ProposalPdfTheme.PRICING_HEADING));

        Table t = new Table(widths(606, 4525, 2445, 847, 1395, 1246))
                .useAllAvailableWidth()
                .setKeepTogether(true);

        String[] heads = {
            copy.get("table.pricing.sno"),
            copy.get("table.pricing.desc"),
            copy.get("table.pricing.price", tok(tokens, "CAPACITY_LABEL")),
            copy.get("table.pricing.gstpct"),
            copy.get("table.pricing.gstamt"),
            copy.get("table.pricing.total"),
        };
        for (String h : heads) {
            t.addHeaderCell(th.doubleBordered(th.strong(h, ProposalPdfTheme.PRICE_CELL)
                    .setTextAlignment(TextAlignment.CENTER)).setMinHeight(40f));
        }

        String[] row = {
            "1", tok(tokens, "WORK_DESC"), tok(tokens, "PRICE_BASE"),
            tok(tokens, "GST_PCT"), tok(tokens, "GST_AMOUNT"), tok(tokens, "PRICE_TOTAL"),
        };
        for (int i = 0; i < row.length; i++) {
            // Description reads left; everything else is centred.
            TextAlignment al = (i == 1) ? TextAlignment.LEFT : TextAlignment.CENTER;
            t.addCell(th.doubleBordered(th.strong(row[i], ProposalPdfTheme.PRICE_CELL)
                    .setTextAlignment(al)).setMinHeight(40f));
        }
        doc.add(t);

        doc.add(th.strong(copy.get("amount.words.label") + " " + tok(tokens, "AMOUNT_WORDS"),
                          ProposalPdfTheme.AMOUNT_WORDS)
                .setMarginTop(8).setMarginBottom(4));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 13 — Subsidy (conditional)
    // ═════════════════════════════════════════════════════════════════════════

    private void subsidy(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        Div block = new Div().setKeepTogether(true).setMarginTop(8);
        block.add(th.strong(copy.get("subsidy.heading"), ProposalPdfTheme.SUB_HEAD).setUnderline()
                .setMarginBottom(4));

        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List()
                .setListSymbol("•")
                .setSymbolIndent(ProposalPdfTheme.BULLET_SYMBOL_GAP)
                .setMarginLeft(ProposalPdfTheme.BULLET_INDENT);
        for (String key : new String[]{"SUBSIDY_LINE", "SUBSIDY_NOTE_1", "SUBSIDY_NOTE_2", "SUBSIDY_NOTE_3"}) {
            String v = tok(tokens, key);
            if (v.isBlank()) continue;
            list.add((ListItem) new ListItem()
                    .add(th.text(v, ProposalPdfTheme.NOTE).setMargin(0)
                            .setMultipliedLeading(ProposalPdfTheme.NOTE_LEADING))
                    .setKeepTogether(true));
        }
        block.add(list);
        doc.add(block);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 14 — ROI analysis (conditional)
    // ═════════════════════════════════════════════════════════════════════════

    private void roi(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        Table t = new Table(widths(954, 5293, 4602))
                .useAllAvailableWidth()
                .setMarginTop(10)
                .setKeepTogether(true);

        // Title band spans all three columns.
        t.addHeaderCell(new Cell(1, 3)
                .add(th.strong(copy.get("table.roi.title", tok(tokens, "ROI_TITLE_CAP")),
                               ProposalPdfTheme.ROI_MONEY)
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(ProposalPdfTheme.YELLOW)
                .setBorder(new SolidBorder(0.5f))
                .setPadding(5f));

        for (String h : new String[]{copy.get("table.roi.sno"), copy.get("table.roi.particular"),
                                     copy.get("table.roi.value")}) {
            t.addHeaderCell(th.bordered(th.strong(h, ProposalPdfTheme.ROI_CELL)
                    .setTextAlignment(TextAlignment.CENTER)));
        }

        String[] valueTokens = {
            "ROI_CAPACITY", "PRICE_BASE", "GST_AMOUNT_ROUNDED", "PRICE_TOTAL", "ROI_TARIFF",
            "ROI_SPECIFIC_GEN", "ROI_ANNUAL_GEN", "ROI_ANNUAL_SAVINGS", "ROI_MONTHLY_SAVINGS",
            "ROI_PAYBACK", "ROI_ANNUAL_ROI", "ROI_LIFE", "ROI_LIFETIME_GEN",
            "ROI_LIFETIME_SAVINGS", "ROI_NET_BENEFIT",
        };
        for (int i = 0; i < valueTokens.length; i++) {
            String particular = (i == 2)
                    ? copy.get("table.roi.row.3", tok(tokens, "GST_PCT"))
                    : copy.get("table.roi.row." + (i + 1));
            t.addCell(th.bordered(th.text(String.valueOf(i + 1), ProposalPdfTheme.ROI_CELL)
                    .setTextAlignment(TextAlignment.CENTER)));
            t.addCell(th.bordered(th.text(particular, ProposalPdfTheme.ROI_CELL)));
            t.addCell(th.bordered(th.strong(tok(tokens, valueTokens[i]), ProposalPdfTheme.ROI_CELL)
                    .setTextAlignment(TextAlignment.CENTER)));
        }
        doc.add(t);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 15-16 — Note, with Warranty as item 7 carrying its own bullets
    // ═════════════════════════════════════════════════════════════════════════

    private void noteAndWarranty(Document doc, ProposalPdfTheme th, Map<String, String> tokens) {
        doc.add(th.strong(copy.get("note.heading"), ProposalPdfTheme.SUB_HEAD).setUnderline()
                .setMarginTop(10).setMarginBottom(4).setKeepWithNext(true));

        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List(ListNumberingType.DECIMAL)
                .setSymbolIndent(ProposalPdfTheme.BULLET_SYMBOL_GAP)
                .setMarginLeft(ProposalPdfTheme.BULLET_INDENT);

        // Item 1 carries the quote-validity tokens.
        String first = copy.get("note.1")
                .replace("{{QUOTE_VALID_DAYS}}", tok(tokens, "QUOTE_VALID_DAYS"))
                .replace("{{QUOTE_VALID_DAY}}", tok(tokens, "QUOTE_VALID_DAY"))
                .replace("{{QUOTE_VALID_DATE}}", tok(tokens, "QUOTE_VALID_DATE"));
        list.add(noteItem(th, first));
        for (int i = 2; i <= 6; i++) list.add(noteItem(th, copy.get("note." + i)));

        // "Warranty:" is item 7 of this same run in the skeleton, not a heading of
        // its own, and its three lines are bullets nested under it.
        ListItem warranty = noteItem(th, copy.get("note.7"));
        com.itextpdf.layout.element.List sub = new com.itextpdf.layout.element.List()
                .setListSymbol("•")
                .setSymbolIndent(ProposalPdfTheme.BULLET_SYMBOL_GAP)
                .setMarginLeft(ProposalPdfTheme.SUBBULLET_INDENT - ProposalPdfTheme.BULLET_INDENT);
        for (int i = 1; i <= 3; i++) {
            sub.add((ListItem) new ListItem()
                    .add(th.text(copy.get("warranty." + i), ProposalPdfTheme.NOTE).setMargin(0)
                            .setMultipliedLeading(ProposalPdfTheme.NOTE_LEADING))
                    .setKeepTogether(true));
        }
        warranty.add(sub);
        list.add(warranty);

        doc.add(list);
    }

    private ListItem noteItem(ProposalPdfTheme th, String s) {
        return (ListItem) new ListItem()
                .add(th.text(s, ProposalPdfTheme.NOTE).setMargin(0)
                        .setMultipliedLeading(ProposalPdfTheme.NOTE_LEADING))
                .setKeepTogether(true);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 17 — Bill of Material
    // ═════════════════════════════════════════════════════════════════════════

    private void billOfMaterial(Document doc, ProposalPdfTheme th, List<Map<String, String>> rows) {
        doc.add(th.centredHeading(copy.get("bom.heading"), ProposalPdfTheme.BOM_HEADING));

        Table t = new Table(widths(962, 1651, 4637, 1979, 859, 1016))
                .useAllAvailableWidth();

        String[] heads = {
            copy.get("table.bom.sl"), copy.get("table.bom.component"), copy.get("table.bom.spec"),
            copy.get("table.bom.make"), copy.get("table.bom.qty"), copy.get("table.bom.unit"),
        };
        // addHeaderCell repeats the row automatically on every page the table spans.
        for (String h : heads) {
            t.addHeaderCell(th.bordered(th.strong(h, ProposalPdfTheme.BOM_CELL)
                            .setTextAlignment(TextAlignment.CENTER))
                    .setBackgroundColor(ProposalPdfTheme.YELLOW).setMinHeight(26f));
        }

        // An empty BOM still gets one blank row so the table never collapses,
        // matching the .docx filler's behaviour.
        List<Map<String, String>> body = (rows == null || rows.isEmpty())
                ? List.of(Map.of()) : rows;
        String[] keys = {"SL", "COMPONENT", "SPEC", "MAKE", "QTY", "UNIT"};
        for (Map<String, String> r : body) {
            for (int i = 0; i < keys.length; i++) {
                // Specifications is the one left-aligned column.
                TextAlignment al = (i == 2) ? TextAlignment.LEFT : TextAlignment.CENTER;
                t.addCell(th.bordered(th.text(r.getOrDefault(keys[i], ""), ProposalPdfTheme.BOM_CELL)
                                .setTextAlignment(al))
                        .setMinHeight(26f)
                        .setKeepTogether(true));   // a row must not split across pages
            }
        }
        doc.add(t);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 18 — Signatory
    // ═════════════════════════════════════════════════════════════════════════

    private void signatory(Document doc, ProposalPdfTheme th) {
        Div block = new Div().setKeepTogether(true).setMarginTop(16);
        block.add(th.strong(copy.get("signatory.regards"), ProposalPdfTheme.AMOUNT_WORDS).setMarginBottom(6));
        block.add(th.strong(copy.get("signatory.company"), ProposalPdfTheme.AMOUNT_WORDS).setMarginBottom(2));

        byte[] sign = ProposalPdfTheme.loadAsset("signature.jpg");
        if (sign != null) {
            Image img = new Image(ImageDataFactory.create(sign));
            img.scaleAbsolute(93.6f, 73.4f);
            block.add(new Div().add(img).setMarginBottom(2));
        }
        block.add(th.strong(copy.get("signatory.name"), ProposalPdfTheme.AMOUNT_WORDS).setMargin(0));
        block.add(th.strong(copy.get("signatory.phone"), ProposalPdfTheme.AMOUNT_WORDS).setMargin(0));
        block.add(th.strong(copy.get("signatory.title"), ProposalPdfTheme.AMOUNT_WORDS).setMargin(0));
        doc.add(block);
    }

    // ═════════════════════════════════════════════════════════════════════════

    private static String tok(Map<String, String> tokens, String key) {
        String v = tokens == null ? null : tokens.get(key);
        return v == null ? "" : v;
    }

    /**
     * Column proportions, given as the skeleton's raw twip grid widths.
     * {@code UnitValue.createPercentArray} takes PERCENTAGES, so the twips must be
     * normalised to sum to 100 — feeding it 11,104 makes iText warn and guess.
     * Normalising keeps the reference's exact proportions while letting the table
     * size itself to whatever column width the page has.
     */
    private static UnitValue[] widths(float... twips) {
        float total = 0f;
        for (float w : twips) total += w;
        float[] pct = new float[twips.length];
        for (int i = 0; i < twips.length; i++) pct[i] = twips[i] * 100f / total;
        return UnitValue.createPercentArray(pct);
    }
}
