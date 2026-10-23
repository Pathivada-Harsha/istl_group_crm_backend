package com.istlgroup.istl_group_crm_backend.service.docx;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Re-signs the solar proposal skeleton's closing block for the company the proposal is
 * from, with the signatory chosen when it was generated — the same block the PDF rendition
 * draws.
 *
 * <p>The skeleton was cut from one company's signed-off proposal, so it ends
 * "For Sesola Power Projects Pvt. Ltd.", that company's signature scan
 * ({@code media/image9.jpeg}) and its manager's name, phone and title, as fixed text.
 * Left alone, every organization's Word proposal was signed by that manager. Here:
 * <ul>
 *   <li>the "For …" line names the proposal's own company (blank when its profile is);</li>
 *   <li>the name, phone and title lines carry the signer's values, blank without one;</li>
 *   <li>the scan is replaced by the signer's image, scaled into the same box; with no
 *       image it is removed and the space is kept for a hand signature.</li>
 * </ul>
 * The template file itself is untouched; this runs on each generated copy.
 */
public final class DocxSignatory {

    private static final String DOCUMENT = "word/document.xml";
    private static final String DOCUMENT_RELS = "word/_rels/document.xml.rels";
    private static final String SKELETON_SIGNATURE = "media/image9.jpeg";
    private static final String SIGNATURE_REL = "rId18";

    /** The skeleton's signature box, EMU (the PDF's 93.6 x 73.4 pt). */
    private static final long BOX_CX = 1188720L;
    private static final long BOX_CY = 932688L;

    /** The skeleton's own text for each line, as {@link DocxBranding#textOf} reads it. */
    private static final String SKELETON_COMPANY = "For Sesola Power Projects Pvt. Ltd.";
    private static final String SKELETON_NAME = "Sudhakar G";
    private static final String SKELETON_PHONE = "+91-7013759123";
    private static final String SKELETON_TITLE = "Executive Manager – Procurement";

    /** The skeleton's signer lines: rewritten on every proposal, so not boilerplate. */
    public static final java.util.List<String> SKELETON_LINES =
            java.util.List.of(SKELETON_COMPANY, SKELETON_NAME, SKELETON_PHONE, SKELETON_TITLE);

    private DocxSignatory() { }

    /**
     * @param docx        the filled proposal
     * @param legalName   the company signed for; blank leaves the "For" line empty
     * @param name        signer's name, or null for no signer
     * @param phone       signer's phone, or null
     * @param title       signer's title, or null
     * @param image       signer's signature image, or null for a blank space
     */
    public static byte[] apply(byte[] docx, String legalName,
                               String name, String phone, String title, byte[] image) throws IOException {
        Map<String, byte[]> parts = DocxTemplate.read(docx);
        byte[] raw = parts.get(DOCUMENT);
        if (raw == null) return docx;

        String xml = new String(raw, StandardCharsets.UTF_8);
        xml = rewriteLines(xml, blank(legalName) ? "" : "For " + legalName.trim(), name, phone, title);
        xml = replaceSignature(parts, xml, image);
        parts.put(DOCUMENT, xml.getBytes(StandardCharsets.UTF_8));
        return DocxTemplate.write(parts);
    }

    /** Rewrites each signatory paragraph in its own formatting. Only the closing block matches. */
    static String rewriteLines(String xml, String company, String name, String phone, String title) {
        // The block is the document's last few paragraphs; starting at "Thanks & Regards"
        // keeps the scan off the 4 MB of body above it.
        int from = xml.lastIndexOf("Regards,");
        if (from < 0) return xml;
        from = Math.max(0, xml.lastIndexOf("<w:p ", from));

        String head = xml.substring(0, from);
        String tail = xml.substring(from);
        StringBuilder out = new StringBuilder(head);
        int cursor = 0;
        for (int[] p : DocxBranding.innermostParagraphs(tail)) {
            String para = tail.substring(p[0], p[1]);
            String text = DocxBranding.textOf(para);
            String value;
            if (text.equals(SKELETON_COMPANY)) value = company;
            else if (text.equals(SKELETON_NAME)) value = name;
            else if (text.equals(SKELETON_PHONE)) value = phone;
            else if (text.equals(SKELETON_TITLE)) value = title;
            else continue;
            out.append(tail, cursor, p[0]).append(DocxBranding.replaceParagraphText(para, value));
            cursor = p[1];
        }
        return out.append(tail.substring(cursor)).toString();
    }

    private static String replaceSignature(Map<String, byte[]> parts, String xml, byte[] image) {
        String embed = "r:embed=\"" + SIGNATURE_REL + "\"";
        int at = xml.indexOf(embed);
        if (at < 0) return xml;

        String ext = image == null ? null : DocxBranding.extensionOf(image);
        if (ext == null) {
            // No image: the drawing goes, and three empty lines keep room to sign by hand.
            int runStart = xml.lastIndexOf("<w:r>", at);
            int runEnd = xml.indexOf("</w:r>", at);
            if (runStart < 0 || runEnd < 0) return xml;
            xml = xml.substring(0, runStart) + "<w:r><w:br/><w:br/><w:br/></w:r>"
                    + xml.substring(runEnd + "</w:r>".length());
            byte[] rels = parts.get(DOCUMENT_RELS);
            if (rels != null) {
                String r = new String(rels, StandardCharsets.UTF_8).replaceAll(
                        "<Relationship [^>]*Id=\"" + SIGNATURE_REL + "\"[^>]*/>", "");
                parts.put(DOCUMENT_RELS, r.getBytes(StandardCharsets.UTF_8));
            }
            DocxBranding.dropPart(parts, SKELETON_SIGNATURE);
            return xml;
        }

        String media = "media/signatory." + ext;
        parts.put("word/" + media, image);
        DocxBranding.ensureContentType(parts, ext);
        byte[] rels = parts.get(DOCUMENT_RELS);
        if (rels != null) {
            String r = new String(rels, StandardCharsets.UTF_8).replaceAll(
                    "(<Relationship [^>]*Id=\"" + SIGNATURE_REL + "\"[^>]*Target=\")" + Pattern.quote(SKELETON_SIGNATURE) + "\"",
                    "$1" + media + "\"");
            parts.put(DOCUMENT_RELS, r.getBytes(StandardCharsets.UTF_8));
        }
        long[] size = fitIntoBox(image);
        int runStart = xml.lastIndexOf("<w:r>", at);
        int runEnd = xml.indexOf("</w:r>", at);
        if (runStart >= 0 && runEnd >= 0) {
            String run = xml.substring(runStart, runEnd)
                    .replace("cx=\"" + BOX_CX + "\" cy=\"" + BOX_CY + "\"",
                             "cx=\"" + size[0] + "\" cy=\"" + size[1] + "\"");
            xml = xml.substring(0, runStart) + run + xml.substring(runEnd);
        }
        DocxBranding.dropPart(parts, SKELETON_SIGNATURE);
        return xml;
    }

    /** The image's size scaled to fit the box, aspect kept; the full box if it can't be read. */
    static long[] fitIntoBox(byte[] image) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                double scale = Math.min((double) BOX_CX / img.getWidth(), (double) BOX_CY / img.getHeight());
                return new long[] { Math.round(img.getWidth() * scale), Math.round(img.getHeight() * scale) };
            }
        } catch (IOException | RuntimeException ignore) { /* fall through to the box */ }
        return new long[] { BOX_CX, BOX_CY };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
