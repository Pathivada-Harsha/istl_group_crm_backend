package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelReader.FieldCell;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelReader.TableRow;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Field;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Kind;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Parsed;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Raw;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Check;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Evidence;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier.Expect;

/**
 * Turns what a workbook held into a review the user works through — every value
 * normalised, every field marked OK / WARNING / ERROR with the reason, and
 * every value checked against the tender PDF (VERIFIED / UNVERIFIED / MISMATCH).
 *
 * <p>Unlike the PDF path, nothing is thrown away here. The PDF parser drops a
 * value that fails a rule because a guess is worse than a blank; an Excel cell
 * was <em>written</em> by somebody, so a failing value is kept, flagged, and the
 * review refuses to save while it stays in error. The reviewer fixes it or
 * declines it.
 *
 * <p>The rules are the parser's own ({@link TenderFieldValidator#check} and its
 * cross-field checks), applied with Excel-appropriate severity:
 * <ul>
 *   <li>format rules — money, percent, dates, email, financial year — are
 *       <b>errors</b>: a malformed figure must not be saved;</li>
 *   <li>the shape heuristics built to spot PDF prose in a field (authority,
 *       address, tender number) are <b>warnings</b>: "BESCOM" is a real
 *       authority even though it has no organisation noun in it;</li>
 *   <li>dates out of order are an error; EMD outside the provisional 0.5–5% of
 *       the estimate, and a deadline already past, are warnings;</li>
 *   <li>a value the PDF contradicts ({@link TenderExcelVerifier#MISMATCH}) is an
 *       error. One the PDF cannot confirm is not an error, but the review makes
 *       the user confirm it before it is saved.</li>
 * </ul>
 * GSTIN, PAN, CIN and IFSC shapes are checked here as well. The parser has no
 * rule for them (its extractor only ever captures the GSTIN shape), and adding
 * one there would change the PDF pipeline.
 */
public final class TenderExcelImport {

    public static final String OK = "OK";
    public static final String WARNING = "WARNING";
    public static final String ERROR = "ERROR";

    /**
     * One scalar field in the review.
     *
     * @param raw          what the cell held, as a person would read it
     * @param value        the normalised value that would be saved (null when blank or unreadable)
     * @param source       the Source Text written beside it
     * @param verification VERIFIED / UNVERIFIED / MISMATCH (null when there is no value)
     * @param verifyText   the passage as the PDF prints it, when found
     * @param verifyPage   the page it is on
     * @param verifyReason why it is not verified
     */
    public record FieldResult(String field, String label, String sheet, String raw,
                              String value, String status, String reason,
                              String source, String verification, String verifyText,
                              Integer verifyPage, String verifyReason) {
        public FieldResult(String field, String label, String sheet, String raw,
                           String value, String status, String reason) {
            this(field, label, sheet, raw, value, status, reason, null, null, null, null, null);
        }

        FieldResult withStatus(String status, String reason) {
            return new FieldResult(field, label, sheet, raw, value, status, reason,
                    source, verification, verifyText, verifyPage, verifyReason);
        }

        FieldResult withSource(String source) {
            return new FieldResult(field, label, sheet, raw, value, status, reason,
                    source, verification, verifyText, verifyPage, verifyReason);
        }

        FieldResult verified(Check c) {
            if (c == null) return this;
            FieldResult r = new FieldResult(field, label, sheet, raw, value, status, reason,
                    source, c.status(), c.pdfText(), c.page(), c.reason());
            return c.mismatch() ? worse(r, ERROR, "Does not match the PDF — " + c.reason()) : r;
        }
    }

    /**
     * What the review modal receives. {@code kind} tells it this is an Excel import.
     *
     * @param importId  the key the PDF's text is held under for re-checking edits
     * @param pdfStatus "ok" (checked), "scanned" (no text layer), "none" (no PDF)
     *                  or "v1" (an older template with no Source Text column)
     */
    public record Result(String kind, String version, String fileName, String message,
                         List<FieldResult> fields,
                         List<Map<String, Object>> eligibilityCriteria,
                         List<Map<String, Object>> documents,
                         List<Map<String, Object>> boqItems,
                         List<String> warnings,
                         int errorCount, int warningCount,
                         String importId, String pdfStatus, String pdfName, String pdfNote,
                         int verifiedCount, int unverifiedCount, int mismatchCount) {

        public Result withPdf(String importId, String pdfStatus, String pdfName, String pdfNote) {
            return new Result(kind, version, fileName, message, fields, eligibilityCriteria, documents, boqItems,
                    warnings, errorCount, warningCount, importId, pdfStatus, pdfName, pdfNote,
                    verifiedCount, unverifiedCount, mismatchCount);
        }
    }

