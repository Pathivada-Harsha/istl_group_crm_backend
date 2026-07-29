package com.istlgroup.istl_group_crm_backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Renders a stored .docx as HTML so the sanction letter can be read inside the
 * page instead of downloaded. No browser can display a Word file natively, and
 * pulling in a converter library (xdocreport, docx4j) for this would be a heavy
 * dependency — a sanction letter is only paragraphs and tables, so POI's own
 * model covers it.
 *
 * <p>Output is a fragment, not a whole document: no script, no style, no link,
 * no external references. Everything that goes in is HTML-escaped first, so a
 * malicious .docx can't inject markup into the page that renders it. Formatting
 * is limited on purpose to bold, italic, underline, alignment and headings —
 * enough to read the letter faithfully, nothing that could carry a payload.
 */
@Component
public class SanctionDocHtmlRenderer {

    public String toHtml(byte[] docxBytes) throws IOException {
        StringBuilder html = new StringBuilder(4096);
        html.append("<div class=\"docx-body\">");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {

            // Walk paragraphs and tables in document order. getBodyElements()
            // preserves the interleaving; iterating the two lists separately
            // would move every table to the end of the letter.
            for (Object element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    appendParagraph(html, p);
                } else if (element instanceof XWPFTable t) {
                    appendTable(html, t);
                }
            }
        }

        html.append("</div>");
        return html.toString();
    }

    private void appendParagraph(StringBuilder html, XWPFParagraph p) {
        String text = p.getText();
        if (text == null || text.isBlank()) {
            html.append("<p class=\"docx-spacer\"></p>");
            return;
        }

        String style = p.getStyle() == null ? "" : p.getStyle().toLowerCase();
        String tag = style.startsWith("heading1") || style.equals("title") ? "h2"
                   : style.startsWith("heading") ? "h3"
                   : "p";

        html.append('<').append(tag).append(" class=\"docx-p")
            .append(alignClass(p.getAlignment())).append("\">");

        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) {
            html.append(escape(text));
        } else {
            for (XWPFRun run : runs) {
                appendRun(html, run);
            }
        }
        html.append("</").append(tag).append('>');
    }

    private void appendRun(StringBuilder html, XWPFRun run) {
        String t = run.text();
        if (t == null || t.isEmpty()) return;

        boolean bold = run.isBold();
        boolean italic = run.isItalic();
        boolean underline = run.getUnderline() != null
                && !"NONE".equalsIgnoreCase(run.getUnderline().toString());

        if (bold) html.append("<strong>");
        if (italic) html.append("<em>");
        if (underline) html.append("<u>");

        // Word stores manual line breaks inside the run, not as separate runs.
        html.append(escape(t).replace("\n", "<br/>"));

        if (underline) html.append("</u>");
        if (italic) html.append("</em>");
        if (bold) html.append("</strong>");
    }

    private void appendTable(StringBuilder html, XWPFTable table) {
        html.append("<table class=\"docx-table\"><tbody>");
        boolean firstRow = true;

        for (XWPFTableRow row : table.getRows()) {
            html.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                // The first row of these letters is a header in every sample,
                // but we don't assume it — it's styled, not structurally <th>.
                html.append(firstRow ? "<td class=\"docx-th\">" : "<td>");

                List<XWPFParagraph> paras = cell.getParagraphs();
                if (paras.isEmpty()) {
                    html.append(escape(cell.getText()));
                } else {
                    for (int i = 0; i < paras.size(); i++) {
                        if (i > 0) html.append("<br/>");
                        String cellText = paras.get(i).getText();
                        html.append(escape(cellText == null ? "" : cellText));
                    }
                }
                html.append("</td>");
            }
            html.append("</tr>");
            firstRow = false;
        }
        html.append("</tbody></table>");
    }

    private static String alignClass(ParagraphAlignment a) {
        if (a == null) return "";
        return switch (a) {
            case CENTER -> " docx-center";
            case RIGHT -> " docx-right";
            case BOTH -> " docx-justify";
            default -> "";
        };
    }

    /**
     * Escape everything. The document is untrusted input — it arrived as an
     * upload — so no character from it is allowed to become markup.
     */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}