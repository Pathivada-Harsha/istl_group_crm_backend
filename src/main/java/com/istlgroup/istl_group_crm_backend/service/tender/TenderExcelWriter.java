package com.istlgroup.istl_group_crm_backend.service.tender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Field;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Kind;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.ListName;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderBoqItemWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderDocumentWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderEligibilityWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

/**
 * Draws the tender template from {@link TenderExcelSchema}: instructions with a
 * ready-to-copy LLM prompt, three Field | Value sheets, three table sheets, and
 * the hidden version stamp.
 *
 * <p>Headers and the Field column are locked (sheet protection with no password,
 * so a person can still lift it); every value cell is open. Fixed-choice cells
 * get a dropdown fed from a very-hidden {@code _lists} sheet, built from the
 * vocabularies the app sent — the same lists its own dropdowns show.
 *
 * <p>Given a tender, the template comes pre-filled with what it already holds,
 * so the LLM only has to fill the gaps.
 */
public final class TenderExcelWriter {

    /** Rows of each table sheet that carry the dropdowns and stay unlocked. */
    static final int TABLE_ROWS = 1000;

    /**
     * The prompt the user pastes into whichever assistant they use. Model-neutral
     * on purpose: it names no product, and every rule is one the import enforces
     * or checks anyway — the prompt only saves a round of corrections.
     */
    static final String PROMPT = String.join("\n",
            "You are filling in a tender data workbook. The tender document (PDF) is attached.",
            "",
            "Fill THIS EXACT workbook from the attached tender PDF and return the filled .xlsx file.",
            "",
            "Rules:",
            "1. Do not rename, add, delete or reorder any sheet, and do not change any header or anything in the "
                    + "\"Field\" column. Write only in the \"Value\" and \"Source Text\" columns and in the rows under "
                    + "the table headers.",
            "2. Write only data into the cells. Do not copy these instructions, the Format hints, notes or "
                    + "explanations into any cell, and do not add comments, summaries or extra rows. The filled file "
                    + "is the whole answer.",
            "3. If a value is not stated in the document, leave the cell blank. Never guess, estimate or infer.",
            "4. Source Text: for every value you fill, copy into the \"Source Text\" cell the exact words of the PDF "
                    + "that value comes from (a short phrase or sentence, word for word, not reworded). Values are "
                    + "checked against the PDF using this text.",
            "5. Money: plain rupees, whole number, no symbols, commas or words "
                    + "(1 Lakh = 100000, 1 Crore = 10000000; \"Rs. 26.90 Crore\" is 269000000).",
            "6. Dates: YYYY-MM-DD. Indian tenders write dates day-first, so 08/07/2026 is 2026-07-08 (8 July).",
            "7. Fixed-choice fields (Client Type, Sector, Tender Type, Source, Fee Refundable, Category, Operator) "
                    + "must use one of the dropdown values exactly. If none fits, leave the cell blank.",
            "8. Percentages: the number only (5 for 5%).",
            "9. Location, District and State are where the WORK is to be done, not the client's office address. "
                    + "Leave them blank if the document does not say.",
            "10. Client Contact Email and Phone: fill them if the document gives them for clarifications or "
                    + "correspondence.",
            "11. Tender fee, processing fee or document fee goes in the \"Tender Fee\" sheet, never in EMD. "
                    + "The EMD is the bid security only.",
            "12. Eligibility: one row per criterion.",
            "    - Required Value is a number only (10, not \"More than 10 MWp\"). The condition goes in Operator "
                    + "(≥, ≤, =, contains); units and context stay in the Criterion text.",
            "    - For a condition that is simply met or not (a certificate, a registration, \"Required\"), leave "
                    + "Required Value blank and set Operator to yes/no.",
            "    - If the tender accepts any ONE of several alternatives, give those rows the same short "
                    + "\"Alternative Group\" label (e.g. A1).",
            "    - If the tender classifies bidders into categories or tiers (e.g. Category A / B / C), put the tier "
                    + "name in the \"Tier\" column of every row that belongs to it. Leave Tier blank for rows every "
                    + "bidder must meet.",
            "13. Documents: one row per document the bidder must submit with the bid.",
            "14. BOQ: one row per item, with quantities exactly as in the schedule.",
            "15. Some cells may already be filled. Keep them unless the document clearly says otherwise.",
            "",
            "Return the same .xlsx file, filled. Do not convert it to CSV, Google Sheets or a table in the chat.");