    /** Input to the rules: scalar cells plus the three tables, however they were obtained. */
    public record Input(List<FieldCell> fields, Map<String, List<TableRow>> tables) {}

    /** Fields whose parser rule is a format rule, so failing it is an error. */
    private static final Set<String> STRICT = Set.of(
            "estimatedValue", "emdAmount", "performanceSecurityPct",
            "submissionDeadline", "technicalOpeningDate", "financialOpeningDate", "emdValidTill",
            "clientContactEmail", "financialYear", "feeAmount");

    /** Identity numbers, stored upper-case without spaces. */
    private static final Set<String> UPPER = Set.of(
            "clientGstin", "clientPan", "clientCin", "emdBeneficiaryIfsc", "feeBeneficiaryIfsc");

    private static final Pattern GSTIN = Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");
    private static final Pattern CIN = Pattern.compile("^[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern URL = Pattern.compile("^(https?://)?[\\w.-]+\\.[a-z]{2,}(/\\S*)?$", Pattern.CASE_INSENSITIVE);

    private static final Evidence NO_PDF = Evidence.unavailable("No PDF was given to check against.");

    private TenderExcelImport() {}

    public static Result evaluate(TenderExcelReader.Workbook wb, TenderExcelOptions opts, String fileName) {
        return evaluate(new Input(wb.fields(), wb.tables()), opts, wb.version(), fileName, wb.warnings(), NO_PDF);
    }

    public static Result evaluate(TenderExcelReader.Workbook wb, TenderExcelOptions opts, String fileName,
                                  Evidence evidence) {
        return evaluate(new Input(wb.fields(), wb.tables()), opts, wb.version(), fileName, wb.warnings(), evidence);
    }

    public static Result evaluate(Input in, TenderExcelOptions opts, String version, String fileName,
                                  List<String> structural) {
        return evaluate(in, opts, version, fileName, structural, NO_PDF);
    }

    public static Result evaluate(Input in, TenderExcelOptions opts, String version, String fileName,
                                  List<String> structural, Evidence evidence) {
        TenderExcelOptions o = opts == null ? TenderExcelOptions.empty() : opts;
        Evidence ev = evidence == null ? NO_PDF : evidence;
        List<FieldResult> fields = verifyFields(fields(in.fields(), o, today()), ev);
        List<Map<String, Object>> elig = eligibility(rows(in, TenderExcelSchema.ELIGIBILITY), o, ev);
        List<Map<String, Object>> docs = documents(rows(in, TenderExcelSchema.DOCUMENTS), ev);
        List<Map<String, Object>> boq = boq(rows(in, TenderExcelSchema.BOQ), ev);

        int errors = 0;
        int warns = 0;
        int[] verdicts = new int[3];
        for (FieldResult f : fields) {
            if (ERROR.equals(f.status())) errors++;
            else if (WARNING.equals(f.status())) warns++;
            tally(verdicts, f.verification());
        }
        for (List<Map<String, Object>> rows : List.of(elig, docs, boq)) {
            for (Map<String, Object> r : rows) {
                if (ERROR.equals(r.get("status"))) errors++;
                else if (WARNING.equals(r.get("status"))) warns++;
                tally(verdicts, (String) r.get("verification"));
            }
        }
        List<String> warnings = structural == null ? List.of() : structural;
        String message = "Read " + fields.size() + " field" + (fields.size() == 1 ? "" : "s")
                + ", " + elig.size() + " eligibility criteri" + (elig.size() == 1 ? "on" : "a")
                + ", " + docs.size() + " document" + (docs.size() == 1 ? "" : "s")
                + " and " + boq.size() + " BOQ row" + (boq.size() == 1 ? "" : "s")
                + (errors > 0 ? " — " + errors + " error" + (errors == 1 ? "" : "s") + " must be fixed or left out before saving."
                              : warns > 0 ? " — check the warnings before saving." : ".");
        return new Result("excel", version, fileName, message, fields, elig, docs, boq,
                warnings, errors, warns, null, null, null, null, verdicts[0], verdicts[1], verdicts[2]);
    }

    private static void tally(int[] counts, String verification) {
        if (TenderExcelVerifier.VERIFIED.equals(verification)) counts[0]++;
        else if (TenderExcelVerifier.UNVERIFIED.equals(verification)) counts[1]++;
        else if (TenderExcelVerifier.MISMATCH.equals(verification)) counts[2]++;
    }

    /** Clock seam for tests. */
    static LocalDate today() {
        return LocalDate.now();
    }

    // ── scalars ──────────────────────────────────────────────────────────────

    static List<FieldResult> fields(List<FieldCell> cells, TenderExcelOptions o, LocalDate today) {
        // Normalise each cell, then fold the field that appears on two sheets.
        Map<String, FieldResult> byKey = new LinkedHashMap<>();
        for (FieldCell cell : cells) {
            Field def = TenderExcelSchema.scalarField(cell.key());
            if (def == null || cell.raw() == null || cell.raw().isBlank()) continue;
            FieldResult r = normalise(cell, def, o);
            FieldResult earlier = byKey.get(cell.key());
            if (earlier == null) {
                byKey.put(cell.key(), r);
            } else if (ERROR.equals(earlier.status())) {
                // an unreadable copy stays visible; the reviewer must sort it out
            } else if (ERROR.equals(r.status()) || earlier.value() == null) {
                byKey.put(cell.key(), r.source() == null ? r.withSource(earlier.source()) : r);
            } else if (r.value() != null && !sameValue(def, r.value(), earlier.value())) {
                byKey.put(cell.key(), earlier.withStatus(ERROR,
                        "\"" + earlier.sheet() + "\" says " + earlier.raw() + " but \"" + r.sheet()
                                + "\" says " + r.raw() + " — they must agree."));
            } else if (earlier.source() == null && r.source() != null) {
                byKey.put(cell.key(), earlier.withSource(r.source()));   // same value; keep whichever copy cites the PDF
            }
        }

        // The parser's rules, over every value that normalised.
        Map<String, String> values = new LinkedHashMap<>();
        byKey.forEach((k, r) -> { if (r.value() != null) values.put(k, r.value()); });
        Map<String, String[]> verdicts = check(values, today);

        List<FieldResult> out = new ArrayList<>();
        for (FieldResult r : byKey.values()) {
            String[] v = verdicts.get(r.field());
            out.add(v == null ? r : worse(r, v[0], v[1]));
        }
        return out;
    }

    private static List<FieldResult> verifyFields(List<FieldResult> fields, Evidence ev) {
        List<FieldResult> out = new ArrayList<>();
        for (FieldResult r : fields) {
            Field def = TenderExcelSchema.scalarField(r.field());
            out.add(def == null ? r : r.verified(TenderExcelVerifier.field(def.kind(), r.value(), r.source(), ev)));
        }
        return out;
    }

    private static FieldResult normalise(FieldCell cell, Field def, TenderExcelOptions o) {
        String shown = cell.raw().text() == null ? "" : cell.raw().text().strip();
        String src = cell.source();
        if (def.kind() == Kind.CHOICE) {
            String written = TenderExcelValues.text(cell.raw()).value();
            if (!o.constrains(def.list())) {
                return new FieldResult(def.key(), def.label(), cell.sheet(), shown, written, OK, null).withSource(src);
            }
            String match = o.match(def.list(), written);
            return (match != null
                    ? new FieldResult(def.key(), def.label(), cell.sheet(), shown, match, OK, null)
                    : new FieldResult(def.key(), def.label(), cell.sheet(), shown, null, WARNING,
                            "\"" + written + "\" is not one of the " + def.label() + " options, so it was left blank."))
                    .withSource(src);
        }
        Parsed p = TenderExcelValues.byKind(def.kind(), cell.raw());
        if (p.failed()) {
            return new FieldResult(def.key(), def.label(), cell.sheet(), shown, null, ERROR, p.error()).withSource(src);
        }
        String value = p.value();
        if (UPPER.contains(def.key())) value = TenderExcelValues.upper(value);
        if ("clientContactEmail".equals(def.key())) value = TenderFieldValidator.stripEmailPrefix(value);
        return new FieldResult(def.key(), def.label(), cell.sheet(), shown, value, OK, null).withSource(src);
    }

    private static boolean sameValue(Field def, String a, String b) {
        if (def.kind() == Kind.MONEY || def.kind() == Kind.PERCENT || def.kind() == Kind.NUMBER) {
            try { return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0; } catch (RuntimeException e) { /* fall through */ }
        }
        return a.equals(b);
    }

    /**
     * The rules over a set of normalised values: field → {status, reason} for
     * every field that is not plainly OK. Also used on its own to re-check values
     * the reviewer edited.
     */
    static Map<String, String[]> check(Map<String, String> values, LocalDate today) {
        Map<String, String[]> out = new LinkedHashMap<>();
        TenderFieldValidator.Context ctx = new TenderFieldValidator.Context(values.get("tenderNumber"), 0, 0);

        Map<String, ExtractedField> asFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String v = e.getValue();
            String shape = shape(key, v);
            if (shape != null) {
                put(out, key, "portalLink".equals(key) ? WARNING : ERROR, shape);
                continue;
            }
            // The fee is not a field the PDF parser knows; its amount is already
            // proven a positive figure by the MONEY parse, and it takes no part
            // in the EMD-against-estimate cross-check.
            if (key.startsWith("fee")) continue;
            ExtractedField f = new ExtractedField(key, "excel", v, 0, v, "excel", true);
            TenderFieldValidator.Verdict verdict = "emdValidTill".equals(key)
                    ? TenderFieldValidator.checkDate(v, ctx)
                    : TenderFieldValidator.check(f, ctx);
            if (!verdict.ok()) {
                put(out, key, STRICT.contains(key) ? ERROR : WARNING, explain(key, verdict.reason(), values));
                if (STRICT.contains(key)) continue;          // a broken value takes no part in cross-checks
            }
            asFields.put(key, f);
        }

        TenderFieldValidator.crossCheck(new LinkedHashMap<>(asFields), (field, why) -> {
            // The cross-checks drop emdAmount only for its share of the estimate,
            // which is a provisional band; everything else they drop is a date
            // out of order.
            if ("emdAmount".equals(field)) put(out, field, WARNING, why + " — usually 0.5–5%; check both figures.");
            else put(out, field, ERROR, dateOrder(field, why));
        });

        String deadline = values.get("submissionDeadline");
        if (deadline != null && !out.containsKey("submissionDeadline")) {
            try {
                if (LocalDate.parse(deadline).isBefore(today)) {
                    put(out, "submissionDeadline", WARNING, "The submission deadline has already passed.");
                }
            } catch (RuntimeException ignored) { /* already reported */ }
        }
        return out;
    }

    /** Identity-number shapes the parser has no rule for. */
    private static String shape(String key, String v) {
        switch (key) {
            case "clientGstin": return GSTIN.matcher(v).matches() ? null : "Not a valid GSTIN (15 characters, e.g. 29ABCDE1234F1Z5).";
            case "clientPan": return PAN.matcher(v).matches() ? null : "Not a valid PAN (10 characters, e.g. ABCDE1234F).";
            case "clientCin": return CIN.matcher(v).matches() ? null : "Not a valid CIN (21 characters).";
            case "emdBeneficiaryIfsc", "feeBeneficiaryIfsc":
                return IFSC.matcher(v).matches() ? null : "Not a valid IFSC (11 characters, e.g. SBIN0001234).";
            case "portalLink": return URL.matcher(v).matches() ? null : "Does not look like a web address.";
            default: return null;
        }
    }

    /** The parser's terse reason, in the reviewer's words. */
    private static String explain(String key, String reason, Map<String, String> values) {
        String r = reason == null ? "" : reason;
        return switch (r) {
            case "not a number" -> "Not a number.";
            case "not positive" -> "Must be more than zero.";
            case "out of range" -> "Must be between 0 and 100.";
            case "not a calendar date" -> "Not a real calendar date.";
            case "not an email" -> "Not an email address.";
            case "not an Indian FY" -> "Use the form 2026-27.";
            case "no organisation token" -> "Does not read like the name of an organisation — check it.";
            case "begins mid-sentence", "spans sentences", "reads as a sentence" ->
                    "Reads like a sentence rather than a name — check it.";
            case "not a postal address" -> "Does not look like a postal address — check it.";
            case "is a date", "only a date", "no non-date component" -> "Looks like a date, not a tender number.";
            case "no digit" -> "Tender numbers normally contain digits — check it.";
            case "implausible length" -> "Unusually short or long — check it.";
            default -> r.startsWith("disagrees with the tender reference")
                    ? "The tender number " + values.get("tenderNumber") + " points to FY "
                      + r.replaceAll(".*\\((.*)\\).*", "$1") + "."
                    : r.isEmpty() ? "Check this value." : Character.toUpperCase(r.charAt(0)) + r.substring(1) + ".";
        };
    }

    /** "falls before submissionDeadline (2026-08-14)" → "Technical Opening Date is before Submission Deadline (2026-08-14)." */
    private static String dateOrder(String field, String why) {
        java.util.regex.Matcher m = Pattern.compile("falls before (\\w+) \\((.*)\\)").matcher(why == null ? "" : why);
        if (!m.find()) return label(field) + " " + why + ".";
        return label(field) + " is before " + label(m.group(1)) + " (" + m.group(2) + ") — dates must run in order.";
    }

    private static String label(String field) {
        Field f = TenderExcelSchema.scalarField(field);
        return f == null ? field : f.label();
    }

    private static void put(Map<String, String[]> out, String key, String status, String reason) {
        String[] prev = out.get(key);
        if (prev == null || rank(status) > rank(prev[0])) out.put(key, new String[] {status, reason});
    }

    private static FieldResult worse(FieldResult r, String status, String reason) {
        if (rank(status) <= rank(r.status())) return r;
        return r.withStatus(status, reason);
    }

    private static int rank(String status) {
        return ERROR.equals(status) ? 2 : WARNING.equals(status) ? 1 : 0;
    }

    // ── tables ───────────────────────────────────────────────────────────────

    private static List<TableRow> rows(Input in, TenderExcelSchema.TableSheet ts) {
        List<TableRow> rows = in.tables() == null ? null : in.tables().get(ts.rowsKey());
        return rows == null ? List.of() : rows;
    }

    /** A row map with its review status: the fields, plus status / issues / excelRow. */
    private static Map<String, Object> row(TableRow tr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("excelRow", tr.excelRow());
        return m;
    }

    /**
     * Checks the row against the PDF, then settles its status. A mismatch is an
     * error like any other: the row cannot be saved until it is fixed.
     */
    private static void finish(Map<String, Object> m, TableRow tr, List<String> errors, List<String> warnings,
                               List<Expect> expects, Evidence ev) {
        String source = cellText(tr, TenderExcelSchema.SOURCE_TEXT_KEY);
        m.put(TenderExcelSchema.SOURCE_TEXT_KEY, source);
        Check c = TenderExcelVerifier.row(source, expects, ev);
        m.put("verification", c.status());
        m.put("verifyText", c.pdfText());
        m.put("verifyPage", c.page());
        m.put("verifyReason", c.reason());
        if (c.mismatch()) errors.add("Does not match the PDF — " + c.reason());

        List<String> issues = new ArrayList<>(errors);
        issues.addAll(warnings);
        m.put("status", !errors.isEmpty() ? ERROR : !warnings.isEmpty() ? WARNING : OK);
        m.put("issues", issues);
    }

    private static String cellText(TableRow tr, String key) {
        String v = TenderExcelValues.text(tr.cells().get(key)).value();
        return v == null ? "" : v;
    }

    static List<Map<String, Object>> eligibility(List<TableRow> rows, TenderExcelOptions o) {
        return eligibility(rows, o, NO_PDF);
    }

    static List<Map<String, Object>> eligibility(List<TableRow> rows, TenderExcelOptions o, Evidence ev) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableRow tr : rows) {
            Map<String, Object> m = row(tr);
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            String category = cellText(tr, "category");
            if (!category.isEmpty() && o.constrains(TenderExcelSchema.ListName.CATEGORY)) {
                String match = o.match(TenderExcelSchema.ListName.CATEGORY, category);
                if (match == null) warnings.add("Category \"" + category + "\" is not an option — left blank (saved as Technical).");
                category = match == null ? "" : match;
            }
            String operator = cellText(tr, "operator");
            if (!operator.isEmpty() && o.constrains(TenderExcelSchema.ListName.OPERATOR)) {
                String match = o.match(TenderExcelSchema.ListName.OPERATOR, operator);
                if (match == null) warnings.add("Operator \"" + operator + "\" is not an option — left blank (suggested from the value).");
                operator = match == null ? "" : match;
            }
            String name = cellText(tr, "criterionName");
            if (name.isEmpty()) errors.add("Criterion is empty.");

            // The threshold as a bare figure, the condition in Operator — however
            // the LLM wrote it ("More than 10 MWp…", "Rs. 100 Cr", "Required").
            RequiredValueCleaner.Cleaned cleaned = RequiredValueCleaner.clean(
                    tr.cells().get("requiredValue"), operator, name, code -> operatorLabel(o, code));
            warnings.addAll(cleaned.warnings());
            String required = cleaned.requiredValue();

            m.put("category", category);
            m.put("criterionName", cleaned.criterionName());
            m.put("requiredValue", required);
            m.put("operator", cleaned.operator());
            m.put("tier", cellText(tr, "tier"));
            m.put("altGroup", cellText(tr, "altGroup"));

            List<Expect> expects = new ArrayList<>();
            if (!required.isEmpty()) {
                expects.add(new Expect("Required Value", isFigure(required) ? Kind.NUMBER : Kind.TEXT, required));
            }
            finish(m, tr, errors, warnings, expects, ev);
            out.add(m);
        }
        return out;
    }

    /** How an operator code reads in the sheet: "gte" → "≥". */
    private static String operatorLabel(TenderExcelOptions o, String code) {
        if (o.operators() != null) {
            for (TenderExcelOptions.Operator op : o.operators()) {
                if (op != null && code.equals(op.value())) return TenderExcelOptions.shortLabel(op);
            }
        }
        return code;
    }

    private static boolean isFigure(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static List<Map<String, Object>> documents(List<TableRow> rows) {
        return documents(rows, NO_PDF);
    }

    static List<Map<String, Object>> documents(List<TableRow> rows, Evidence ev) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableRow tr : rows) {
            Map<String, Object> m = row(tr);
            String name = cellText(tr, "documentName");
            List<String> errors = new ArrayList<>();
            if (name.isEmpty()) errors.add("Document Name is empty.");
            m.put("documentName", name);
            finish(m, tr, errors, new ArrayList<>(), List.of(new Expect("Document Name", Kind.TEXT, name)), ev);
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> boq(List<TableRow> rows) {
        return boq(rows, NO_PDF);
    }

    static List<Map<String, Object>> boq(List<TableRow> rows, Evidence ev) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableRow tr : rows) {
            Map<String, Object> m = row(tr);
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            String description = cellText(tr, "description");
            if (description.isEmpty()) errors.add("Description is empty.");
            Parsed qty = TenderExcelValues.number(tr.cells().get("quantity"));
            String quantity = qty.value();
            if (qty.failed()) {
                errors.add("Quantity " + qty.error() + ".");
                quantity = TenderExcelValues.text(tr.cells().get("quantity")).value();
            } else if (quantity == null) {
                warnings.add("Quantity is blank.");
            }
            m.put("itemNo", cellText(tr, "itemNo"));
            m.put("scope", cellText(tr, "scope"));
            m.put("description", description);
            m.put("unit", cellText(tr, "unit"));
            m.put("quantity", quantity == null ? "" : quantity);
            List<Expect> expects = new ArrayList<>();
            if (!qty.failed() && quantity != null) expects.add(new Expect("Quantity", Kind.NUMBER, quantity));
            expects.add(new Expect("Description", Kind.TEXT, description));
            finish(m, tr, errors, warnings, expects, ev);
            out.add(m);
        }
        return out;
    }

    // ── re-checking edited values ────────────────────────────────────────────

    public static Input fromEdits(Map<String, String> fields, Map<String, List<Map<String, String>>> tables) {
        return fromEdits(fields, null, tables);
    }

    /**
     * The review modal's edits, as an {@link Input}: each value is treated as if
     * it had been typed into the template, so an edited "2.5 Cr" is normalised
     * and checked exactly as an imported one is — against the PDF too, using the
     * Source Text the reviewer may also have corrected.
     */
    public static Input fromEdits(Map<String, String> fields, Map<String, String> fieldSources,
                                  Map<String, List<Map<String, String>>> tables) {
        List<FieldCell> cells = new ArrayList<>();
        if (fields != null) {
            fields.forEach((key, value) -> {
                Field def = TenderExcelSchema.scalarField(key);
                String src = fieldSources == null ? null : fieldSources.get(key);
                if (def != null) cells.add(new FieldCell("Review", def.label(), key, Raw.text(value),
                        src == null || src.isBlank() ? null : src));
            });
        }
        Map<String, List<TableRow>> t = new LinkedHashMap<>();
        if (tables != null) {
            for (TenderExcelSchema.TableSheet ts : TenderExcelSchema.TABLES) {
                List<TableRow> rows = new ArrayList<>();
                List<Map<String, String>> in = tables.get(ts.rowsKey());
                if (in != null) {
                    int n = 0;
                    for (Map<String, String> r : in) {
                        Map<String, Raw> cellsOf = new LinkedHashMap<>();
                        for (Field c : ts.columns()) cellsOf.put(c.key(), Raw.text(r == null ? null : r.get(c.key())));
                        rows.add(new TableRow(++n, cellsOf));
                    }
                }
                t.put(ts.rowsKey(), rows);
            }
        }
        return new Input(cells, t);
    }
}
