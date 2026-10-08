package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.List;
import java.util.Locale;

/**
 * The one definition of the tender Excel template: which sheets, in which
 * order, which fields and columns each holds, and what kind of value each one
 * takes. {@link TenderExcelWriter} draws the workbook from it and
 * {@link TenderExcelReader} reads it back by it, which is what makes a template
 * that is downloaded and re-imported untouched come back as the same data.
 *
 * <p>Only what a tender <em>document</em> states is here. EMD payment, approvals,
 * go/no-go, submission and result are the bidder's own records and have no place
 * in a file an LLM fills from the NIT.
 *
 * <p>Sheets and fields are matched by {@link #norm name}, never by position —
 * an LLM that reorders rows or changes "Tender Number" to "tender  number" has
 * not changed what the cell means.
 */
public final class TenderExcelSchema {

    /**
     * The version this app writes. Bump when a sheet, field or column changes
     * meaning, and keep reading the old one in {@link #ACCEPTED} for as long as
     * filled copies of it may still be around.
     */
    public static final String VERSION = "TENDER-XLSX-2";

    /**
     * The first template: no Source Text or Tier column and no Tender Fee sheet.
     * Its files still import; with no source text, every value comes back
     * unverified.
     */
    public static final String V1 = "TENDER-XLSX-1";

    public static final List<String> ACCEPTED = List.of(V1, VERSION);

    public enum Kind { TEXT, MONEY, DATE, PERCENT, NUMBER, CHOICE }

    /**
     * The fixed vocabularies. Their values come from the app with each request,
     * except {@code YES_NO}, which is not business vocabulary and is owned here.
     */
    public enum ListName { CLIENT_TYPE, SECTOR, TENDER_TYPE, SOURCE, CATEGORY, OPERATOR, YES_NO }

    /** One row of a vertical (Field | Value) sheet, or one column of a table sheet. */
    public record Field(String label, String key, Kind kind, ListName list, String format) {
        static Field text(String label, String key) { return new Field(label, key, Kind.TEXT, null, "Text"); }
        static Field of(String label, String key, Kind kind, String format) { return new Field(label, key, kind, null, format); }
        static Field choice(String label, String key, ListName list) {
            return new Field(label, key, Kind.CHOICE, list, "Pick from the dropdown");
        }
    }

    public record VerticalSheet(String name, List<Field> fields) {}

    /**
     * @param rowsKey  the tender's child collection this sheet fills
     * @param primary  the column a row cannot do without; a sheet missing it is refused
     */
    public record TableSheet(String name, String rowsKey, String primary, List<Field> columns) {}

    public static final String INSTRUCTIONS = "Instructions";
    public static final String META = "_meta";
    /** Very-hidden sheet holding the dropdown sources. Not part of the contract. */
    public static final String LISTS = "_lists";
    /** Vertical sheets carry a locked column of format hints for the LLM. */
    public static final String FORMAT_HEADER = "Format";
    /**
     * The evidence column: the exact words of the PDF a value was read from.
     * Not plain "Source" — Basic Info already has a field called Source (the
     * portal the tender came from), and the two must not be confused.
     */
    public static final String SOURCE_TEXT_HEADER = "Source Text";
    public static final String SOURCE_TEXT_KEY = "sourceText";

    private static final String MONEY_FMT = "Plain rupees, whole number, no symbols or commas";
    private static final String DATE_FMT = "YYYY-MM-DD";

    public static final VerticalSheet BASIC_INFO = new VerticalSheet("Basic Info", List.of(
            Field.text("Tender Number", "tenderNumber"),
            Field.text("Tender Name", "tenderName"),
            Field.text("Issuing Authority", "issuingAuthority"),
            Field.text("Client Company", "clientCompany"),
            Field.choice("Client Type", "clientType", ListName.CLIENT_TYPE),
            Field.of("Client GSTIN", "clientGstin", Kind.TEXT, "15 characters, e.g. 29ABCDE1234F1Z5"),
            Field.of("Client PAN", "clientPan", Kind.TEXT, "10 characters, e.g. ABCDE1234F"),
            Field.of("Client CIN", "clientCin", Kind.TEXT, "21 characters"),
            Field.text("Client Contact Person", "clientContactPerson"),
            Field.of("Client Contact Email", "clientContactEmail", Kind.TEXT, "Email address"),
            Field.text("Client Contact Phone", "clientContactPhone"),
            Field.text("Client Address", "clientAddress"),
            Field.text("Client City", "clientCity"),
            Field.text("Client State", "clientState"),
            Field.choice("Sector", "sector", ListName.SECTOR),
            Field.choice("Tender Type", "tenderType", ListName.TENDER_TYPE),
            Field.choice("Source", "source", ListName.SOURCE),
            Field.of("Portal Link", "portalLink", Kind.TEXT, "Full URL"),
            Field.text("Location", "location"),
            Field.text("District", "district"),
            Field.text("State", "state"),
            Field.of("Financial Year", "financialYear", Kind.TEXT, "e.g. 2026-27")));

