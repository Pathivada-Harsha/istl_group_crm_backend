package com.istlgroup.istl_group_crm_backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        // Same "(Rs. Cr's)" suffixed header Debt/Equity already get, below.
        LABELS.put("projectcostrscrs",                  "projectCost");
        LABELS.put("totalprojectcostrscrs",             "projectCost");
        LABELS.put("debtequityratio",                   "debtEquityRatio");
        LABELS.put("debtequity",                        "debtEquityRatio");
        LABELS.put("der",                               "debtEquityRatio");
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

        // ── registry-sheet columns ──
        // Order matters: putIfAbsent means first match wins, so the specific
        // spellings sit above the greedy one-word ones.

        // The registry sheet's "SL Ref. No" is the sanction's own reference
        // number under another name, not a second number of its own.
        LABELS.put("slrefno",                           "refNo");
        LABELS.put("slrefnumber",                       "refNo");

        // Borrower-level. These aren't sanction columns — the review screen
        // routes them to /borrower/resolve so they land on the borrower row.
        LABELS.put("borrowername",                      "borrowerName");
        LABELS.put("promotername",                      "promoterName");
        LABELS.put("nameofthepromoter",                 "promoterName");
        LABELS.put("promoter",                          "promoterName");
        LABELS.put("sponsorname",                       "sponsorName");
        LABELS.put("sponsor",                           "sponsorName");
        LABELS.put("holdingcompany",                    "sponsorName");
        LABELS.put("guarantorname",                     "guarantorName");
        LABELS.put("corporateguarantor",                "guarantorName");
        LABELS.put("guarantor",                         "guarantorName");
        LABELS.put("groupname",                         "groupName");
        LABELS.put("promotergroup",                     "groupName");
        LABELS.put("parentgroup",                       "groupName");
        LABELS.put("cat",                               "borrowerCategory");
        LABELS.put("subcat",                            "borrowerSubCategory");
        LABELS.put("subcategory",                       "borrowerSubCategory");

        // Means of finance. "debtrscrs" is what "Debt (Rs. Cr's)" normalises to.
        LABELS.put("debtrscrs",                         "debtAmount");
        LABELS.put("debtrscr",                          "debtAmount");
        LABELS.put("debtamount",                        "debtAmount");
        LABELS.put("debtpct",                           "debtPct");
        LABELS.put("debtpercentage",                    "debtPct");
        LABELS.put("debtshare",                         "debtPct");
        LABELS.put("debt",                              "debtAmount");
        LABELS.put("equityrscrs",                       "equityAmount");
        LABELS.put("equityrscr",                        "equityAmount");
        LABELS.put("equityamount",                      "equityAmount");
        LABELS.put("equitypct",                         "equityPct");
        LABELS.put("equitypercentage",                  "equityPct");
        // "equity" alone may catch the equity leg of a debt:equity table; the
        // ratio itself is safe, it comes in under the distinct "debtequity" key.
        LABELS.put("equity",                            "equityAmount");

        // Rate build-up.
        LABELS.put("baserate",                          "baseRatePct");
        LABELS.put("mclr",                              "baseRatePct");
        LABELS.put("benchmarkrate",                     "baseRatePct");
        LABELS.put("referencerate",                     "baseRatePct");
        LABELS.put("repolinkedbenchmarkrate",           "baseRatePct");
        LABELS.put("creditspread",                      "spreadPct");
        LABELS.put("spread",                            "spreadPct");
        LABELS.put("markup",                            "spreadPct");
        LABELS.put("effectiveroi",                      "roiPct");
        LABELS.put("roi",                               "roiPct");
        LABELS.put("allinrate",                         "roiPct");
        LABELS.put("allinrateofinterest",               "roiPct");

        // Project details. Village / district / technology are sanction-level;
        // state stays on the borrower row and is filled via resolve — it was
        // already read on the PDF path but had no DOCX table-label entry, so
        // a Word letter's "State" row fell through unmatched.
        LABELS.put("technology",                        "technology");
        LABELS.put("village",                           "village");
        LABELS.put("district",                          "district");
        LABELS.put("dist",                              "district");
        LABELS.put("state",                             "state");
        LABELS.put("projectstate",                      "state");

        // Product.
        LABELS.put("instrument",                        "instrument");
        LABELS.put("producttype",                       "instrument");
        LABELS.put("facilitytype",                      "instrument");
        LABELS.put("natureoffacility",                  "instrument");
        LABELS.put("typeoffacility",                    "instrument");

        // Security.
        LABELS.put("coobligators",                      "coObligators");
        LABELS.put("coobligator",                       "coObligators");
        LABELS.put("coobligors",                        "coObligators");
        LABELS.put("pledgeofshareofborrower",           "pledgeOfSharesPct");
        LABELS.put("pledgeofsharesofborrower",          "pledgeOfSharesPct");
        LABELS.put("pledgeofshares",                    "pledgeOfSharesPct");
        LABELS.put("sharepledge",                       "pledgeOfSharesPct");

        // Financial covenants.
        LABELS.put("mindscr",                           "minDscr");
        LABELS.put("minimumdscr",                       "minDscr");
        LABELS.put("dscr",                              "minDscr");
        LABELS.put("debtservicereserveaccount",         "dsra");
        LABELS.put("dsra",                              "dsra");
        LABELS.put("interestservicereserveaccount",     "isra");
        LABELS.put("isra",                              "isra");
        LABELS.put("cashsweep",                         "cashSweep");
        LABELS.put("cashsweepmechanism",                "cashSweep");
        LABELS.put("cashsweepclause",                   "cashSweep");

        // Timeline.
        LABELS.put("disbdate",                          "disbursementDate");
        LABELS.put("disbursementdate",                  "disbursementDate");
        LABELS.put("dateofdisbursement",                "disbursementDate");
        LABELS.put("firstdisbursementdate",             "disbursementDate");
        LABELS.put("repaymentstartdate",                "repaymentStartDate");
        LABELS.put("repaymentcommencementdate",         "repaymentStartDate");
        LABELS.put("firstrepaymentdate",                "repaymentStartDate");
        LABELS.put("repaymentenddate",                  "repaymentEndDate");
        LABELS.put("lastrepaymentdate",                 "repaymentEndDate");

        // Base case assumptions.
        LABELS.put("plf",                               "plfPct");
        LABELS.put("cuf",                               "plfPct");
        LABELS.put("plantloadfactor",                   "plfPct");
        LABELS.put("capacityutilizationfactor",         "plfPct");
        LABELS.put("levellisedtariff",                  "tariffPerUnit");
        LABELS.put("ppatariff",                         "tariffPerUnit");
        LABELS.put("tariff",                            "tariffPerUnit");
        LABELS.put("energytariff",                      "tariffPerUnit");
        LABELS.put("unitrate",                          "tariffPerUnit");
    }

    /**
     * Keys whose column header already declares crore, so a bare "153.75" in
     * the cell means ₹153.75 Cr and not ₹153.75. The unit is appended at read
     * time; without it the record would be off by a factor of ten million,
     * silently.
     */
    private static final Set<String> CRORE_LABELS = Set.of(
            "debtrscrs", "debtrscr", "equityrscrs", "equityrscr",
            "projectcostrscrs", "totalprojectcostrscrs");

    /**
     * Fields whose value is always a number, date, percentage or ratio. Used
     * only to guard {@link #putIfInlinePair}: a cell like "Debt : Equity
     * Ratio" colon-splits into label "Debt" (→ debtAmount) and remainder
     * "Equity Ratio" — that remainder is not a value, it's the rest of the
     * label, and has no digit in it. {@link #putIfLabelled} doesn't need this
     * guard; the last cell of a multi-column row is unambiguously the value
     * regardless of its content.
     */
    private static final Set<String> NUMERIC_VALUE_KEYS = Set.of(
            "projectCost", "debtAmount", "equityAmount", "debtPct", "equityPct",
            "sanctionedAmount", "debtEquityRatio", "baseRatePct", "spreadPct",
            "roiPct", "minDscr", "pledgeOfSharesPct", "plfPct", "tariffPerUnit",
            "sanctionDate", "disbursementDate", "repaymentStartDate",
            "repaymentEndDate", "scheduledCod");

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
        deriveRoiFromInterestText(out);
        return out;
    }

    // ── PDF: labelled regex over flattened text ─────────────────────────────

    /** A date in any of the shapes the sample letters print. */
    private static final String DATE_ALT =
            "[0-9]{1,2}\\s+[A-Za-z]{3,12}\\s+[0-9]{4}|[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{4}";

    /** An amount with or without its unit. */
    private static final String MONEY =
            "Rs\\.?\\s*[0-9][0-9,\\.]*\\s*(?:Crore|Cr|Lakhs?|Lacs?)?";

    /**
     * Best-effort read of an already-extracted PDF text body.
     *
     * <p>Each pattern stops at the <em>next</em> label it expects, so this tier
     * assumes the letter follows the registry sheet's ordering. That is brittle
     * over flattened PDF text by construction — the DOCX table walker is the
     * reliable path, and the AI fallback exists to rescue what falls through.
     * A pattern that misses simply omits its key; nothing here ever guesses.
     */
    public Map<String, Object> extractFromPdfText(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;

        String flat = text.replaceAll("\\s+", " ").trim();

        // Matches "Ref. No." and the sheet's "SL Ref. No" alike — they name the
        // same number, so no lookbehind is wanted here.
        put(out, "refNo",            group(flat, "Ref\\.?\\s*No\\.?\\s*[:\\-]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{3,60})"));
        put(out, "sanctionDate",     group(flat, "Date\\s*[:\\-]\\s*(" + DATE_ALT + ")"));
        put(out, "borrowerName",     group(flat, "Borrower\\s*[:\\-]?\\s*(.{4,120}?)\\s*(?:Project|Category|Location|Total)"));
        put(out, "projectName",      group(flat, "Project\\s*[:\\-]?\\s*(.{4,160}?)\\s*(?:Category|Location|Total\\s+Project)"));
        put(out, "category",         group(flat, "Category\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Location|Total\\s+Project)"));
        put(out, "location",         group(flat, "Location\\s*[:\\-]?\\s*(.{3,120}?)\\s*(?:Total\\s+Project|Debt)"));
        // "Total" is optional and an "(Rs. Cr's)" header suffix is tolerated,
        // the same way debtAmount/equityAmount below handle their own unit.
        put(out, "projectCost",      group(flat, "(?:Total\\s+)?Project\\s+Cost\\s*(?:\\(Rs\\.?\\s*Cr[^)]*\\))?\\s*[:\\-]?\\s*(" + MONEY + "|[0-9][0-9,\\.]*)"));
        put(out, "debtEquityRatio",  group(flat, "Debt\\s*[:\\-]?\\s*Equity\\s*Ratio\\s*[:\\-]?\\s*([0-9]{1,3}\\s*:\\s*[0-9]{1,3})"));
        put(out, "sanctionedAmount", group(flat, "Sanctioned\\s+(?:Term\\s+Loan\\s+)?Amount\\s*[:\\-]?\\s*(" + MONEY + ")"));
        put(out, "interestRateText", group(flat, "Rate\\s+of\\s+Interest\\s*[:\\-]?\\s*(.{3,120}?)\\s*(?:Tenor|Tenure|Scheduled)"));
        put(out, "tenorText",        group(flat, "Tenor\\s*[:\\-]?\\s*(.{3,160}?)\\s*(?:Scheduled|Repayment|Security)"));
        put(out, "scheduledCod",     group(flat, "Commercial\\s+Operation\\s+Date\\s*(?:\\(COD\\))?\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));

        // ── registry-sheet columns ──
        // Borrower-level; the review screen routes these to /borrower/resolve.
        put(out, "promoterName",  group(flat, "Promoter\\s*(?:Name)?\\s*[:\\-]\\s*(.{3,120}?)\\s*(?:Sponsor|Guarantor|Group|Project)"));
        put(out, "guarantorName", group(flat, "Guarantor\\s*(?:Name)?\\s*[:\\-]\\s*(.{3,120}?)\\s*(?:Group|Project|Debt)"));
        put(out, "groupName",     group(flat, "Group\\s+Name\\s*[:\\-]?\\s*(.{2,120}?)\\s*(?:Project|Debt|Technology)"));

        // Means of finance. The bracketed unit is optional in the label but the
        // capture keeps whatever unit the value itself carried.
        put(out, "debtAmount",   group(flat, "\\bDebt\\s*(?:\\(Rs\\.?\\s*Cr[^)]*\\))?\\s*[:\\-]\\s*(" + MONEY + "|[0-9][0-9,\\.]*)"));
        put(out, "equityAmount", group(flat, "\\bEquity\\s*(?:\\(Rs\\.?\\s*Cr[^)]*\\))?\\s*[:\\-]\\s*(" + MONEY + "|[0-9][0-9,\\.]*)"));
        put(out, "debtPct",      group(flat, "Debt\\s*\\(\\s*%\\s*\\)\\s*[:\\-]?\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%?"));
        put(out, "equityPct",    group(flat, "Equity\\s*\\(\\s*%\\s*\\)\\s*[:\\-]?\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%?"));

        // Rate build-up.
        put(out, "baseRatePct", group(flat, "Base\\s+Rate\\s*[:\\-]?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%"));
        put(out, "spreadPct",   group(flat, "Spread\\s*[:\\-]?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%"));
        put(out, "roiPct",      group(flat, "\\bROI\\b\\s*[:\\-]?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%"));

        // Project details and product.
        put(out, "technology", group(flat, "Technology\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Village|District|State|Instrument)"));
        put(out, "village",    group(flat, "Village\\s*[:\\-]?\\s*(.{2,80}?)\\s*(?:District|Tehsil|Taluka|State)"));
        put(out, "district",   group(flat, "District\\s*[:\\-]?\\s*(.{2,60}?)\\s*(?:State|Pin|Instrument)"));
        put(out, "state",      group(flat, "State\\s*[:\\-]?\\s*(.{2,40}?)\\s*(?:Instrument|Product|Security|Co\\s*Obligat)"));
        put(out, "instrument", group(flat, "Instrument\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Security|Co\\s*Obligat|Pledge)"));

        // Security.
        put(out, "coObligators",      group(flat, "Co[\\s\\-]*Obligat(?:ors?|ers?)\\s*[:\\-]?\\s*(.{2,200}?)\\s*(?:Pledge|Financial\\s+Covenant|Min)"));
        put(out, "pledgeOfSharesPct", group(flat, "Pledge\\s+of\\s+shares?\\s*(?:of\\s+(?:the\\s+)?borrower)?\\s*[:\\-]?\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%"));

        // Financial covenants. DSRA / ISRA / cash sweep capture the phrase, not
        // a number — most letters qualify them and the qualifier is the point.
        put(out, "minDscr",   group(flat, "Min(?:imum)?\\.?\\s*DSCR\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*x?"));
        put(out, "dsra",      group(flat, "\\bDSRA\\b\\s*[:\\-]?\\s*(.{2,120}?)\\s*(?:ISRA|Cash\\s+Sweep|Sanction\\s+Date|Time\\s+Lines)"));
        put(out, "isra",      group(flat, "\\bISRA\\b\\s*[:\\-]?\\s*(.{2,120}?)\\s*(?:Cash\\s+Sweep|Sanction\\s+Date|Time\\s+Lines)"));
        put(out, "cashSweep", group(flat, "Cash\\s+Sweep\\s*[:\\-]?\\s*(.{2,160}?)\\s*(?:Sanction\\s+Date|Disb|Tenor)"));

        // Timeline.
        put(out, "disbursementDate",   group(flat, "Disb(?:ursement)?\\.?\\s*Date\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));
        put(out, "repaymentStartDate", group(flat, "Repayment\\s+Start\\s+Date\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));
        put(out, "repaymentEndDate",   group(flat, "Repayment\\s+End\\s+[Dd]ate\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));

        // Base case assumptions.
        put(out, "plfPct",        group(flat, "\\b(?:PLF|CUF)\\b\\s*[:\\-]?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%"));
        put(out, "tariffPerUnit", group(flat, "Tariff\\s*[:\\-]?\\s*((?:Rs\\.?|₹)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:/|per\\s*)?(?:kWh|unit)?)"));

        // The letterhead is the first line of the document, before flattening.
        String firstLine = text.strip().lines().findFirst().orElse("");
        put(out, "lenderName", stripLenderSuffixNoise(SanctionValueParser.clean(firstLine)));

        deriveRoiFromInterestText(out);
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * "Rate of Interest" and "ROI" name the same figure on most letters — one
     * spells out the floating-rate mechanics ("10.35% p.a., linked to..."),
     * the other is the registry sheet's bare percentage. A letter that only
     * prints the narrative phrase still states the all-in rate as its first
     * number, so when no row was labelled "ROI" outright, take that.
     */
    private static void deriveRoiFromInterestText(Map<String, Object> out) {
        if (out.containsKey("roiPct")) return;
        Object text = out.get("interestRateText");
        if (text == null) return;
        Matcher m = Pattern.compile("([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%").matcher(text.toString());
        if (m.find()) out.put("roiPct", m.group(1) + "%");
    }

    private void putIfLabelled(Map<String, Object> out, String label, String value) {
        String norm = normaliseLabel(label);
        String key = LABELS.get(norm);
        if (key == null) return;
        String v = SanctionValueParser.clean(value);
        if (v == null) return;
        // Don't let a later table overwrite a value an earlier one supplied.
        out.putIfAbsent(key, withUnit(norm, v));
    }

    /** Handle a cell that carries both parts, e.g. "Ref. No.: VIFL/PF/2025/1007". */
    private void putIfInlinePair(Map<String, Object> out, String cellText) {
        String t = SanctionValueParser.clean(cellText);
        if (t == null) return;
        int colon = t.indexOf(':');
        if (colon <= 0 || colon == t.length() - 1) return;

        String label = t.substring(0, colon);
        String value = t.substring(colon + 1);
        String norm = normaliseLabel(label);
        String key = LABELS.get(norm);
        if (key == null) return;

        String v = SanctionValueParser.clean(value);
        if (v == null) return;
        if (NUMERIC_VALUE_KEYS.contains(key) && !v.matches(".*[0-9].*")) return;
        out.putIfAbsent(key, withUnit(norm, v));
    }

    /**
     * Carry the unit down from the column header onto the value. Only fires for
     * the headers that spell out "Rs. Cr's", and only when the cell itself is a
     * bare number — a cell that already says "Cr" or "Lakh" is left alone.
     */
    private static String withUnit(String normalisedLabel, String value) {
        if (!CRORE_LABELS.contains(normalisedLabel)) return value;
        String lower = value.toLowerCase(Locale.ENGLISH);
        if (lower.contains("cr") || lower.contains("lakh") || lower.contains("lac")) return value;
        if (!value.matches("[0-9][0-9,]*(?:\\.[0-9]+)?")) return value;
        return value + " Cr";
    }

    /**
     * Lower-case and drop everything that isn't a letter.
     *
     * <p>"%" is spelled out first, deliberately. Without that step "Debt (%)"
     * and "Debt (Rs. Cr's)" both reduce to something a percentage column and an
     * amount column can't be told apart by — "Debt (%)" would collide with a
     * bare "Debt" row and the two registry-sheet columns would fight over one
     * key. No label in {@link #LABELS} contains a literal "%", so spelling it
     * out changes nothing that already worked.
     */
    private static String normaliseLabel(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ENGLISH)
                .replace("%", "pct")
                .replaceAll("[^a-z]", "");
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
     *
     * <p>Deliberately not widened when the registry-sheet columns were added.
     * Demanding PLF or ISRA here would send almost every real letter to the LLM
     * for values the LLM also cannot find — most sanction letters simply do not
     * print them. A missing column is a blank cell, not a reason to escalate.
     */
    public boolean isSufficient(Map<String, Object> m) {
        return m != null
            && m.containsKey("refNo")
            && m.containsKey("borrowerName")
            && m.containsKey("sanctionedAmount")
            && m.size() >= 6;
    }
}
