package com.istlgroup.istl_group_crm_backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
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
        // Some letters name one entity as both in a single combined row
        // ("Promoter / Sponsor") rather than stating them separately. There
        // is no way to split one company name into two different roles the
        // letter never actually distinguished, so it's read onto the more
        // fundamental of the two fields — promoterName — rather than
        // duplicating it into sponsorName as well, which the letter never
        // separately claimed.
        LABELS.put("promotersponsor",                   "promoterName");
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

        // Project details. Technology is sanction-level; state stays on the
        // borrower row and is filled via resolve — it was already read on the
        // PDF path but had no DOCX table-label entry, so a Word letter's
        // "State" row fell through unmatched.
        LABELS.put("technology",                        "technology");
        // "Module / Technology" packs the module spec and the technology
        // together in one cell (e.g. "Mono PERC bifacial modules... (solar
        // component); 33 x 2.5 MW WTGs (wind component)") — still the best
        // available answer for this field, so it's read the same way.
        LABELS.put("moduletechnology",                  "technology");
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
        LABELS.put("avgdscr",                           "avgDscr");
        LABELS.put("averagedscr",                       "avgDscr");
        LABELS.put("adscr",                             "avgDscr");
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
            "roiPct", "minDscr", "avgDscr", "pledgeOfSharesPct", "plfPct", "tariffPerUnit",
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
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return flattenDocxText(doc);
        }
    }

    /** Paragraphs then table rows, in document order — shared with the covenant text scan. */
    private static String flattenDocxText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
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

            // Pass 1 — exact, labelled rows only (putIfLabelled/putIfInlinePair).
            // These are always more trustworthy than a bare substring hit, so
            // they must run — and win — before pass 2's narrative scan ever
            // gets a chance to plant a wrong value first. Without this split,
            // an earlier, unrelated row that merely mentions "DSRA" in
            // passing (e.g. a Security clause naming the DSRA account) could
            // populate `dsra` before the table walk ever reaches the real,
            // correctly-labelled "Debt Service Reserve Account" row — and
            // putIfLabelled's putIfAbsent would then refuse to overwrite it.
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

            // Pass 2 — narrative/cell-substring fallback (DSRA/ISRA only),
            // run only now that every exactly-labelled row has already had
            // its say. A key pass 1 already found is left untouched —
            // putDsraIsraFromCell's own guard skips it — so this can only
            // ever fill in what pass 1 didn't.
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<?> cells = row.getTableCells();
                    if (cells.isEmpty()) continue;
                    String value = cells.size() >= 2
                            ? row.getCell(cells.size() - 1).getText()
                            : row.getCell(0).getText();
                    putDsraIsraFromCell(out, value);
                }
            }

            // The financial covenants are as often narrative bullets as a table
            // row — e.g. "Cash Sweep Clause: In the event the trailing 12-month
            // DSCR falls below 1.33x, ..." The table walk above only sees cells,
            // so when it found nothing, scan the whole document's text.
            if (!out.containsKey("minDscr") || !out.containsKey("avgDscr")
                    || !out.containsKey("dsra") || !out.containsKey("isra")
                    || !out.containsKey("cashSweep") || !out.containsKey("instrument")
                    || !out.containsKey("tariffPerUnit")) {
                String flat = normaliseWhitespace(flattenDocxText(doc));
                if (!out.containsKey("minDscr"))       put(out, "minDscr", extractMinDscr(flat));
                if (!out.containsKey("avgDscr"))       put(out, "avgDscr", extractAvgDscr(flat));
                if (!out.containsKey("dsra"))          put(out, "dsra", extractDsra(flat));
                if (!out.containsKey("isra"))          put(out, "isra", extractIsra(flat));
                if (!out.containsKey("cashSweep"))     put(out, "cashSweep", extractCashSweep(flat));
                if (!out.containsKey("instrument"))    put(out, "instrument", extractInstrument(flat));
                if (!out.containsKey("tariffPerUnit")) put(out, "tariffPerUnit", extractTariff(flat));
            }

            // Never sits in a table cell — always a full-text scan, unlike the
            // covenant fields above which are only scanned when the table walk
            // came up empty.
            if (!out.containsKey("interestDuringMoratorium")) {
                String flatFull = normaliseWhitespace(flattenDocxText(doc));
                put(out, "interestDuringMoratorium", extractInterestMoratoriumTreatment(flatFull));
            }
        }
        normaliseInstrumentInPlace(out);
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

        String flat = normaliseWhitespace(text);

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
        put(out, "state",      group(flat, "State\\s*[:\\-]?\\s*(.{2,40}?)\\s*(?:Instrument|Product|Security|Co\\s*Obligat)"));
        put(out, "instrument", extractInstrument(flat));

        // Security.
        put(out, "coObligators",      group(flat, "Co[\\s\\-]*Obligat(?:ors?|ers?)\\s*[:\\-]?\\s*(.{2,200}?)\\s*(?:Pledge|Financial\\s+Covenant|Min)"));
        put(out, "pledgeOfSharesPct", group(flat, "Pledge\\s+of\\s+shares?\\s*(?:of\\s+(?:the\\s+)?borrower)?\\s*[:\\-]?\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%"));

        // Financial covenants. DSRA / ISRA / cash sweep capture the phrase, not
        // a number — most letters qualify them and the qualifier is the point.
        put(out, "minDscr",   extractMinDscr(flat));
        put(out, "avgDscr",   extractAvgDscr(flat));
        put(out, "dsra",      extractDsra(flat));
        put(out, "isra",      extractIsra(flat));
        put(out, "cashSweep", extractCashSweep(flat));
        put(out, "interestDuringMoratorium", extractInterestMoratoriumTreatment(flat));

        // Timeline.
        put(out, "disbursementDate",   group(flat, "Disb(?:ursement)?\\.?\\s*Date\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));
        put(out, "repaymentStartDate", group(flat, "Repayment\\s+Start\\s+Date\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));
        put(out, "repaymentEndDate",   group(flat, "Repayment\\s+End\\s+[Dd]ate\\s*[:\\-]?\\s*(" + DATE_ALT + ")"));

        // Base case assumptions.
        put(out, "plfPct",        group(flat, "\\b(?:PLF|CUF)\\b\\s*[:\\-]?\\s*([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%"));
        put(out, "tariffPerUnit", group(flat, "Tariff\\s*[:\\-]?\\s*((?:Rs\\.?|₹)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:/|per\\s*)?(?:kWh|unit)?)"));
        // A tariff figure often shows up inline in an unrelated sentence
        // (Off-taker/PPA terms, tariff escalation clauses, etc.) rather than
        // under its own "Tariff:" label — see extractTariff's own comment.
        if (!out.containsKey("tariffPerUnit")) put(out, "tariffPerUnit", extractTariff(flat));

        // The letterhead is the first line of the document, before flattening.
        String firstLine = text.strip().lines().findFirst().orElse("");
        put(out, "lenderName", stripLenderSuffixNoise(SanctionValueParser.clean(firstLine)));

        normaliseInstrumentInPlace(out);
        deriveRoiFromInterestText(out);
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * A letter that states both the spread and the all-in rate in one
     * sentence ("...spread of 2.32% p.a., reset quarterly; presently working
     * out to 9.88% p.a. floating") must not have the spread mistaken for the
     * rate itself just because it's printed first. A phrase anchoring the
     * all-in figure wins over "first % in the sentence".
     */
    private static final Pattern ROI_ANCHORED = Pattern.compile(
            "(?:presently\\s+working\\s+out\\s+to|effective\\s+rate(?:\\s+of\\s+interest)?|all[\\s-]?in\\s+rate)"
          + "[^0-9%]{0,20}([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROI_ANY = Pattern.compile("([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%");

    /**
     * "Rate of Interest" and "ROI" name the same figure on most letters — one
     * spells out the floating-rate mechanics ("10.35% p.a., linked to..."),
     * the other is the registry sheet's bare percentage. A letter that only
     * prints the narrative phrase still states the all-in rate — usually as
     * its only (or first) number, so that's the fallback once no more
     * specific anchor is found — so when no row was labelled "ROI" outright,
     * take that.
     */
    private static void deriveRoiFromInterestText(Map<String, Object> out) {
        if (out.containsKey("roiPct")) return;
        Object text = out.get("interestRateText");
        if (text == null) return;
        String t = text.toString();
        Matcher anchored = ROI_ANCHORED.matcher(t);
        if (anchored.find()) {
            out.put("roiPct", anchored.group(1) + "%");
            return;
        }
        Matcher any = ROI_ANY.matcher(t);
        if (any.find()) out.put("roiPct", any.group(1) + "%");
    }

    private void putIfLabelled(Map<String, Object> out, String label, String value) {
        String norm = normaliseLabel(label);

        // "Means of Finance" packs debt and equity together in one cell
        // ("Rupee Term Loan (Bank): Rs. 522.24 Crore (68%) Promoter's Equity/
        // Unsecured Loan: Rs. 245.76 Crore (32%) Total: Rs. 768.00 Crore")
        // rather than one row per figure — no single LABELS entry can stand
        // for that, so it gets its own multi-value reader instead.
        if ("meansoffinance".equals(norm)) {
            extractMeansOfFinance(SanctionValueParser.clean(value), out);
            return;
        }

        // Some letters state Tenor and Moratorium as two separate rows
        // instead of one combined sentence — "Tenor: 204 months ... inclusive
        // of moratorium" states no count at all, and the count only appears
        // in this row ("Moratorium: 6 months from the date of first
        // disbursement..."). Store just the month count, not the sentence.
        if (("moratorium".equals(norm) || "moratoriumperiod".equals(norm))
                && !out.containsKey("moratoriumMonths")) {
            String months = extractMoratoriumMonths(value);
            if (months != null) out.put("moratoriumMonths", months);
            return;
        }

        String key = LABELS.get(norm);
        if (key == null) return;
        String v = SanctionValueParser.clean(value);
        if (v == null) return;
        // A pledge row is often a full covenant sentence ("Pledge of 51% of
        // the paid-up equity share capital of the Borrower...") rather than a
        // bare number — this field wants just the percentage out of it.
        if ("pledgeOfSharesPct".equals(key)) {
            String pct = extractLeadingPercent(v);
            if (pct != null) v = pct;
        }
        // A Debt:Equity row is sometimes a full covenant sentence ("Not to
        // exceed 68:32 at any time during the currency of the loan...")
        // rather than a bare ratio -- keep just the "NN:NN" figure itself.
        // The surrounding covenant text isn't a ratio and can run well past
        // the column's length limit.
        if ("debtEquityRatio".equals(key)) {
            String ratio = extractRatio(v);
            if (ratio != null) v = ratio;
        }
        // Don't let a later table overwrite a value an earlier one supplied.
        out.putIfAbsent(key, withUnit(norm, v));
    }

    /**
     * Debt and equity, read out of one combined "Means of Finance" cell
     * rather than their own labelled rows. Each side is found by its own
     * keyword ("Term Loan"/"Debt" for the lender's share, "Equity"/
     * "Promoter" for the sponsor's), so a candidate is only ever accepted
     * next to the label that actually names it — never "first amount, second
     * amount" position, which would silently swap the two on a letter that
     * happens to print them in the other order.
     *
     * <p>Only fills keys still absent, the same precedence every other field
     * in this class follows — a letter that also carries a proper "Debt (Rs.
     * Cr's)" row elsewhere keeps that value untouched.
     */
    private static final Pattern MOF_DEBT = Pattern.compile(
            "(?:Term\\s+Loan|Debt)[^:]{0,40}:\\s*(?:Rs\\.?|₹|INR)?\\s*"
          + "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(Crore|Cr\\.?|Lakhs?|Lacs?)?"
          + "\\s*(?:\\(\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%\\s*\\))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOF_EQUITY = Pattern.compile(
            "(?:Promoter'?s?\\s+Equity|Equity)[^:]{0,60}:\\s*(?:Rs\\.?|₹|INR)?\\s*"
          + "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(Crore|Cr\\.?|Lakhs?|Lacs?)?"
          + "\\s*(?:\\(\\s*([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%\\s*\\))?",
            Pattern.CASE_INSENSITIVE);

    private static void extractMeansOfFinance(String value, Map<String, Object> out) {
        if (value == null) return;
        Matcher debt = MOF_DEBT.matcher(value);
        if (debt.find() && !out.containsKey("debtAmount")) {
            out.put("debtAmount", debt.group(1) + (debt.group(2) != null ? " " + debt.group(2) : ""));
            if (debt.group(3) != null && !out.containsKey("debtPct")) out.put("debtPct", debt.group(3));
        }
        Matcher equity = MOF_EQUITY.matcher(value);
        if (equity.find() && !out.containsKey("equityAmount")) {
            out.put("equityAmount", equity.group(1) + (equity.group(2) != null ? " " + equity.group(2) : ""));
            if (equity.group(3) != null && !out.containsKey("equityPct")) out.put("equityPct", equity.group(3));
        }
    }

    private static final Pattern MORATORIUM_PERIOD =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(months?|years?|yrs?)", Pattern.CASE_INSENSITIVE);

    /** "6 months from the date of first disbursement or COD..." → "6" */
    private static String extractMoratoriumMonths(String cellText) {
        String v = SanctionValueParser.clean(cellText);
        if (v == null) return null;
        Matcher m = MORATORIUM_PERIOD.matcher(v);
        if (!m.find()) return null;
        double n = Double.parseDouble(m.group(1));
        boolean years = m.group(2).toLowerCase(Locale.ENGLISH).startsWith("y");
        return String.valueOf(Math.round(years ? n * 12 : n));
    }

    private static final Pattern FIRST_PERCENT = Pattern.compile("([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%");

    private static String extractLeadingPercent(String text) {
        if (text == null) return null;
        Matcher m = FIRST_PERCENT.matcher(text);
        return m.find() ? m.group(1) + "%" : null;
    }

    private static final Pattern RATIO_ONLY =
            Pattern.compile("^[0-9]{1,3}\\s*:\\s*[0-9]{1,3}$");
    private static final Pattern RATIO_IN_TEXT =
            Pattern.compile("([0-9]{1,3}\\s*:\\s*[0-9]{1,3})");

    /** "Not to exceed 68:32 at any time during the currency of the loan..." → "68:32" */
    private static String extractRatio(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (RATIO_ONLY.matcher(t).matches()) return t;
        Matcher m = RATIO_IN_TEXT.matcher(t);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Some letters bundle the DSRA/ISRA covenant into a cell labelled
     * something else entirely — e.g. a "Debt Service coverage ratio" cell
     * that packs a minimum-DSCR bullet and a "...Debt Service Reserve
     * Account (DSRA) equivalent to the next two quarters'..." bullet
     * together, with no row anywhere labelled just "DSRA". The label
     * dictionary in {@link #putIfLabelled} only catches a cell explicitly
     * labelled "DSRA"/"ISRA", so it misses this. {@link #extractDsra} and
     * {@link #extractIsra} still apply here — but run them against this
     * cell's own text, not the whole document: a cell ends where it ends, so
     * there's no risk of the capture running on into an unrelated covenant
     * two rows down before finding a recognised stop-word, the way it would
     * scanning the full flattened document.
     */
    private void putDsraIsraFromCell(Map<String, Object> out, String cellText) {
        if (out.containsKey("dsra") && out.containsKey("isra")) return;
        String t = SanctionValueParser.clean(cellText);
        if (t == null) return;
        String flat = normaliseWhitespace(t);
        if (!out.containsKey("dsra")) put(out, "dsra", extractDsra(flat));
        if (!out.containsKey("isra")) put(out, "isra", extractIsra(flat));
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
        if ("debtEquityRatio".equals(key)) {
            String ratio = extractRatio(v);
            if (ratio != null) v = ratio;
        }
        out.putIfAbsent(key, withUnit(norm, v));
    }

    /**
     * Carry the unit down from the column header onto the value. Only fires for
     * the headers that spell out "Rs. Cr's", and only when the cell itself is a
     * bare number — a cell that already says "Cr" or "Lakh" is left alone.
     *
     * <p>Mirrors the magnitude escape in {@link SanctionValueParser#parseMoneyCrore}:
     * a bare figure of a lakh or more under a "Rs. Cr's" header was plainly
     * already typed in rupees (some letters are inconsistent about it), so
     * tagging it "Cr" here would make {@code parseMoneyCrore} scale it by
     * crore a second time downstream. Leave it untagged instead and let that
     * parser's own escape read it correctly.
     */
    private static String withUnit(String normalisedLabel, String value) {
        if (!CRORE_LABELS.contains(normalisedLabel)) return value;
        String lower = value.toLowerCase(Locale.ENGLISH);
        if (lower.contains("cr") || lower.contains("lakh") || lower.contains("lac")) return value;
        if (!value.matches("[0-9][0-9,]*(?:\\.[0-9]+)?")) return value;
        BigDecimal num = new BigDecimal(value.replace(",", ""));
        if (num.abs().compareTo(BigDecimal.valueOf(100_000)) >= 0) return value;
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
     * Like {@link #group}, but skips a candidate match whose own gap text —
     * between the anchor and the captured number — is itself qualified by
     * {@code excludeWordInGap}. A raw "DSCR" pattern can land on a row's own
     * label text ("Debt Service Coverage Ratio (DSCR)"), which isn't itself
     * preceded by "average" and so isn't excluded by a lookbehind on the
     * anchor alone — its lazy gap then swallows an unrelated "Average DSCR of
     * X" mention sitting right after the label, before ever reaching a
     * correctly-qualified minimum figure later in the same string. Checking
     * the gap itself catches that; {@code find()} simply resumes from where
     * the rejected match ended, so a later, unqualified candidate still wins.
     */
    private static String groupExcludingGapWord(String text, String regex, String excludeWordInGap) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String gap = text.substring(m.start(), m.start(1));
            if (!gap.toLowerCase(Locale.ENGLISH).contains(excludeWordInGap)) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * Word documents routinely substitute a non-breaking space (U+00A0),
     * narrow no-break space (U+202F) or figure space (U+2007) for an ordinary
     * one -- invisibly, since they render identically -- and Java's {@code \s}
     * does not match any of them without the (slower, globally-applied)
     * {@code UNICODE_CHARACTER_CLASS} flag. A single such character sitting
     * between two words a pattern expects a plain space between is enough to
     * silently fail the whole match, so every regex tier normalises through
     * here before matching rather than trusting {@code \s} alone.
     *
     * <p>Bullet glyphs get the same treatment for the same reason. A letter
     * generated by script (rather than typed by hand in Word) often types a
     * literal bullet character in front of each clause instead of using
     * Word's list-formatting feature, which would keep the glyph out of the
     * text entirely. Left in, that character sits right where a covenant's
     * end-of-sentence terminator (a period, then the next clause's capital
     * letter) expects to find one, and blocks the match outright: "...in
     * order of maturity. [bullet] Debt-Equity..." has no letter immediately
     * after the period-and-space, only the bullet.
     */
    private static String normaliseWhitespace(String text) {
        if (text == null) return "";
        return text
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\u2022\u2023\u25E6\u25AA\u25CF\u25CB\u2043\u2219]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * "DSCR", "Min DSCR" and "Debt Service Coverage Ratio" all name the same
     * covenant, and letters state it either as a clean label ("Min. DSCR: 1.12x")
     * or as prose ("...maintain a Minimum Debt Service Coverage Ratio (DSCR) of
     * 1.20x throughout the tenor of the loan."). The bounded, non-digit gap
     * between the covenant's name and its value tolerates the connector words a
     * sentence adds without wandering off into an unrelated number.
     *
     * <p>Excludes a mention qualified by "average" — some letters state both an
     * average-DSCR covenant and a floor DSCR covenant in one sentence ("...a
     * minimum average DSCR of 1.15x over the loan tenor, and a minimum DSCR of
     * 1.10x in any individual year...") and this field is the floor, not the
     * average; {@link #extractAvgDscr} reads the other one.
     */
    private static String extractMinDscr(String flat) {
        return groupExcludingGapWord(flat,
                "(?<!average\\s)(?:Min(?:imum)?\\.?\\s*)?(?:Debt\\s+Service\\s+Coverage\\s+Ratio|DSCR)"
              + "(?:\\s*\\(\\s*DSCR\\s*\\))?[^0-9]{0,55}?([0-9]+(?:\\.[0-9]+)?)\\s*(?:x\\b|times\\b)?",
                "average");
    }

    /**
     * The average-DSCR covenant, as opposed to the floor {@link #extractMinDscr}
     * reads — requires an explicit "Avg"/"Average" qualifier immediately before
     * the DSCR mention, so it never fires on a letter that only states the
     * minimum.
     */
    private static String extractAvgDscr(String flat) {
        return group(flat,
                "(?:Min(?:imum)?\\.?\\s*)?(?:Avg\\.?|Average)\\s+(?:Debt\\s+Service\\s+Coverage\\s+Ratio|DSCR)"
              + "(?:\\s*\\(\\s*DSCR\\s*\\))?[^0-9]{0,55}?([0-9]+(?:\\.[0-9]+)?)\\s*(?:x\\b|times\\b)?");
    }

    /**
     * A registry-sheet letter prints "DSRA: <phrase> ISRA: <phrase> Cash Sweep:
     * <phrase>" back to back, so the original patterns stopped a value at
     * whichever of those fixed labels came next. A letter written as narrative
     * bullets ("Cash Sweep Clause: ..." followed by an unrelated heading like
     * "Debt-Equity and Leverage:") doesn't offer any of those labels next, so
     * the fixed-anchor list alone leaves the value uncaptured. The added
     * "\\.\\s+[A-Z0-9]" / end-of-text alternative closes a value at its own
     * sentence's full stop when no known label follows — the digit alongside
     * the capital letter covers a numbered section heading right after
     * ("...disbursement. 3. Restricted Payment Conditions"), not just a
     * lettered one.
     */
    private static final String COVENANT_END =
            "\\.\\s+[A-Z0-9]|$";

    private static String extractDsra(String flat) {
        return group(flat,
                "\\bDSRA\\b\\)?\\s*[:\\-]?\\s*(.{2,200}?)\\s*(?:ISRA|Cash\\s+Sweep|Sanction\\s+Date|Time\\s+Lines|"
              + COVENANT_END + ")");
    }

    private static String extractIsra(String flat) {
        return group(flat,
                "\\bISRA\\b\\)?\\s*[:\\-]?\\s*(.{2,200}?)\\s*(?:Cash\\s+Sweep|Sanction\\s+Date|Time\\s+Lines|"
              + COVENANT_END + ")");
    }

    private static String extractCashSweep(String flat) {
        return group(flat,
                "Cash\\s+Sweep(?:\\s+(?:Clause|Mechanism|Mechanic))?\\s*[:\\-]?\\s*(.{2,300}?)\\s*"
              + "(?:Sanction\\s+Date|Disb|Tenor|" + COVENANT_END + ")");
    }

    /**
     * A window of text that mentions both "interest" and "moratorium" near
     * each other, in either order, plus a little more text after — wide
     * enough to catch the clause that actually states the treatment
     * ("...moratorium period shall be capitalized...") without demanding a
     * single rigid word order real letters don't reliably follow.
     */
    private static final Pattern MORATORIUM_INTEREST_WINDOW = Pattern.compile(
            "(?i)(?:interest[^.]{0,150}?moratorium|moratorium[^.]{0,150}?interest)[^.]{0,100}");

    private static final Pattern CAPITALIZED_WORD = Pattern.compile("(?i)capitali[sz]e");
    private static final Pattern SERVICED_WORD = Pattern.compile("(?i)servic|\\bpayable\\b|\\bpaid\\b");

    /**
     * Returns "CAPITALIZED" or "SERVICED" only when a window around a joint
     * "interest"/"moratorium" mention says so in roughly those words; null
     * otherwise — the review screen supplies the labelled default ("Not
     * specified... defaulted to Interest Served") downstream, this method
     * never guesses. Capitalized is checked first within each window: it is
     * the less common, more specific claim.
     *
     * <p>This doesn't attempt to parse negation ("...shall NOT be
     * capitalized") — a starting point, not verified against a real
     * capitalization-clause sanction letter; tune it against actual samples
     * the way extractDsra/extractIsra were, once some are on hand.
     */
    private static String extractInterestMoratoriumTreatment(String flat) {
        Matcher m = MORATORIUM_INTEREST_WINDOW.matcher(flat);
        while (m.find()) {
            String window = m.group();
            if (CAPITALIZED_WORD.matcher(window).find()) return "CAPITALIZED";
            if (SERVICED_WORD.matcher(window).find()) return "SERVICED";
        }
        return null;
    }

    /**
     * Most of these specimen letters never print a labelled "Instrument" row
     * at all — the facility type only ever appears once, in the subject line
     * ("Subject: Sanction of Rupee Term Loan for 75 MWac Solar Power Project").
     * The explicit label is still tried first since it's the more trustworthy
     * source when a letter does carry one; this is a fallback, not a
     * replacement.
     *
     * <p>The similarly-shaped "sanction of a Rupee Term Loan in favour of
     * <borrower> ... for part-financing" in the covering paragraph is not a
     * false-positive risk here: that "for" sits well past the borrower's full
     * name, comfortably outside this pattern's short capture bound, so the
     * subject line's tighter "Instrument for <project>" phrasing matches
     * first regardless of which appears earlier in the text.
     */
    private static String extractInstrument(String flat) {
        String labelled = group(flat,
                "Instrument\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Security|Co\\s*Obligat|Pledge)");
        if (labelled != null) return labelled;
        return group(flat, "Sanction\\s+of\\s+(?:a\\s+|an\\s+)?(.{3,40}?)\\s+for\\s+");
    }

    /**
     * A tariff figure is often stated inline within a row about something
     * else entirely ("Off-taker / PPA: ... power supplied under long-term
     * Power Usage Agreements (PUAs) at a fixed rate of Rs. 3.42/kWh, subject
     * to ...") rather than under its own labelled "Tariff" row — no LABELS
     * entry can catch that, since the row's actual subject is the offtake
     * arrangement, not the tariff. Rather than guess which row it might be
     * hiding in, this scans the whole document for the one unambiguous shape
     * a tariff figure takes (a rupee amount per kWh/unit) and returns just
     * the number — matching what the form field itself stores (see
     * sanctionFields.js: the "₹ / kWh" unit is shown as a fixed suffix beside
     * the box, not typed into it).
     */
    private static final Pattern TARIFF_RATE = Pattern.compile(
            "(?:Rs\\.?|₹|INR)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/|per\\s+)\\s*(?:kWh|unit)\\b",
            Pattern.CASE_INSENSITIVE);

    private static String extractTariff(String flat) {
        Matcher m = TARIFF_RATE.matcher(flat);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The registry's fixed instrument vocabulary. {@code instrument} stays a
     * free-text field on the form — a reviewer can still type anything — but
     * auto-fill only ever writes one of these, never an arbitrary raw phrase,
     * so the same facility isn't recorded three different ways across letters
     * ("Rupee Term Loan Facility", "INR Term Loan", "Term Loan (Rupee)").
     *
     * <p>Checked in this order deliberately: the more specific instruments
     * (FCTL, ECB, NCD, Bridge Loan) are tried before "Term Loan", which
     * is the broadest match ("term loan" alone) and would otherwise swallow a
     * foreign-currency term loan or a bridge loan that also happens to
     * mention "loan".
     */
    private static final LinkedHashMap<String, String[]> INSTRUMENT_CANON = new LinkedHashMap<>();
    static {
        INSTRUMENT_CANON.put("FCTL",           new String[]{"foreign currency term loan", "fctl"});
        INSTRUMENT_CANON.put("ECB",            new String[]{"external commercial borrowing", "ecb"});
        INSTRUMENT_CANON.put("NCD",            new String[]{"non-convertible debenture", "non convertible debenture", "ncd"});
        INSTRUMENT_CANON.put("Bridge Loan",    new String[]{"bridge loan", "bridge facility", "bridge financing"});
        INSTRUMENT_CANON.put("Bank Guarantee", new String[]{"bank guarantee", "bg"});
        INSTRUMENT_CANON.put("LC",             new String[]{"letter of credit", "lc"});
        INSTRUMENT_CANON.put("Cash Credit",    new String[]{"cash credit"});
        INSTRUMENT_CANON.put("Overdraft (OD)", new String[]{"overdraft", "od"});
        INSTRUMENT_CANON.put("Term Loan", new String[]{"rupee term loan", "term loan", "rtl"});
    }

    /**
     * Whole-word match, case-insensitive, tolerant of a trailing plural "s"
     * ("Non-Convertible Debentures" must still match the singular keyword
     * "non-convertible debenture") — but "lc" must not fire inside "clause"
     * or "welcome".
     */
    private static boolean containsWord(String lowerText, String phrase) {
        return Pattern.compile("(?<![a-z])" + Pattern.quote(phrase) + "s?(?![a-z])").matcher(lowerText).find();
    }

    private static String normaliseInstrument(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ENGLISH);
        for (Map.Entry<String, String[]> e : INSTRUMENT_CANON.entrySet()) {
            for (String phrase : e.getValue()) {
                if (containsWord(lower, phrase)) return e.getKey();
            }
        }
        return null;
    }

    /**
     * Snaps whatever landed in "instrument" — from a table cell, an inline
     * pair, or the subject-line fallback, it doesn't matter which — onto the
     * fixed vocabulary above. A raw phrase that matches none of it is dropped
     * rather than kept: an unrecognised value would look auto-filled but
     * wouldn't be one of the registry's known instruments, which is worse
     * than a blank the reviewer knows to fill in by hand.
     */
    private static void normaliseInstrumentInPlace(Map<String, Object> out) {
        Object raw = out.get("instrument");
        String canon = raw == null ? null : normaliseInstrument(raw.toString());
        if (canon != null) out.put("instrument", canon);
        else out.remove("instrument");
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