    private TenderExcelWriter() {}

    public static byte[] write(TenderWrapper prefill, TenderExcelOptions opts) {
        TenderExcelOptions o = opts == null ? TenderExcelOptions.empty() : opts;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);

            instructions(wb, st);
            for (TenderExcelSchema.VerticalSheet vs : TenderExcelSchema.VERTICAL) vertical(wb, st, vs, prefill);
            table(wb, st, TenderExcelSchema.ELIGIBILITY, withOperatorLabels(eligibilityRows(prefill), o));
            table(wb, st, TenderExcelSchema.DOCUMENTS, documentRows(prefill));
            table(wb, st, TenderExcelSchema.BOQ, boqRows(prefill));
            tabColours(wb);

            XSSFSheet meta = wb.createSheet(TenderExcelSchema.META);
            meta.createRow(0).createCell(0).setCellValue("template_version");
            meta.getRow(0).createCell(1).setCellValue(TenderExcelSchema.VERSION);
            meta.enableLocking();
            wb.setSheetVisibility(wb.getSheetIndex(meta), SheetVisibility.HIDDEN);

            lists(wb, o);                                          // must exist before validations resolve
            dropdowns(wb, o);

            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── sheets ───────────────────────────────────────────────────────────────

    private static void instructions(XSSFWorkbook wb, Styles st) {
        XSSFSheet s = wb.createSheet(TenderExcelSchema.INSTRUCTIONS);
        s.setColumnWidth(0, 120 * 256);
        int r = 0;
        cell(s, r++, 0, "Tender data template", st.title);
        r++;
        String[] steps = {
                "1. Open any AI assistant that can read files.",
                "2. Attach this workbook AND the tender PDF.",
                "3. Copy the prompt below (the whole yellow cell) into the chat and send it.",
                "4. Download the .xlsx it returns. Check it — the assistant can still make mistakes.",
                "5. In the CRM choose \"Import from Excel\" and pick BOTH files: the filled .xlsx and the tender PDF. "
                        + "Every value is checked against the PDF; nothing is saved until you review it.",
                "",
                "Do not rename sheets or headers — the import matches them by name and refuses a file it cannot match.",
                "Template version: " + TenderExcelSchema.VERSION,
        };
        for (String step : steps) cell(s, r++, 0, step, st.wrap);
        r++;
        cell(s, r++, 0, "Prompt to copy:", st.header);
        XSSFRow promptRow = s.createRow(r);
        promptRow.setHeightInPoints(Math.min(409, 16 * PROMPT.split("\n").length + 40));
        XSSFCell prompt = promptRow.createCell(0);
        prompt.setCellValue(PROMPT);
        prompt.setCellStyle(st.prompt);
        s.enableLocking();
        s.lockSelectLockedCells(false);
    }

    /** Field | Value | Source Text | Format. */
    private static void vertical(XSSFWorkbook wb, Styles st, TenderExcelSchema.VerticalSheet vs, TenderWrapper t) {
        XSSFSheet s = wb.createSheet(vs.name());
        s.setColumnWidth(0, 30 * 256);
        s.setColumnWidth(1, 50 * 256);
        s.setColumnWidth(2, 50 * 256);
        s.setColumnWidth(3, 40 * 256);
        cell(s, 0, 0, "Field", st.header);
        cell(s, 0, 1, "Value", st.header);
        cell(s, 0, 2, TenderExcelSchema.SOURCE_TEXT_HEADER, st.sourceHeader);
        cell(s, 0, 3, TenderExcelSchema.FORMAT_HEADER, st.header);
        int r = 1;
        for (Field f : vs.fields()) {
            cell(s, r, 0, f.label(), st.label);
            value(s, r, 1, f, t == null ? null : scalar(t, f.key()), st);
            value(s, r, 2, null, null, st);
            cell(s, r, 3, f.format(), st.hint);
            r++;
        }
        s.createFreezePane(0, 1);
        protect(s);
    }

