package com.istlgroup.istl_group_crm_backend.service.tender;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Raw;

/**
 * Reads a filled tender template back into raw cells, refusing a file whose
 * structure cannot be trusted and warning about anything it ignores.
 *
 * <p>Refused outright: not an .xlsx, too large, carries macros, no or an
 * unknown template version, a required sheet missing. Those are not
 * "partly right" files — importing what is left of them would silently drop a
 * whole section.
 *
 * <p>Tolerated, with a warning: extra sheets, unknown fields, extra columns,
 * a listed field that is missing. Everything is matched by
 * {@link TenderExcelSchema#norm name}, never by position.
 *
 * <p>Values are <em>not</em> normalised here; this class only says what each
 * cell held. {@link TenderExcelImport} does the rest.
 */
public final class TenderExcelReader {

    /** A filled template is a few hundred KB at most; this is generous. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    /** A file the import will not touch, with the reason in the user's words. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }

    /**
     * One scalar cell: which sheet and label it was under, what it held, and
     * the Source Text written beside it (null in a file that has no such column).
     */
    public record FieldCell(String sheet, String label, String key, Raw raw, String source) {
        public FieldCell(String sheet, String label, String key, Raw raw) {
            this(sheet, label, key, raw, null);
        }
    }

    /** One table row: column key → cell, plus the 1-based Excel row it came from. */
    public record TableRow(int excelRow, Map<String, Raw> cells) {}

    public record Workbook(String version,
                           List<FieldCell> fields,
                           Map<String, List<TableRow>> tables,
                           List<String> warnings) {}

    private static final DataFormatter FORMATTER = new DataFormatter();

    private TenderExcelReader() {}

