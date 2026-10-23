package com.istlgroup.istl_group_crm_backend.service.docx;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Re-brands the solar proposal skeleton's running header and footer band for the
 * company the proposal is from — the same identity the PDF rendition draws.
 *
 * <p>The skeleton was cut from one company's signed-off proposal, so its header
 * carries that company's logo ({@code media/image5.jpeg}) and its footer band
 * spells out that company's name, address and contact line. Leaving them in put
 * one company's identity on every organization's Word proposal. Here:
 * <ul>
 *   <li>the header logo is swapped for the company's own, scaled into the same
 *       box and kept right-aligned; with no logo the picture is removed, as the
 *       PDF omits it;</li>
 *   <li>each footer paragraph (name / address / contact) is rewritten in its own
 *       formatting with the profile's value, blank when the profile is blank.</li>
 * </ul>
 */
public final class DocxBranding {

    private static final String SKELETON_LOGO = "media/image5.jpeg";
    private static final String[] HEADERS = { "word/header1.xml", "word/header2.xml" };
    private static final String[] FOOTERS = { "word/footer1.xml", "word/footer2.xml" };

    /** The skeleton's logo box, EMU — the PDF's 2.178 x 0.485 in. */
    private static final long BOX_CX = 1991867L;
    private static final long BOX_CY = 443484L;
    private static final long BOX_LEFT = 4957571L;

    private static final Pattern RUN_WITH_DRAWING =
            Pattern.compile("<w:r>(?:(?!</w:r>).)*?<w:drawing>.*?</w:drawing></w:r>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>");
    private static final Pattern HYPERLINK_REL =
            Pattern.compile("<Relationship [^>]*Type=\"[^\"]*/hyperlink\"[^>]*/>");

    private DocxBranding() { }

    /**
     * @param docx    the filled proposal
     * @param logo    the company's logo bytes, or null for none
     * @param name    footer line 1 (may be blank)
     * @param address footer line 2 (may be blank)
     * @param contact footer line 3 (may be blank)
     */
    public static byte[] apply(byte[] docx, byte[] logo,
                               String name, String address, String contact) throws IOException {
        Map<String, byte[]> parts = DocxTemplate.read(docx);
        brandHeader(parts, logo);
        for (String f : FOOTERS) {
            byte[] raw = parts.get(f);
            if (raw == null) continue;
            String xml = new String(raw, StandardCharsets.UTF_8);
            xml = rewriteFooter(xml, name, address, contact);
            parts.put(f, xml.getBytes(StandardCharsets.UTF_8));
            dropHyperlinkRels(parts, "word/_rels/" + f.substring("word/".length()) + ".rels");
        }
        return DocxTemplate.write(parts);
    }

    // ── Header logo ──────────────────────────────────────────────────────────

    private static void brandHeader(Map<String, byte[]> parts, byte[] logo) {
        String ext = logo == null ? null : extensionOf(logo);
        if (ext == null) {
            for (String h : HEADERS) {
                byte[] raw = parts.get(h);
                if (raw == null) continue;
                String xml = new String(raw, StandardCharsets.UTF_8);
                parts.put(h, RUN_WITH_DRAWING.matcher(xml).replaceAll("").getBytes(StandardCharsets.UTF_8));
                String rels = "word/_rels/" + h.substring("word/".length()) + ".rels";
                byte[] r = parts.get(rels);
                if (r != null) {
                    String x = new String(r, StandardCharsets.UTF_8)
                            .replaceAll("<Relationship [^>]*Target=\"" + Pattern.quote(SKELETON_LOGO) + "\"[^>]*/>", "");
                    parts.put(rels, x.getBytes(StandardCharsets.UTF_8));
                }
            }
            dropPart(parts, SKELETON_LOGO);
            return;
        }

        String media = "media/company-logo." + ext;
        parts.put("word/" + media, logo);
        ensureContentType(parts, ext);

        long[] size = fitIntoBox(logo);
        long cx = size[0], cy = size[1];
        long left = BOX_LEFT + (BOX_CX - cx);   // keep the right edge where the skeleton's was
        for (String h : HEADERS) {
            String rels = "word/_rels/" + h.substring("word/".length()) + ".rels";
            byte[] r = parts.get(rels);
            if (r != null) {
                String x = new String(r, StandardCharsets.UTF_8).replace(SKELETON_LOGO, media);
                parts.put(rels, x.getBytes(StandardCharsets.UTF_8));
            }
            byte[] raw = parts.get(h);
            if (raw == null) continue;
            String xml = new String(raw, StandardCharsets.UTF_8)
                    .replace("cx=\"" + BOX_CX + "\" cy=\"" + BOX_CY + "\"", "cx=\"" + cx + "\" cy=\"" + cy + "\"")
                    .replace("<wp:posOffset>" + BOX_LEFT + "</wp:posOffset>", "<wp:posOffset>" + left + "</wp:posOffset>");
            parts.put(h, xml.getBytes(StandardCharsets.UTF_8));
        }
        dropPart(parts, SKELETON_LOGO);
    }

