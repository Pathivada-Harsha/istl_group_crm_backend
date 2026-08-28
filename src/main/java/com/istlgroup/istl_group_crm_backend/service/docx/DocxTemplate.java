package com.istlgroup.istl_group_crm_backend.service.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Fills a .docx that was prepared as a fixed skeleton: every region we never
 * vary is left byte-for-byte as the designer saved it, and only {@code {{TOKEN}}}
 * placeholders, {@code {{#BLOCK}}…{{/BLOCK}}} regions and repeatable table rows
 * are rewritten.
 *
 * <p>Deliberately raw XML rather than POI's object model. The skeleton carries a
 * grouped cover-page shape with a 4 MB VML fallback and hand-tuned table
 * geometry; round-tripping that through a DOM is both slow and a fidelity risk,
 * and POI's run splitting makes "replace this label" unreliable. Because the
 * skeleton is built by us (see {@code proposal-templates/}), every token is
 * guaranteed to sit inside a single {@code <w:t>}, so plain string replacement
 * is exact.
 */
public final class DocxTemplate {

    /** Parts we template. Everything else in the package is copied through. */
    private static final List<String> TEXT_PARTS =
            List.of("word/document.xml", "docProps/core.xml", "docProps/app.xml");

    private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[#/]?[A-Z_0-9]+\\}\\}");

    private DocxTemplate() { }

    /**
     * @param template the skeleton .docx bytes
     * @param tokens   {@code TOKEN -> value} (no braces); values are XML-escaped
     * @param blocks   {@code BLOCK -> keep?}; a dropped block deletes the whole
     *                 span from the paragraph holding {@code {{#BLOCK}}} through
     *                 the paragraph holding {@code {{/BLOCK}}}
     * @param repeats  {@code PREFIX -> rows}; the single {@code <w:tr>} holding
     *                 tokens with that prefix is cloned once per row
     */
    public static byte[] fill(byte[] template,
                              Map<String, String> tokens,
                              Map<String, Boolean> blocks,
                              Map<String, List<Map<String, String>>> repeats) throws IOException {

        Map<String, byte[]> parts = read(template);

        for (String part : TEXT_PARTS) {
            byte[] raw = parts.get(part);
            if (raw == null) continue;
            String xml = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (repeats != null) {
                for (Map.Entry<String, List<Map<String, String>>> e : repeats.entrySet()) {
                    xml = expandRows(xml, e.getKey(), e.getValue());
                }
            }
            if (blocks != null) {
                for (Map.Entry<String, Boolean> e : blocks.entrySet()) {
                    xml = applyBlock(xml, e.getKey(), Boolean.TRUE.equals(e.getValue()));
                }
            }
            xml = applyTokens(xml, tokens);
            // Anything the caller didn't supply must not reach the client as
            // "{{SOMETHING}}"; blank it instead.
            xml = LEFTOVER.matcher(xml).replaceAll("");
            parts.put(part, xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return write(parts);
    }

    // ── Tokens ───────────────────────────────────────────────────────────────

    private static String applyTokens(String xml, Map<String, String> tokens) {
        if (tokens == null) return xml;
        StringBuilder sb = new StringBuilder(xml);
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            replaceAll(sb, "{{" + e.getKey() + "}}", escape(e.getValue()));
        }
        return sb.toString();
    }

    private static void replaceAll(StringBuilder sb, String find, String replace) {
        int i = sb.indexOf(find);
        while (i >= 0) {
            sb.replace(i, i + find.length(), replace);
            i = sb.indexOf(find, i + replace.length());
        }
    }

    // ── Optional blocks ──────────────────────────────────────────────────────

    /**
     * Keep → strip just the two markers. Drop → delete from the start of the
     * paragraph carrying the opening marker to the end of the one carrying the
     * closing marker, so spacer paragraphs and the section break that ends the
     * page go with it.
     */
    private static String applyBlock(String xml, String name, boolean keep) {
        String open = "{{#" + name + "}}";
        String close = "{{/" + name + "}}";
        int o = xml.indexOf(open);
        int c = xml.indexOf(close);
        if (o < 0 || c < 0) return xml;
        if (keep) {
            return xml.replace(open, "").replace(close, "");
        }
        int from = paragraphStartBefore(xml, o);
        int to = xml.indexOf("</w:p>", c);
        to = (to < 0) ? c + close.length() : to + "</w:p>".length();
        return xml.substring(0, from) + xml.substring(to);
    }

    /** Offset of the {@code <w:p>}/{@code <w:p …>} that encloses {@code pos}. */
    private static int paragraphStartBefore(String xml, int pos) {
        for (int i = pos; i >= 1; i--) {
            if (xml.charAt(i) == '<' && xml.startsWith("<w:p", i)) {
                char after = xml.charAt(i + 4);
                // "<w:p>" or "<w:p " only — never <w:pPr>, <w:pStyle>, <w:pgSz>.
                if (after == '>' || after == ' ') return i;
            }
        }
        return 0;
    }

    // ── Repeatable rows ──────────────────────────────────────────────────────

    /**
     * Clones the table row that carries {@code {{PREFIX_*}}} tokens once per row
     * map. An empty list leaves a single blank row so the table never collapses
     * to a header with nothing under it.
     */
    private static String expandRows(String xml, String prefix, List<Map<String, String>> rows) {
        int marker = xml.indexOf("{{" + prefix + "_");
        if (marker < 0) return xml;
        int start = elementStartBefore(xml, marker, "w:tr");
        if (start < 0) return xml;
        int end = elementEnd(xml, start, "w:tr");
        String rowXml = xml.substring(start, end);

        StringBuilder out = new StringBuilder();
        if (rows == null || rows.isEmpty()) {
            out.append(blankRow(rowXml, prefix));
        } else {
            for (Map<String, String> row : rows) {
                StringBuilder one = new StringBuilder(rowXml);
                for (Map.Entry<String, String> e : row.entrySet()) {
                    replaceAll(one, "{{" + prefix + "_" + e.getKey() + "}}", escape(e.getValue()));
                }
                // Any column this row didn't fill renders empty, not as a token.
                out.append(stripPrefixTokens(one.toString(), prefix));
            }
        }
        return xml.substring(0, start) + out + xml.substring(end);
    }

    private static String blankRow(String rowXml, String prefix) {
        return stripPrefixTokens(rowXml, prefix);
    }

    private static String stripPrefixTokens(String xml, String prefix) {
        return xml.replaceAll("\\{\\{" + Pattern.quote(prefix) + "_[A-Z_0-9]+\\}\\}", "");
    }

    private static int elementStartBefore(String xml, int pos, String name) {
        String open = "<" + name;
        for (int i = pos; i >= 0; i--) {
            if (xml.charAt(i) == '<' && xml.startsWith(open, i)) {
                char after = xml.charAt(i + open.length());
                if (after == '>' || after == ' ') return i;
            }
        }
        return -1;
    }

    /** Offset just past the {@code </name>} that closes the element at {@code start}. */
    private static int elementEnd(String xml, int start, String name) {
        String closeTag = "</" + name + ">";
        Pattern openPat = Pattern.compile("<" + Pattern.quote(name) + "[\\s>]");
        Matcher m = openPat.matcher(xml);
        int depth = 0;
        int i = start;
        while (i < xml.length()) {
            int ci = xml.indexOf(closeTag, i);
            if (ci < 0) return xml.length();
            boolean hasOpen = m.find(i);
            int oi = hasOpen ? m.start() : -1;
            if (hasOpen && oi < ci) {
                int gt = xml.indexOf('>', oi);
                if (xml.charAt(gt - 1) != '/') depth++;
                i = gt + 1;
            } else {
                depth--;
                i = ci + closeTag.length();
                if (depth == 0) return i;
            }
        }
        return xml.length();
    }

    // ── Package I/O ──────────────────────────────────────────────────────────

    private static Map<String, byte[]> read(byte[] docx) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                parts.put(e.getName(), bos.toByteArray());
            }
        }
        return parts;
    }

    private static byte[] write(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(parts.size() * 4096);
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            // [Content_Types].xml first — some readers insist on it.
            List<String> names = new ArrayList<>(parts.keySet());
            names.remove("[Content_Types].xml");
            names.add(0, "[Content_Types].xml");
            for (String name : names) {
                byte[] data = parts.get(name);
                if (data == null) continue;
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(0L); // deterministic output
                zout.putNextEntry(ze);
                zout.write(data);
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                // A newline inside a run needs a real break element.
                case '\n': sb.append("</w:t><w:br/><w:t xml:space=\"preserve\">"); break;
                case '\r': break;
                default:
                    // Control chars are illegal in XML 1.0 and would corrupt the part.
                    if (c < 0x20 && c != '\t') break;
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