    public static final VerticalSheet DATES_MONEY = new VerticalSheet("Dates & Money", List.of(
            Field.of("Estimated Value", "estimatedValue", Kind.MONEY, MONEY_FMT),
            Field.of("EMD Amount", "emdAmount", Kind.MONEY, MONEY_FMT),
            Field.of("Performance Security %", "performanceSecurityPct", Kind.PERCENT, "Number only, e.g. 5"),
            Field.of("Submission Deadline", "submissionDeadline", Kind.DATE, DATE_FMT),
            Field.of("Technical Opening Date", "technicalOpeningDate", Kind.DATE, DATE_FMT),
            Field.of("Financial Opening Date", "financialOpeningDate", Kind.DATE, DATE_FMT)));

    /** EMD Amount appears here too, on purpose: it is what the EMD instrument must be for. */
    public static final VerticalSheet EMD = new VerticalSheet("EMD Requirements", List.of(
            Field.of("EMD Amount", "emdAmount", Kind.MONEY, MONEY_FMT),
            Field.of("EMD Valid Till", "emdValidTill", Kind.DATE, DATE_FMT),
            Field.text("Beneficiary Name", "emdBeneficiaryName"),
            Field.text("Beneficiary Bank", "emdBeneficiaryBank"),
            Field.text("Beneficiary Account No", "emdBeneficiaryAccount"),
            Field.of("Beneficiary IFSC", "emdBeneficiaryIfsc", Kind.TEXT, "11 characters, e.g. SBIN0001234")));

    /** The tender / processing fee. Separate from the EMD: a tender may ask for either, both or neither. */
    public static final VerticalSheet FEE = new VerticalSheet("Tender Fee", List.of(
            Field.of("Fee Amount", "feeAmount", Kind.MONEY, MONEY_FMT),
            Field.choice("Fee Refundable", "feeRefundable", ListName.YES_NO),
            Field.text("Fee Beneficiary Name", "feeBeneficiaryName"),
            Field.text("Fee Beneficiary Bank", "feeBeneficiaryBank"),
            Field.text("Fee Beneficiary Account No", "feeBeneficiaryAccount"),
            Field.of("Fee Beneficiary IFSC", "feeBeneficiaryIfsc", Kind.TEXT, "11 characters, e.g. SBIN0001234")));

    private static final Field SOURCE_TEXT = Field.of(SOURCE_TEXT_HEADER, SOURCE_TEXT_KEY, Kind.TEXT,
            "Exact words copied from the PDF");

    public static final TableSheet ELIGIBILITY = new TableSheet("Eligibility", "eligibilityCriteria", "criterionName", List.of(
            Field.choice("Category", "category", ListName.CATEGORY),
            Field.text("Criterion", "criterionName"),
            Field.text("Required Value", "requiredValue"),
            Field.choice("Operator", "operator", ListName.OPERATOR),
            Field.text("Tier", "tier"),
            Field.text("Alternative Group", "altGroup"),
            SOURCE_TEXT));

    public static final TableSheet DOCUMENTS = new TableSheet("Documents", "documents", "documentName", List.of(
            Field.text("Document Name", "documentName"),
            SOURCE_TEXT));

    public static final TableSheet BOQ = new TableSheet("BOQ", "boqItems", "description", List.of(
            Field.text("Item No", "itemNo"),
            Field.text("Scope/Section", "scope"),
            Field.text("Description", "description"),
            Field.text("Unit", "unit"),
            Field.of("Quantity", "quantity", Kind.NUMBER, "Number"),
            SOURCE_TEXT));

    public static final List<VerticalSheet> VERTICAL = List.of(BASIC_INFO, DATES_MONEY, EMD, FEE);
    public static final List<TableSheet> TABLES = List.of(ELIGIBILITY, DOCUMENTS, BOQ);

    /** Table columns that the first template did not have. */
    private static final java.util.Set<String> V2_COLUMNS = java.util.Set.of("tier", SOURCE_TEXT_KEY);

    /** The vertical sheets a file of this version carries. */
    public static List<VerticalSheet> vertical(String version) {
        return V1.equals(version) ? List.of(BASIC_INFO, DATES_MONEY, EMD) : VERTICAL;
    }

    /** The columns a table sheet of this version carries. */
    public static List<Field> columns(TableSheet sheet, String version) {
        if (!V1.equals(version)) return sheet.columns();
        return sheet.columns().stream().filter(f -> !V2_COLUMNS.contains(f.key())).toList();
    }

    /** Whether a file of this version has a Source Text column at all. */
    public static boolean hasSourceText(String version) {
        return !V1.equals(version);
    }

    /** Every data sheet a file of this version must carry, in template order. */
    public static List<String> requiredSheets(String version) {
        List<String> out = new java.util.ArrayList<>();
        for (VerticalSheet v : vertical(version)) out.add(v.name());
        for (TableSheet t : TABLES) out.add(t.name());
        return out;
    }

    /** Every data sheet the current template carries, in template order. */
    public static List<String> requiredSheets() {
        return requiredSheets(VERSION);
    }

    /** The scalar field for a tender key, from whichever sheet holds it first. */
    public static Field scalarField(String key) {
        for (VerticalSheet s : VERTICAL) {
            for (Field f : s.fields()) if (f.key().equals(key)) return f;
        }
        return null;
    }

    /** The column definition for a child-row key. */
    public static Field column(TableSheet sheet, String key) {
        for (Field f : sheet.columns()) if (f.key().equals(key)) return f;
        return null;
    }

    /** How names are compared: case-folded, trimmed, inner whitespace collapsed. */
    public static String norm(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private TenderExcelSchema() {}
}