    private static void table(XSSFWorkbook wb, Styles st, TenderExcelSchema.TableSheet ts, List<Map<String, String>> rows) {
        XSSFSheet s = wb.createSheet(ts.name());
        for (int c = 0; c < ts.columns().size(); c++) {
            Field f = ts.columns().get(c);
            boolean source = TenderExcelSchema.SOURCE_TEXT_KEY.equals(f.key());
            cell(s, 0, c, f.label(), source ? st.sourceHeader : st.header);
            s.setColumnWidth(c, (source ? 60 : "description".equals(f.key()) || "criterionName".equals(f.key())
                    || "documentName".equals(f.key()) ? 70 : 20) * 256);
            s.setDefaultColumnStyle(c, st.open);
        }
        int r = 1;
        for (Map<String, String> row : rows) {
            for (int c = 0; c < ts.columns().size(); c++) {
                Field f = ts.columns().get(c);
                value(s, r, c, f, row.get(f.key()), st);
            }
            r++;
        }
        s.createFreezePane(0, 1);
        protect(s);
    }

    /** Locked by default, but rows can still be added, removed and resized. */
    private static void protect(XSSFSheet s) {
        s.enableLocking();
        s.lockInsertRows(false);
        s.lockDeleteRows(false);
        s.lockFormatColumns(false);
        s.lockFormatRows(false);
        s.lockSelectLockedCells(false);
        s.lockSelectUnlockedCells(false);
    }

    /** One value cell: money, percent and quantity as numbers; everything else as text. */
    private static void value(XSSFSheet s, int r, int c, Field f, String v, Styles st) {
        XSSFRow row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        XSSFCell cell = row.createCell(c);
        cell.setCellStyle(st.open);
        if (f == null || TenderValues.isBlank(v)) return;
        if (f.kind() == Kind.MONEY || f.kind() == Kind.PERCENT || f.kind() == Kind.NUMBER) {
            try {
                cell.setCellValue(new BigDecimal(v.strip()).doubleValue());
                cell.setCellStyle(f.kind() == Kind.MONEY ? st.openMoney : st.open);
                return;
            } catch (NumberFormatException ignored) { /* write it as text */ }
        }
        cell.setCellValue(v.strip());
    }

    // ── dropdowns ────────────────────────────────────────────────────────────

    private static String rangeName(ListName l) {
        return "list_" + l.name().toLowerCase();
    }

    private static void lists(XSSFWorkbook wb, TenderExcelOptions o) {
        XSSFSheet s = wb.createSheet(TenderExcelSchema.LISTS);
        int col = 0;
        for (ListName l : ListName.values()) {
            List<String> values = o.shown(l);
            if (values.isEmpty()) continue;
            for (int i = 0; i < values.size(); i++) {
                XSSFRow row = s.getRow(i) == null ? s.createRow(i) : s.getRow(i);
                row.createCell(col).setCellValue(values.get(i));
            }
            Name name = wb.createName();
            name.setNameName(rangeName(l));
            String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(col);
            name.setRefersToFormula("'" + TenderExcelSchema.LISTS + "'!$" + letter + "$1:$" + letter + "$" + values.size());
            col++;
        }
        s.enableLocking();
        wb.setSheetVisibility(wb.getSheetIndex(s), SheetVisibility.VERY_HIDDEN);
    }

