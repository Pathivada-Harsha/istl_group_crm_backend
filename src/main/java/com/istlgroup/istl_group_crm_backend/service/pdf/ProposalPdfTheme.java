package com.istlgroup.istl_group_crm_backend.service.pdf;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.DoubleBorder;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.VerticalAlignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

/**
 * Geometry, colour and type scale for the Solar proposal PDF, plus the element
 * factories the renderer builds every paragraph and cell through.
 *
 * <p><b>Construct one per render, never once per application.</b> A {@link PdfFont}
 * created by {@link PdfFontFactory} becomes an indirect object of the FIRST
 * {@code PdfDocument} it is written into; caching one on a {@code @Service} field
 * and reusing it for the next proposal throws
 * {@code PdfException: PdfIndirectReference ... belongs to another document}.
 * Only the raw font/asset <i>bytes</i> are cached (statically, below) — they never
 * change and reading them per render would be pointless I/O.
 *
 * <h2>Type scale</h2>
 * The Word skeleton sets its text in Times New Roman, Calibri, Arial and Trebuchet
 * MS. Those are proprietary and cannot be embedded, so everything is re-set in
 * Poppins (already shipped for the Purchase Order, and its cmap carries U+20B9 ₹
 * and U+2022 •). Poppins is a wider face, so sizes are scaled by a per-family
 * copyfit factor — TNR x0.85, Calibri x0.88, Trebuchet x0.95, Arial x1.00 — chosen
 * so a line of Poppins occupies the same measure as the original and line breaks
 * land in nearly the same places.
 */
@Slf4j
public class ProposalPdfTheme {

    // ── Page geometry (US Letter, matching the skeleton's sectPr) ───────────────
    /** Word: pgMar top=1140tw. Must clear the header logo, which ends 49.8pt down. */
    public static final float MARGIN_TOP = 57f;
    /**
     * Word uses right=0, an artefact of the reference that it compensates for with
     * large per-paragraph right indents. A symmetric margin is used instead and
     * those indents are dropped.
     */
    public static final float MARGIN_RIGHT = 36f;
    /**
     * Word: bottom=1720tw = 86pt, which lets the last line overlap the footer rule
     * by ~3.5pt. Raised for clearance — the footer band starts 90.9pt up.
     */
    public static final float MARGIN_BOTTOM = 94f;
    public static final float MARGIN_LEFT = 36f;

    /** Usable text column: 612 - 36 - 36. Every table sizes itself to this. */
    public static final float CONTENT_WIDTH = 540f;

    // ── Cover artwork geometry ─────────────────────────────────────────────────
    public static final float COVER_W = 518.4f;   // 7.20in
    public static final float COVER_H = 434.2f;   // 6.03in
    /**
     * Baselines of the three overlay lines, as a fraction of the artwork height
     * measured from its top. Two derivations of these disagreed (40/47/56% from a
     * visual read, 22/26/34% from walking the text box's spacer paragraphs), so
     * they are constants: render once and pick whichever lands the text on the
     * artwork's blank band.
     */
    public static final float COVER_LEAD_PCT  = 0.40f;
    public static final float COVER_TITLE_PCT = 0.47f;
    public static final float COVER_SUB_PCT   = 0.56f;

    // ── Colours, all lifted from the skeleton ──────────────────────────────────
    public static final DeviceRgb GREEN_LABEL = new DeviceRgb(0x90, 0xCF, 0x50); // client-info labels
    public static final DeviceRgb GREY_VALUE  = new DeviceRgb(0xED, 0xED, 0xED); // client-info values
    public static final DeviceRgb YELLOW      = new DeviceRgb(0xFF, 0xFF, 0x00); // ROI title, BOM header
    public static final DeviceRgb NAVY_COVER  = new DeviceRgb(0x1F, 0x38, 0x64); // cover text
    public static final DeviceRgb NAVY_FOOTER = new DeviceRgb(0x00, 0x00, 0x80); // footer company name
    public static final DeviceRgb FOOTER_RULE = new DeviceRgb(0xD8, 0xF6, 0xBC); // footer divider rectangle

    // ── Type scale (points, already copyfit-scaled from the Word sizes) ────────
    public static final float BODY            = 9.5f;   // TNR 11
    public static final float H1              = 9.5f;   // TNR 11 bold+underline
    public static final float FIGURE_HEAD     = 10f;    // Calibri 11 b+u (rounded up to read as a heading)
    public static final float INFO_LABEL      = 9.5f;
    public static final float INFO_VALUE      = 10f;
    public static final float PRICE_CELL      = 11f;    // TNR 13
    public static final float NOTE            = 11f;    // TNR 13
    public static final float NOTE_LEADING    = 1.8f;   // Word 2.0; Poppins' line box is taller
    public static final float AMOUNT_WORDS    = 12f;    // TNR 14
    public static final float BOM_CELL        = 12f;    // TNR 14
    public static final float SUB_HEAD        = 13.5f;  // TNR 16 b+u
    public static final float BOM_HEADING     = 14f;    // TNR 16.5 b+u
    public static final float ROI_CELL        = 12f;    // TNR 18 -> reduced so the 17-row table holds one page
    public static final float ROI_MONEY       = 13f;    // TNR 22 -> ditto
    public static final float PRICING_HEADING = 16f;    // TNR 18.5 b+u
    public static final float COVER_LEAD      = 14f;    // Calibri 15.5
    public static final float COVER_TITLE     = 24f;    // Arial 24 bold
    public static final float COVER_SUB       = 14f;    // Trebuchet 15.5
    public static final float FOOTER_NAME     = 14f;    // Arial 16 bold (Poppins Bold is heavier)
    public static final float FOOTER_ADDR     = 7.5f;   // Arial MT 8.5

