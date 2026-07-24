package com.istlgroup.istl_group_crm_backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Deterministic, zero-cost extractor for sanction letters. Runs before the LLM
 * and, on a table-driven letter, makes it unnecessary.
 *
 * <p>Two paths, converging on one field map:
 *
 * <ul>
 *   <li><b>DOCX</b> — walk the document's tables and match column 0 against a
 *       label dictionary, taking the last cell of the row as the value. Because
 *       the table structure survives, this is far more reliable than regex over
 *       flattened text, which is why the sample letters parse without ever
 *       reaching Groq.</li>
 *   <li><b>PDF</b> — PDFBox text, then labelled regexes over a single-space
 *       flattened view, the same approach {@code TenderPdfExtractor} takes.</li>
 * </ul>
 *
 * <p>Every field is guarded independently: a miss omits the key rather than
 * aborting the parse, so a letter with an unusual row still yields everything
 * else.
 */
@Component
public class SanctionDocExtractor {

    /**
     * Label → field. Keys are compared after lower-casing and stripping every
     * character that isn't a letter, so "Debt : Equity Ratio",
     * "Debt-Equity Ratio" and "debtequityratio" all collide onto one entry.
     */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("borrower",                          "borrowerName");
        LABELS.put("nameoftheborrower",                 "borrowerName");
        LABELS.put("project",                           "projectName");
        LABELS.put("nameoftheproject",                  "projectName");
        LABELS.put("category",                          "category");
        LABELS.put("sector",                            "category");
        LABELS.put("location",                          "location");
        LABELS.put("site",                              "location");
        LABELS.put("totalprojectcost",                  "projectCost");
        LABELS.put("projectcost",                       "projectCost");
        LABELS.put("debtequityratio",                   "debtEquityRatio");
        LABELS.put("debtequity",                        "debtEquityRatio");
        LABELS.put("sanctionedtermloanamount",          "sanctionedAmount");
        LABELS.put("sanctionedamount",                  "sanctionedAmount");
        LABELS.put("termloanamount",                    "sanctionedAmount");
        LABELS.put("loanamount",                        "sanctionedAmount");
        LABELS.put("facilityamount",                    "sanctionedAmount");
        LABELS.put("rateofinterest",                    "interestRateText");
        LABELS.put("interestrate",                      "interestRateText");
        LABELS.put("interest",                          "interestRateText");
        LABELS.put("tenor",                             "tenorText");
        LABELS.put("tenure",                            "tenorText");
        LABELS.put("repaymenttenor",                    "tenorText");
        LABELS.put("scheduledcommercialoperationdate",  "scheduledCod");
        LABELS.put("scheduledcommercialoperationdatecod", "scheduledCod");
        LABELS.put("commercialoperationdate",           "scheduledCod");
        LABELS.put("cod",                               "scheduledCod");
        LABELS.put("refno",                             "refNo");
        LABELS.put("referenceno",                       "refNo");
        LABELS.put("sanctionrefno",                     "refNo");
        LABELS.put("date",                              "sanctionDate");
        LABELS.put("sanctiondate",                      "sanctionDate");
        LABELS.put("dateofsanction",                    "sanctionDate");
    }

    // ── entry points ────────────────────────────────────────────────────────

    /** True when the bytes look like a Word document rather than a PDF. */
    public static boolean isDocx(String fileName, String contentType) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ENGLISH);
        String c = contentType == null ? "" : contentType.toLowerCase(Locale.ENGLISH);
        return n.endsWith(".docx") || c.contains("wordprocessingml");
    }

    public static boolean isPdf(String fileName, String contentType) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ENGLISH);
        String c = contentType == null ? "" : contentType.toLowerCase(Locale.ENGLISH);
        return n.endsWith(".pdf") || c.contains("pdf");
    }

    /**
     * Plain text of the document, shared with the AI extractor so a fallback
     * never re-reads the file. For DOCX this flattens paragraphs and table rows
     * in document order.
     */
    public static String loadText(byte[] bytes, boolean docx) throws IOException {
        return docx ? loadDocxText(bytes) : loadPdfText(bytes);
    }

    private static String loadPdfText(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null ? "" : text;
        }
    }

    private static String loadDocxText(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t.trim()).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < row.getTableCells().size(); i++) {
                        if (i > 0) line.append(" : ");
                        line.append(row.getCell(i).getText().trim());
                    }
                    if (!line.toString().isBlank()) sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ── DOCX: table walker ──────────────────────────────────────────────────

    /**
     * Read a Word sanction letter. The lender's name is taken from the first
     * non-empty paragraph — in every sample that is the letterhead — and the
     * rest comes from the tables.
     */
    public Map<String, Object> extractDocx(byte[] bytes) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {

            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = SanctionValueParser.clean(p.getText());
                if (t != null && t.length() > 2) {
                    out.put("lenderName", stripLenderSuffixNoise(t));
                    break;
                }
            }

            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<?> cells = row.getTableCells();
                    if (cells.isEmpty()) continue;

                    String first = row.getCell(0).getText();

                    // Two-or-more column row: label in column 0, value in the last.
                    if (cells.size() >= 2) {
                        String value = row.getCell(cells.size() - 1).getText();
                        putIfLabelled(out, first, value);
                        // A header row often packs both pairs across two cells:
                        // "Ref. No.: VIFL/…" | "Date: 14 March 2025"
                        for (int i = 0; i < cells.size(); i++) {
                            putIfInlinePair(out, row.getCell(i).getText());
                        }
                    } else {
                        // Single cell — the label and value share it, colon-separated.
                        putIfInlinePair(out, first);
                    }
                }
            }
        }
        return out;
    }

    // ── PDF: labelled regex over flattened text ─────────────────────────────

    /** Best-effort read of an already-extracted PDF text body. */
    public Map<String, Object> extractFromPdfText(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;

        String flat = text.replaceAll("\\s+", " ").trim();

        put(out, "refNo",            group(flat, "Ref\\.?\\s*No\\.?\\s*[:\\-]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{3,60})"));
        put(out, "sanctionDate",     group(flat, "Date\\s*[:\\-]\\s*([0-9]{1,2}\\s+[A-Za-z]{3,12}\\s+[0-9]{4}|[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{4})"));
        put(out, "borrowerName",     group(flat, "Borrower\\s*[:\\-]?\\s*(.{4,120}?)\\s*(?:Project|Category|Location|Total)"));
        put(out, "projectName",      group(flat, "Project\\s*[:\\-]?\\s*(.{4,160}?)\\s*(?:Category|Location|Total\\s+Project)"));
        put(out, "category",         group(flat, "Category\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Location|Total\\s+Project)"));
        put(out, "location",         group(flat, "Location\\s*[:\\-]?\\s*(.{3,120}?)\\s*(?:Total\\s+Project|Debt)"));
        put(out, "projectCost",      group(flat, "Total\\s+Project\\s+Cost\\s*[:\\-]?\\s*(Rs\\.?\\s*[0-9][0-9,\\.]*\\s*(?:Crore|Cr|Lakhs?|Lacs?)?)"));
        put(out, "debtEquityRatio",  group(flat, "Debt\\s*[:\\-]?\\s*Equity\\s*Ratio\\s*[:\\-]?\\s*([0-9]{1,3}\\s*:\\s*[0-9]{1,3})"));
        put(out, "sanctionedAmount", group(flat, "Sanctioned\\s+(?:Term\\s+Loan\\s+)?Amount\\s*[:\\-]?\\s*(Rs\\.?\\s*[0-9][0-9,\\.]*\\s*(?:Crore|Cr|Lakhs?|Lacs?)?)"));
        put(out, "interestRateText", group(flat, "Rate\\s+of\\s+Interest\\s*[:\\-]?\\s*(.{3,120}?)\\s*(?:Tenor|Tenure|Scheduled)"));
        put(out, "tenorText",        group(flat, "Tenor\\s*[:\\-]?\\s*(.{3,160}?)\\s*(?:Scheduled|Repayment|Security)"));
        put(out, "scheduledCod",     group(flat, "Commercial\\s+Operation\\s+Date\\s*(?:\\(COD\\))?\\s*[:\\-]?\\s*([0-9]{1,2}\\s+[A-Za-z]{3,12}\\s+[0-9]{4}|[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{4})"));

        // The letterhead is the first line of the document, before flattening.
        String firstLine = text.strip().lines().findFirst().orElse("");
        put(out, "lenderName", stripLenderSuffixNoise(SanctionValueParser.clean(firstLine)));

        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void putIfLabelled(Map<String, Object> out, String label, String value) {
        String key = LABELS.get(normaliseLabel(label));
        if (key == null) return;
        String v = SanctionValueParser.clean(value);
        if (v == null) return;
        // Don't let a later table overwrite a value an earlier one supplied.
        out.putIfAbsent(key, v);
    }

    /** Handle a cell that carries both parts, e.g. "Ref. No.: VIFL/PF/2025/1007". */
    private void putIfInlinePair(Map<String, Object> out, String cellText) {
        String t = SanctionValueParser.clean(cellText);
        if (t == null) return;
        int colon = t.indexOf(':');
        if (colon <= 0 || colon == t.length() - 1) return;

        String label = t.substring(0, colon);
        String value = t.substring(colon + 1);
        String key = LABELS.get(normaliseLabel(label));
        if (key == null) return;

        String v = SanctionValueParser.clean(value);
        if (v != null) out.putIfAbsent(key, v);
    }

    /** Lower-case and drop everything that isn't a letter. */
    private static String normaliseLabel(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z]", "");
    }

    /** Trim a trailing division line off a letterhead. */
    private static String stripLenderSuffixNoise(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() > 120) t = t.substring(0, 120).trim();
        return t;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        String v = SanctionValueParser.clean(value);
        if (v != null) out.put(key, v);
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Enough to skip the LLM. The ref no. plus the borrower and the money are
     * the fields a record can't be built without; if all of those are present
     * the parse is trustworthy and spending tokens would add nothing.
     */
    public boolean isSufficient(Map<String, Object> m) {
        return m != null
            && m.containsKey("refNo")
            && m.containsKey("borrowerName")
            && m.containsKey("sanctionedAmount")
            && m.size() >= 6;
    }
}