    private static void dropdowns(XSSFWorkbook wb, TenderExcelOptions o) {
        for (TenderExcelSchema.VerticalSheet vs : TenderExcelSchema.VERTICAL) {
            XSSFSheet s = wb.getSheet(vs.name());
            for (int i = 0; i < vs.fields().size(); i++) {
                Field f = vs.fields().get(i);
                if (f.kind() == Kind.CHOICE) dropdown(s, o, f, i + 1, i + 1, 1);
            }
        }
        for (TenderExcelSchema.TableSheet ts : TenderExcelSchema.TABLES) {
            XSSFSheet s = wb.getSheet(ts.name());
            for (int c = 0; c < ts.columns().size(); c++) {
                Field f = ts.columns().get(c);
                if (f.kind() == Kind.CHOICE) dropdown(s, o, f, 1, TABLE_ROWS, c);
            }
        }
    }

    private static void dropdown(XSSFSheet s, TenderExcelOptions o, Field f, int firstRow, int lastRow, int col) {
        if (!o.constrains(f.list())) return;
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(s);
        DataValidationConstraint constraint = helper.createFormulaListConstraint(rangeName(f.list()));
        DataValidation dv = helper.createValidation(constraint, new CellRangeAddressList(firstRow, lastRow, col, col));
        dv.setSuppressDropDownArrow(true);                  // XSSF: true means "show the arrow"
        dv.setShowErrorBox(true);
        dv.setErrorStyle(DataValidation.ErrorStyle.STOP);
        dv.createErrorBox("Pick from the list", f.label() + " must be one of the dropdown values. Leave it blank if none fits.");
        s.addValidationData(dv);
    }

    // ── pre-fill ─────────────────────────────────────────────────────────────

    /** A tender's scalar by template key. */
    static String scalar(TenderWrapper t, String key) {
        return switch (key) {
            case "tenderNumber" -> t.getTenderNumber();
            case "tenderName" -> t.getTenderName();
            case "issuingAuthority" -> t.getIssuingAuthority();
            case "clientCompany" -> t.getClientCompany();
            case "clientType" -> t.getClientType();
            case "clientGstin" -> t.getClientGstin();
            case "clientPan" -> t.getClientPan();
            case "clientCin" -> t.getClientCin();
            case "clientContactPerson" -> t.getClientContactPerson();
            case "clientContactEmail" -> t.getClientContactEmail();
            case "clientContactPhone" -> t.getClientContactPhone();
            case "clientAddress" -> t.getClientAddress();
            case "clientCity" -> t.getClientCity();
            case "clientState" -> t.getClientState();
            case "sector" -> t.getSector();
            case "tenderType" -> t.getTenderType();
            case "source" -> t.getSource();
            case "portalLink" -> t.getPortalLink();
            case "location" -> t.getLocation();
            case "district" -> t.getDistrict();
            case "state" -> t.getState();
            case "financialYear" -> t.getFinancialYear();
            case "estimatedValue" -> t.getEstimatedValue();
            case "emdAmount" -> t.getEmdAmount();
            case "performanceSecurityPct" -> t.getPerformanceSecurityPct();
            case "submissionDeadline" -> day(t.getSubmissionDeadline());
            case "technicalOpeningDate" -> day(t.getTechnicalOpeningDate());
            case "financialOpeningDate" -> day(t.getFinancialOpeningDate());
            case "emdValidTill" -> day(t.getEmdValidTill());
            case "emdBeneficiaryName" -> t.getEmdBeneficiaryName();
            case "emdBeneficiaryBank" -> t.getEmdBeneficiaryBank();
            case "emdBeneficiaryAccount" -> t.getEmdBeneficiaryAccount();
            case "emdBeneficiaryIfsc" -> t.getEmdBeneficiaryIfsc();
            case "feeAmount" -> t.getFeeAmount();
            case "feeRefundable" -> t.getFeeRefundable();
            case "feeBeneficiaryName" -> t.getFeeBeneficiaryName();
            case "feeBeneficiaryBank" -> t.getFeeBeneficiaryBank();
            case "feeBeneficiaryAccount" -> t.getFeeBeneficiaryAccount();
            case "feeBeneficiaryIfsc" -> t.getFeeBeneficiaryIfsc();
            default -> null;
        };
    }