    /** Word numbering indents, converted from twips: abstractNum 0/1/2. */
    public static final float BULLET_INDENT      = 36f;
    public static final float BULLET_SYMBOL_GAP  = 8f;
    public static final float SUBBULLET_INDENT   = 43f;

    // Raw bytes are immutable and shared; PdfFont objects are not (see class doc).
    private static volatile byte[] regularTtf;
    private static volatile byte[] boldTtf;

    public final PdfFont regular;
    public final PdfFont bold;

    public ProposalPdfTheme() {
        if (regularTtf == null) regularTtf = loadAsset("Poppins-Regular.ttf");
        if (boldTtf == null) boldTtf = loadAsset("Poppins-Bold.ttf");
        this.regular = font(regularTtf, StandardFonts.HELVETICA, "Poppins-Regular.ttf");
        this.bold = font(boldTtf, StandardFonts.HELVETICA_BOLD, "Poppins-Bold.ttf");
    }

    /** True when the embedded face can draw ₹; the renderer falls back to "Rs. " if not. */
    public boolean hasRupeeGlyph() {
        try {
            return regular.containsGlyph(0x20B9) && bold.containsGlyph(0x20B9);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Classpath lookup for a font or image. Proposal-specific assets win, so the
     * proposal can be re-branded later without disturbing the Purchase Order.
     */
    public static byte[] loadAsset(String name) {
        String[] paths = { "proposal-assets/" + name, "po-assets/" + name, name };
        for (String path : paths) {
            try {
                ClassPathResource res = new ClassPathResource(path);
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) { return in.readAllBytes(); }
                }
            } catch (Exception ignore) { /* try the next location */ }
        }
        log.warn("[SOLAR-PDF] asset not found on the classpath: {}", name);
        return null;
    }

    private static PdfFont font(byte[] ttf, String fallback, String label) {
        try {
            if (ttf != null) {
                return PdfFontFactory.createFont(ttf, PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            }
            log.warn("[SOLAR-PDF] {} missing; falling back to {} — the rupee sign will not render.",
                     label, fallback);
        } catch (Exception e) {
            log.warn("[SOLAR-PDF] could not load {} ({}); using {}.", label, e.getMessage(), fallback);
        }
        try { return PdfFontFactory.createFont(fallback); }
        catch (Exception e) { throw new IllegalStateException("Font load failed", e); }
    }

    // ── Element factories ──────────────────────────────────────────────────────

    public Paragraph text(String s, float size) {
        return new Paragraph(s == null ? "" : s).setFont(regular).setFontSize(size);
    }

    public Paragraph strong(String s, float size) {
        return new Paragraph(s == null ? "" : s).setFont(bold).setFontSize(size);
    }

    /** Body prose: justified, matching the skeleton's jc=both on these paragraphs. */
    public Paragraph prose(String s) {
        return text(s, BODY)
                .setTextAlignment(TextAlignment.JUSTIFIED)
                .setMarginLeft(BULLET_INDENT)
                .setMarginTop(0).setMarginBottom(6)
                .setMultipliedLeading(1.15f);
    }

    /** Section heading: bold + underlined, and glued to whatever follows it. */
    public Paragraph heading(String s) {
        return strong(s, H1).setUnderline()
                .setMarginLeft(BULLET_INDENT)
                .setMarginTop(10).setMarginBottom(5)
                .setKeepWithNext(true);
    }

    /** Centred, underlined heading above a figure or a table. */
    public Paragraph centredHeading(String s, float size) {
        return strong(s, size).setUnderline()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(12).setMarginBottom(6)
                .setKeepWithNext(true);
    }

    public Cell cell(Paragraph content) {
        return new Cell().add(content)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(4f);
    }

    /** Single 0.5pt black border — client-info and BOM tables. */
    public Cell bordered(Paragraph content) {
        return cell(content).setBorder(new SolidBorder(0.5f));
    }

    /** Double border — the Financial Pricing table's distinguishing feature. */
    public Cell doubleBordered(Paragraph content) {
        return cell(content).setBorder(new DoubleBorder(0.5f));
    }

    public Cell borderless(Paragraph content) {
        return cell(content).setBorder(Border.NO_BORDER);
    }

    /** A run of mixed bold/regular text within one paragraph. */
    public Paragraph runs(float size, Run... parts) {
        Paragraph para = new Paragraph().setFontSize(size);
        for (Run r : parts) {
            Text t = new Text(r.text()).setFont(r.bold() ? bold : regular).setFontSize(size);
            if (r.italic()) t.setItalic();
            para.add(t);
        }
        return para;
    }

    /** One formatting run inside a paragraph — the skeleton bolds mid-sentence in five places. */
    public record Run(String text, boolean bold, boolean italic) {
        public static Run of(String t) { return new Run(t, false, false); }
        public static Run b(String t) { return new Run(t, true, false); }
        public static Run i(String t) { return new Run(t, false, true); }
    }
}