    /** Only formats Word draws everywhere; anything else (WebP) is omitted, as the PDF does. */
    static String extensionOf(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "jpeg";
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
        return null;
    }

    /** The logo's size scaled to fit the box, aspect kept; the full box if it can't be read. */
    static long[] fitIntoBox(byte[] logo) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(logo));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                double scale = Math.min((double) BOX_CX / img.getWidth(), (double) BOX_CY / img.getHeight());
                return new long[] { Math.round(img.getWidth() * scale), Math.round(img.getHeight() * scale) };
            }
        } catch (IOException | RuntimeException ignore) { /* fall through to the box */ }
        return new long[] { BOX_CX, BOX_CY };
    }

    static void ensureContentType(Map<String, byte[]> parts, String ext) {
        byte[] raw = parts.get("[Content_Types].xml");
        if (raw == null) return;
        String xml = new String(raw, StandardCharsets.UTF_8);
        if (xml.contains("Extension=\"" + ext + "\"")) return;
        xml = xml.replaceFirst("(<Types[^>]*>)",
                "$1<Default Extension=\"" + ext + "\" ContentType=\"image/" + ext + "\"/>");
        parts.put("[Content_Types].xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    /** Removes a media part once no relationship in the package still points at it. */
    static void dropPart(Map<String, byte[]> parts, String media) {
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            if (!e.getKey().endsWith(".rels")) continue;
            if (new String(e.getValue(), StandardCharsets.UTF_8).contains("\"" + media + "\"")) return;
        }
        parts.remove("word/" + media);
    }

    // ── Footer band ──────────────────────────────────────────────────────────

    /**
     * Rewrites every innermost paragraph whose text is the skeleton's company name,
     * address or contact line. The band sits in a text box with a VML fallback, so
     * each line appears more than once; all copies are rewritten.
     */
    static String rewriteFooter(String xml, String name, String address, String contact) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] p : innermostParagraphs(xml)) {
            String para = xml.substring(p[0], p[1]);
            String text = textOf(para);
            String value;
            if (text.startsWith("SESOLA POWER PROJECTS")) value = name;
            else if (text.contains("Pranava Vaishnoi")) value = address;
            else if (text.contains("sesolaenergy.com")) value = contact;
            else continue;
            out.append(xml, cursor, p[0]).append(replaceParagraphText(para, value));
            cursor = p[1];
        }
        return out.append(xml.substring(cursor)).toString();
    }

    /** [start, end) of each {@code <w:p>} that holds no nested paragraph, in document order. */
    static List<int[]> innermostParagraphs(String xml) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int close = xml.indexOf("</w:p>", from);
            if (close < 0) break;
            int open = lastParagraphOpen(xml, close);
            if (open >= 0 && xml.lastIndexOf("</w:p>", close - 1) < open) {
                out.add(new int[] { open, close + "</w:p>".length() });
            }
            from = close + 1;
        }
        return out;
    }

    private static int lastParagraphOpen(String xml, int before) {
        int i = before;
        while ((i = xml.lastIndexOf("<w:p", i - 1)) >= 0) {
            char after = xml.charAt(i + 4);
            if (after == '>' || after == ' ') return i;
        }
        return -1;
    }

    static String textOf(String para) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TEXT.matcher(para);
        while (m.find()) sb.append(m.group(1));
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Keeps the paragraph's own properties and the formatting of its last run (the
     * contact line's first runs are hyperlink-styled), and replaces all the runs —
     * hyperlinks included — with one run of {@code value}.
     */
    static String replaceParagraphText(String para, String value) {
        int openEnd = para.indexOf('>') + 1;
        String open = para.substring(0, openEnd);
        String pPr = "";
        int ps = para.indexOf("<w:pPr>");
        if (ps >= 0) pPr = para.substring(ps, para.indexOf("</w:pPr>", ps) + "</w:pPr>".length());

        String body = ps >= 0 ? para.substring(ps + pPr.length()) : para.substring(openEnd);
        String rPr = "";
        int rs = body.lastIndexOf("<w:rPr>");
        if (rs >= 0) rPr = body.substring(rs, body.indexOf("</w:rPr>", rs) + "</w:rPr>".length());

        String run = (value == null || value.isBlank()) ? ""
                : "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + DocxTemplate.escape(value.trim()) + "</w:t></w:r>";
        return open + pPr + run + "</w:p>";
    }

    private static void dropHyperlinkRels(Map<String, byte[]> parts, String rels) {
        byte[] raw = parts.get(rels);
        if (raw == null) return;
        String xml = new String(raw, StandardCharsets.UTF_8);
        parts.put(rels, HYPERLINK_REL.matcher(xml).replaceAll("").getBytes(StandardCharsets.UTF_8));
    }
}