    /** A stored date may carry a time ("2026-08-14T00:00"); the template takes the day. */
    private static String day(String v) {
        if (v == null) return null;
        String s = v.strip();
        return s.length() >= 10 && s.charAt(4) == '-' ? s.substring(0, 10) : s;
    }

    private static List<Map<String, String>> eligibilityRows(TenderWrapper t) {
        List<Map<String, String>> out = new ArrayList<>();
        if (t == null || t.getEligibilityCriteria() == null) return out;
        for (TenderEligibilityWrapper e : t.getEligibilityCriteria()) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("category", e.getCategory());
            m.put("criterionName", e.getCriterionName());
            m.put("requiredValue", e.getRequiredValue());
            m.put("operator", e.getOperator());
            m.put("tier", e.getTier());
            m.put("altGroup", e.getAltGroup());
            m.put(TenderExcelSchema.SOURCE_TEXT_KEY, e.getClauseText());   // the clause it was read from, when known
            out.add(m);
        }
        return out;
    }

    /** Eligibility operators are stored as codes; the sheet shows the dropdown's short value. */
    static List<Map<String, String>> withOperatorLabels(List<Map<String, String>> rows, TenderExcelOptions o) {
        for (Map<String, String> m : rows) {
            String code = m.get("operator");
            if (code == null || o.operators() == null) continue;
            for (TenderExcelOptions.Operator op : o.operators()) {
                if (op != null && code.equals(op.value())) m.put("operator", TenderExcelOptions.shortLabel(op));
            }
        }
        return rows;
    }

    private static List<Map<String, String>> documentRows(TenderWrapper t) {
        List<Map<String, String>> out = new ArrayList<>();
        if (t == null || t.getDocuments() == null) return out;
        for (TenderDocumentWrapper d : t.getDocuments()) {
            if (TenderValues.isBlank(d.getDocumentName())) continue;
            out.add(Map.of("documentName", d.getDocumentName()));
        }
        return out;
    }

    private static List<Map<String, String>> boqRows(TenderWrapper t) {
        List<Map<String, String>> out = new ArrayList<>();
        if (t == null || t.getBoqItems() == null) return out;
        for (TenderBoqItemWrapper b : t.getBoqItems()) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("itemNo", b.getItemNo());
            m.put("scope", b.getScope());
            m.put("description", b.getDescription());
            m.put("unit", b.getUnit());
            m.put("quantity", b.getQuantity());
            out.add(m);
        }
        return out;
    }

    // ── cells & styles ───────────────────────────────────────────────────────

    private static void cell(XSSFSheet s, int r, int c, String v, CellStyle style) {
        XSSFRow row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        XSSFCell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    /** Each sheet's tab: navy for the instructions, then one colour per kind of sheet. */
    private static void tabColours(XSSFWorkbook wb) {
        tab(wb, TenderExcelSchema.INSTRUCTIONS, Styles.NAVY);
        for (TenderExcelSchema.VerticalSheet vs : TenderExcelSchema.VERTICAL) tab(wb, vs.name(), Styles.BLUE);
        for (TenderExcelSchema.TableSheet ts : TenderExcelSchema.TABLES) tab(wb, ts.name(), Styles.GREEN);
    }

    private static void tab(XSSFWorkbook wb, String sheet, byte[] rgb) {
        XSSFSheet s = wb.getSheet(sheet);
        if (s != null) s.setTabColor(new XSSFColor(rgb, null));
    }

    /**
     * Header rows navy with white text; the Field column pale blue; the Source
     * Text column amber, so it reads as the column the LLM must not skip; the
     * format hints grey; the prompt pale yellow. Colours are RGB, not the old
     * indexed palette, so they look the same in Excel, LibreOffice and Sheets.
     */
    private static final class Styles {
        static final byte[] NAVY = rgb(0x1F, 0x4E, 0x79);
        static final byte[] BLUE = rgb(0x2E, 0x75, 0xB6);
        static final byte[] GREEN = rgb(0x37, 0x86, 0x4A);
        static final byte[] WHITE = rgb(0xFF, 0xFF, 0xFF);
        static final byte[] PALE_BLUE = rgb(0xDD, 0xEB, 0xF7);
        static final byte[] AMBER = rgb(0xFF, 0xC0, 0x00);
        static final byte[] INK = rgb(0x1F, 0x1F, 0x1F);
        static final byte[] GREY_TEXT = rgb(0x6B, 0x6B, 0x6B);
        static final byte[] GREY_FILL = rgb(0xF2, 0xF2, 0xF2);
        static final byte[] PALE_YELLOW = rgb(0xFF, 0xF2, 0xCC);
        static final byte[] BORDER = rgb(0xBF, 0xBF, 0xBF);

        final XSSFCellStyle title;
        final XSSFCellStyle header;
        final XSSFCellStyle sourceHeader;
        final XSSFCellStyle label;
        final XSSFCellStyle hint;
        final XSSFCellStyle wrap;
        final XSSFCellStyle prompt;
        final XSSFCellStyle open;
        final XSSFCellStyle openMoney;

        Styles(XSSFWorkbook wb) {
            XSSFFont white = wb.createFont();
            white.setBold(true);
            white.setColor(new XSSFColor(WHITE, null));
            XSSFFont ink = wb.createFont();
            ink.setBold(true);
            ink.setColor(new XSSFColor(INK, null));
            XSSFFont navy = wb.createFont();
            navy.setBold(true);
            navy.setColor(new XSSFColor(NAVY, null));
            XSSFFont big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            big.setColor(new XSSFColor(NAVY, null));
            XSSFFont grey = wb.createFont();
            grey.setItalic(true);
            grey.setColor(new XSSFColor(GREY_TEXT, null));

            title = locked(wb);
            title.setFont(big);

            header = filled(wb, NAVY);
            header.setFont(white);
            header.setBorderBottom(BorderStyle.MEDIUM);
            header.setBottomBorderColor(new XSSFColor(NAVY, null));

            sourceHeader = filled(wb, AMBER);
            sourceHeader.setFont(ink);
            sourceHeader.setBorderBottom(BorderStyle.MEDIUM);
            sourceHeader.setBottomBorderColor(new XSSFColor(NAVY, null));

            label = filled(wb, PALE_BLUE);
            label.setFont(navy);
            label.setBorderBottom(BorderStyle.THIN);
            label.setBottomBorderColor(new XSSFColor(BORDER, null));

            hint = filled(wb, GREY_FILL);
            hint.setFont(grey);

            wrap = locked(wb);
            wrap.setWrapText(true);

            prompt = filled(wb, PALE_YELLOW);
            prompt.setWrapText(true);
            prompt.setVerticalAlignment(VerticalAlignment.TOP);

            open = wb.createCellStyle();
            open.setLocked(false);
            open.setWrapText(true);
            open.setVerticalAlignment(VerticalAlignment.TOP);

            openMoney = wb.createCellStyle();
            openMoney.cloneStyleFrom(open);
            openMoney.setDataFormat(wb.createDataFormat().getFormat("0"));
        }

        private static XSSFCellStyle locked(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setLocked(true);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            return s;
        }

        private static XSSFCellStyle filled(XSSFWorkbook wb, byte[] rgb) {
            XSSFCellStyle s = locked(wb);
            s.setFillForegroundColor(new XSSFColor(rgb, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return s;
        }

        private static byte[] rgb(int r, int g, int b) {
            return new byte[] {(byte) r, (byte) g, (byte) b};
        }
    }
}