    public static Workbook read(byte[] bytes, String fileName) {
        checkFile(bytes, fileName);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return read(wb);
        } catch (Rejected e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Rejected("This file could not be opened as an Excel workbook (.xlsx).");
        }
    }

    // ── the file itself ──────────────────────────────────────────────────────

    static void checkFile(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) throw new Rejected("The file is empty.");
        String name = fileName == null ? "" : fileName.strip().toLowerCase();
        if (!name.endsWith(".xlsx")) {
            throw new Rejected(name.endsWith(".xlsm")
                    ? "Macro-enabled workbooks (.xlsm) are not accepted. Save the file as .xlsx."
                    : "Only .xlsx files are accepted.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new Rejected("The file is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB.");
        }
        // Every .xlsx is a ZIP; anything else renamed to .xlsx stops here.
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new Rejected("This is not a valid .xlsx file.");
        }
        if (hasMacros(bytes)) {
            throw new Rejected("This workbook contains macros, which are not accepted. "
                    + "Save it as a plain .xlsx (Excel Workbook) and import again.");
        }
    }

    /** A VBA project part, or a content type declaring the workbook macro-enabled. */
    static boolean hasMacros(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (n.endsWith("vbaproject.bin") || n.endsWith("vbadata.xml")) return true;
                if (n.equals("[content_types].xml")) {
                    String types = new String(zip.readNBytes(1_000_000), StandardCharsets.UTF_8).toLowerCase();
                    if (types.contains("macroenabled") || types.contains("vbaproject")) return true;
                }
            }
        } catch (IOException e) {
            throw new Rejected("This is not a valid .xlsx file.");
        }
        return false;
    }

    // ── structure ────────────────────────────────────────────────────────────

    static Workbook read(XSSFWorkbook wb) {
        Map<String, Sheet> byName = new LinkedHashMap<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            byName.putIfAbsent(TenderExcelSchema.norm(s.getSheetName()), s);
        }

        String version = version(byName.get(TenderExcelSchema.norm(TenderExcelSchema.META)));
        if (version == null) {
            throw new Rejected("This is not a tender template: the hidden \"_meta\" sheet with the template "
                    + "version is missing. Download a fresh template and fill that.");
        }
        if (!TenderExcelSchema.ACCEPTED.contains(version)) {
            throw new Rejected("Unknown template version \"" + version + "\" (this app reads "
                    + String.join(" and ", TenderExcelSchema.ACCEPTED) + "). Download a fresh template.");
        }

        List<String> missing = new ArrayList<>();
        for (String required : TenderExcelSchema.requiredSheets(version)) {
            if (!byName.containsKey(TenderExcelSchema.norm(required))) missing.add(required);
        }
        if (!missing.isEmpty()) {
            throw new Rejected((missing.size() == 1 ? "Sheet \"" + missing.get(0) + "\" is missing"
                    : "Sheets " + quoteAll(missing) + " are missing")
                    + " — was it renamed or deleted? Sheet names must stay exactly as in the template.");
        }

        List<String> warnings = new ArrayList<>();
        List<String> known = new ArrayList<>(TenderExcelSchema.requiredSheets(version));
        known.add(TenderExcelSchema.INSTRUCTIONS);
        known.add(TenderExcelSchema.META);
        known.add(TenderExcelSchema.LISTS);
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = wb.getSheetName(i);
            if (known.stream().noneMatch(k -> TenderExcelSchema.norm(k).equals(TenderExcelSchema.norm(name)))) {
                warnings.add("Extra sheet \"" + name + "\" was ignored.");
            }
        }

        List<FieldCell> fields = new ArrayList<>();
        for (TenderExcelSchema.VerticalSheet vs : TenderExcelSchema.vertical(version)) {
            fields.addAll(readVertical(byName.get(TenderExcelSchema.norm(vs.name())), vs, version, warnings));
        }
        Map<String, List<TableRow>> tables = new LinkedHashMap<>();
        for (TenderExcelSchema.TableSheet ts : TenderExcelSchema.TABLES) {
            tables.put(ts.rowsKey(), readTable(byName.get(TenderExcelSchema.norm(ts.name())), ts, version, warnings));
        }
        return new Workbook(version, fields, tables, warnings);
    }

    /** The version string anywhere on the meta sheet. */
    private static String version(Sheet meta) {
        if (meta == null) return null;
        String fallback = null;
        for (Row row : meta) {
            for (Cell c : row) {
                String v = FORMATTER.formatCellValue(c).strip();
                if (v.startsWith("TENDER-XLSX")) return v;
                if (fallback == null && TenderExcelSchema.norm(v).replace('_', ' ').equals("template version")) {
                    Cell next = row.getCell(c.getColumnIndex() + 1);
                    String nv = next == null ? "" : FORMATTER.formatCellValue(next).strip();
                    if (!nv.isEmpty()) fallback = nv;
                }
            }
        }
        return fallback;
    }

    // ── vertical sheets: Field | Value [| Source Text] [| Format] ────────────

    private static List<FieldCell> readVertical(Sheet sheet, TenderExcelSchema.VerticalSheet vs, String version,
                                                List<String> warnings) {
        Map<String, TenderExcelSchema.Field> byLabel = new HashMap<>();
        for (TenderExcelSchema.Field f : vs.fields()) byLabel.put(TenderExcelSchema.norm(f.label()), f);

        // Where Source Text and Format sit: from the header row when there is
        // one, else where this version of the template put them.
        boolean hasSource = TenderExcelSchema.hasSourceText(version);
        int sourceCol = hasSource ? 2 : -1;
        int formatCol = hasSource ? 3 : 2;
        for (Row row : sheet) {
            if (!TenderExcelSchema.norm(text(row.getCell(0))).equals("field")) continue;
            for (Cell c : row) {
                String h = TenderExcelSchema.norm(text(c));
                if (h.equals(TenderExcelSchema.norm(TenderExcelSchema.SOURCE_TEXT_HEADER))) sourceCol = c.getColumnIndex();
                if (h.equals(TenderExcelSchema.norm(TenderExcelSchema.FORMAT_HEADER))) formatCol = c.getColumnIndex();
            }
            break;
        }

        List<FieldCell> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        boolean extraColumn = false;
        for (Row row : sheet) {
            String label = TenderExcelSchema.norm(text(row.getCell(0)));
            if (label.isEmpty()) continue;
            if (label.equals("field")) {                         // the header row
                extraColumn |= hasContentOutside(row, sourceCol, formatCol);
                continue;
            }
            TenderExcelSchema.Field f = byLabel.get(label);
            if (f == null) {
                warnings.add(vs.name() + ": unknown field \"" + text(row.getCell(0)).strip() + "\" was ignored.");
                continue;
            }
            if (seen.contains(label)) {
                warnings.add(vs.name() + ": \"" + f.label() + "\" appears twice; the first one was used.");
                continue;
            }
            seen.add(label);
            String source = sourceCol < 0 ? null : text(row.getCell(sourceCol)).strip();
            out.add(new FieldCell(vs.name(), f.label(), f.key(), raw(row.getCell(1)),
                    source == null || source.isEmpty() ? null : source));
            extraColumn |= hasContentOutside(row, sourceCol, formatCol);
        }
        if (extraColumn) {
            warnings.add(vs.name() + ": extra columns were ignored — only the Value"
                    + (hasSource ? " and Source Text columns are" : " column is") + " read.");
        }
        for (TenderExcelSchema.Field f : vs.fields()) {
            if (!seen.contains(TenderExcelSchema.norm(f.label()))) {
                warnings.add(vs.name() + ": field \"" + f.label() + "\" is missing, so it was left blank.");
            }
        }
        return out;
    }

    /**
     * Content in a column the sheet does not define: anything past Field and
     * Value that is neither the Source Text column nor the template's own
     * locked Format hints.
     */
    private static boolean hasContentOutside(Row row, int sourceCol, int formatCol) {
        for (Cell c : row) {
            int col = c.getColumnIndex();
            if (col <= 1 || col == sourceCol || col == formatCol) continue;
            if (!text(c).strip().isEmpty()) return true;
        }
        return false;
    }

    // ── table sheets: header row, then one row per item ──────────────────────

    private static List<TableRow> readTable(Sheet sheet, TenderExcelSchema.TableSheet ts, String version,
                                            List<String> warnings) {
        List<TenderExcelSchema.Field> expected = TenderExcelSchema.columns(ts, version);
        Map<String, TenderExcelSchema.Field> byHeader = new HashMap<>();
        for (TenderExcelSchema.Field f : expected) byHeader.put(TenderExcelSchema.norm(f.label()), f);

        Row header = null;
        for (Row row : sheet) {
            if (!blankRow(row)) { header = row; break; }
        }
        Map<Integer, String> colKey = new LinkedHashMap<>();
        if (header != null) {
            for (Cell c : header) {
                String h = text(c).strip();
                if (h.isEmpty()) continue;
                TenderExcelSchema.Field f = byHeader.get(TenderExcelSchema.norm(h));
                if (f == null) {
                    warnings.add(ts.name() + ": unknown column \"" + h + "\" was ignored.");
                } else if (colKey.containsValue(f.key())) {
                    warnings.add(ts.name() + ": column \"" + h + "\" appears twice; the first one was used.");
                } else {
                    colKey.put(c.getColumnIndex(), f.key());
                }
            }
        }
        TenderExcelSchema.Field primary = TenderExcelSchema.column(ts, ts.primary());
        if (!colKey.containsValue(ts.primary())) {
            throw new Rejected("Sheet \"" + ts.name() + "\" has no \"" + primary.label()
                    + "\" column — was the header renamed? Column headers must stay as in the template.");
        }
        for (TenderExcelSchema.Field f : expected) {
            if (!colKey.containsValue(f.key())) {
                warnings.add(ts.name() + ": column \"" + f.label() + "\" is missing, so it was left blank.");
            }
        }

        List<TableRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() <= header.getRowNum()) continue;
            Map<String, Raw> cells = new LinkedHashMap<>();
            boolean any = false;
            for (Map.Entry<Integer, String> e : colKey.entrySet()) {
                Raw r = raw(row.getCell(e.getKey()));
                cells.put(e.getValue(), r);
                any |= !r.isBlank();
            }
            if (any) rows.add(new TableRow(row.getRowNum() + 1, cells));
        }
        return rows;
    }

    private static boolean blankRow(Row row) {
        for (Cell c : row) if (!text(c).isBlank()) return false;
        return true;
    }

    // ── cells ────────────────────────────────────────────────────────────────

    /** What a cell holds, keeping numbers, dates and %-formatting distinct from text. */
    static Raw raw(Cell c) {
        if (c == null) return Raw.text(null);
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        switch (type) {
            case NUMERIC: {
                if (DateUtil.isCellDateFormatted(c)) {
                    return Raw.date(c.getLocalDateTimeCellValue().toLocalDate());
                }
                String fmt = c.getCellStyle() == null ? "" : c.getCellStyle().getDataFormatString();
                return Raw.number(c.getNumericCellValue(), fmt != null && fmt.contains("%"));
            }
            case STRING:
                return Raw.text(c.getRichStringCellValue().getString());
            case BOOLEAN:
                return Raw.text(c.getBooleanCellValue() ? "Yes" : "No");
            default:
                return Raw.text(null);
        }
    }

    private static String text(Cell c) {
        return c == null ? "" : FORMATTER.formatCellValue(c);
    }

    private static String quoteAll(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(i == names.size() - 1 ? " and " : ", ");
            sb.append('"').append(names.get(i)).append('"');
        }
        return sb.toString();
    }
}
