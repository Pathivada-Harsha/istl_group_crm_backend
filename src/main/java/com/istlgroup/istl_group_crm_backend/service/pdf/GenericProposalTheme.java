package com.istlgroup.istl_group_crm_backend.service.pdf;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

/**
 * Geometry, colour and type scale for the standard (non-solar) proposal PDF.
 *
 * <p>Extends {@link ProposalPdfTheme} purely to inherit the font machinery — the
 * statically cached Poppins bytes, the per-document {@link PdfFont} pair,
 * {@code hasRupeeGlyph()} and the {@code text/strong/cell/bordered/borderless/runs}
 * element factories. None of the parent's <i>constants</i> are used: those are
 * US-Letter geometry and the greens and yellows of the solar Word skeleton, chosen
 * to copyfit that document. Everything below re-declares the values this document
 * needs, and the parent class is not modified.
 *
 * <p><b>Construct one per render</b>, for the reason given on the parent: a
 * {@code PdfFont} belongs to the first {@code PdfDocument} it is written into.
 *
 * <p><b>Never call {@link ProposalPdfTheme#loadAsset} from this renderer for a logo
 * or a signature.</b> Its fallback chain ends in {@code proposal-assets/} and
 * {@code po-assets/}, which are one organization's artwork. The only image this
 * document may draw is the logo the rendering organization uploaded itself.
 */
public class GenericProposalTheme extends ProposalPdfTheme {

    // ── Page geometry (A4, print-ready) ────────────────────────────────────────
    public static final PageSize PAGE = PageSize.A4;   // 595.3 x 841.9 pt
    public static final float MARGIN_TOP    = 48f;
    public static final float MARGIN_RIGHT  = 45f;
    /** Clears the footer rule, which is drawn 42pt up. */
    public static final float MARGIN_BOTTOM = 56f;
    public static final float MARGIN_LEFT   = 45f;

    /** Usable text column: 595.3 - 45 - 45. Every table sizes itself to this. */
    public static final float CONTENT_WIDTH = 505.3f;

    // ── Colours: one accent, three neutrals. Conservative on purpose. ──────────
    public static final DeviceRgb INK    = new DeviceRgb(0x1F, 0x29, 0x37);
    public static final DeviceRgb MUTED  = new DeviceRgb(0x6B, 0x72, 0x80);
    public static final DeviceRgb RULE   = new DeviceRgb(0xD1, 0xD5, 0xDB);
    public static final DeviceRgb BAND   = new DeviceRgb(0xF3, 0xF4, 0xF6);
    public static final DeviceRgb ACCENT = new DeviceRgb(0x1F, 0x38, 0x64);

    // ── Type scale (points) ───────────────────────────────────────────────────
    public static final float TITLE      = 20f;
    public static final float ORG_NAME   = 16f;
    public static final float SECTION    = 12.5f;
    public static final float BODY_TEXT  = 10f;
    public static final float TABLE_CELL = 9.5f;
    public static final float SMALL      = 8f;
    public static final float TOTAL      = 14f;

    /** Border weight used by every table in this document. */
    public static final float BORDER_W = 0.5f;

    /**
     * A section heading: bold, accent-coloured, with a hairline under it, glued to
     * whatever follows so a heading can never be orphaned at a page foot.
     */
    public Paragraph sectionHeading(String s) {
        return strong(s, SECTION)
                .setFontColor(ACCENT)
                .setMarginTop(16).setMarginBottom(7)
                .setPaddingBottom(3)
                .setBorderBottom(new SolidBorder(RULE, BORDER_W))
                .setKeepWithNext(true);
    }

    /**
     * Body prose: justified, full measure. The inherited {@code prose()} indents by
     * the Word skeleton's list indent, which this layout does not use.
     */
    public Paragraph body(String s) {
        return text(s, BODY_TEXT)
                .setFontColor(INK)
                .setTextAlignment(TextAlignment.JUSTIFIED)
                .setMarginTop(0).setMarginBottom(7)
                .setMultipliedLeading(1.35f);
    }

    /** A bullet line, for terms authored one per line. */
    public Paragraph bullet(String s) {
        return body("•   " + s)
                .setTextAlignment(TextAlignment.LEFT)
                .setMarginLeft(10)
                .setMarginBottom(4);
    }

    /** A small, muted label — table captions and signature-block rules. */
    public Paragraph label(String s) {
        return text(s, SMALL).setFontColor(MUTED);
    }

    /**
     * Column widths as percentages of the measure, from any set of relative weights.
     * (The solar renderer has the same helper, but it is private to it.)
     */
    public static UnitValue[] widths(float... weights) {
        float total = 0f;
        for (float w : weights) total += w;
        float[] pct = new float[weights.length];
        for (int i = 0; i < weights.length; i++) pct[i] = weights[i] * 100f / total;
        return UnitValue.createPercentArray(pct);
    }
}
