package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * What the import refuses, and what it tolerates with a warning. A file is
 * refused when importing what is left of it would silently lose a section; it
 * is tolerated when the difference is cosmetic (case, spacing, an extra sheet).
 */
class TenderExcelStructureTest {

    static byte[] template() {
        return TenderExcelWriter.write(null, TenderExcelValuesTest.options());
    }

    /** The blank template, changed by {@code edit}, saved back to bytes. */
    static byte[] edited(Consumer<XSSFWorkbook> edit) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template()));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            edit.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String rejection(byte[] bytes, String name) {
        return assertThrows(TenderExcelReader.Rejected.class, () -> TenderExcelReader.read(bytes, name)).getMessage();
    }

    @Test
    void theBlankTemplateReadsCleanly() {
        TenderExcelReader.Workbook wb = TenderExcelReader.read(template(), "t.xlsx");
        assertEquals(TenderExcelSchema.VERSION, wb.version());
        assertTrue(wb.warnings().isEmpty(), () -> "unexpected warnings: " + wb.warnings());
        assertTrue(wb.fields().stream().allMatch(f -> f.raw().isBlank()));
    }

    @Test
    void missingMetaIsRefused() {
        byte[] bytes = edited(wb -> wb.removeSheetAt(wb.getSheetIndex(TenderExcelSchema.META)));
        assertTrue(rejection(bytes, "t.xlsx").contains("_meta"));
    }

    @Test
    void unknownVersionIsRefused() {
        byte[] bytes = edited(wb -> wb.getSheet(TenderExcelSchema.META).getRow(0).getCell(1).setCellValue("TENDER-XLSX-0"));
        assertTrue(rejection(bytes, "t.xlsx").contains("TENDER-XLSX-0"));
    }

    @Test
    void aRenamedSheetIsRefusedByName() {
        byte[] bytes = edited(wb -> wb.setSheetName(wb.getSheetIndex("Eligibility"), "Qualification"));
        String msg = rejection(bytes, "t.xlsx");
        assertTrue(msg.contains("\"Eligibility\""), msg);
        assertTrue(msg.contains("missing"), msg);
    }

    @Test
    void aDeletedSheetIsRefusedByName() {
        byte[] bytes = edited(wb -> wb.removeSheetAt(wb.getSheetIndex("BOQ")));
        assertTrue(rejection(bytes, "t.xlsx").contains("\"BOQ\""));
    }

    @Test
    void aRenamedPrimaryColumnIsRefused() {
        byte[] bytes = edited(wb -> wb.getSheet("Documents").getRow(0).getCell(0).setCellValue("Docs"));
        assertTrue(rejection(bytes, "t.xlsx").contains("Document Name"));
    }

    @Test
    void caseAndSpacingInNamesAreTolerated() {
        byte[] bytes = edited(wb -> {
            wb.setSheetName(wb.getSheetIndex("Basic Info"), "basic  INFO");
            wb.setSheetName(wb.getSheetIndex("Dates & Money"), " dates & money");
            XSSFSheet basic = wb.getSheet("basic  INFO");
            Row r = basic.getRow(1);                            // Tender Number
            r.getCell(0).setCellValue("  TENDER   number ");
            r.getCell(1).setCellValue("GEM/2026/B/5541203");
            wb.getSheet("BOQ").getRow(0).getCell(2).setCellValue("description ");
        });
        TenderExcelReader.Workbook wb = TenderExcelReader.read(bytes, "Filled.XLSX");
        assertTrue(wb.warnings().isEmpty(), () -> "unexpected warnings: " + wb.warnings());
        assertEquals("GEM/2026/B/5541203", wb.fields().stream()
                .filter(f -> f.key().equals("tenderNumber")).findFirst().orElseThrow().raw().text());
    }

    @Test
    void extraSheetsFieldsAndColumnsAreWarnings() {
        byte[] bytes = edited(wb -> {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("from the model");
            XSSFSheet basic = wb.getSheet("Basic Info");
            Row extra = basic.createRow(basic.getLastRowNum() + 1);
            extra.createCell(0).setCellValue("Bid Validity");
            extra.createCell(1).setCellValue("180 days");
            wb.getSheet("BOQ").getRow(0).createCell(7).setCellValue("Rate");
            basic.getRow(2).createCell(4).setCellValue("source: page 1");
        });
        TenderExcelReader.Workbook wb = TenderExcelReader.read(bytes, "t.xlsx");
        String all = String.join(" | ", wb.warnings());
        assertTrue(all.contains("\"Notes\""), all);
        assertTrue(all.contains("\"Bid Validity\""), all);
        assertTrue(all.contains("\"Rate\""), all);
        assertTrue(all.contains("extra columns"), all);
    }

    @Test
    void aMissingFieldRowIsAWarning() {
        byte[] bytes = edited(wb -> {
            XSSFSheet emd = wb.getSheet("EMD Requirements");
            emd.removeRow(emd.getRow(6));                       // Beneficiary IFSC
        });
        assertTrue(String.join(" ", TenderExcelReader.read(bytes, "t.xlsx").warnings()).contains("Beneficiary IFSC"));
    }

    @Test
    void onlyXlsxIsAccepted() {
        assertTrue(rejection(template(), "tender.xls").contains(".xlsx"));
        assertTrue(rejection(template(), "tender.xlsm").contains("Macro"));
        assertTrue(rejection("not a zip".getBytes(), "tender.xlsx").contains("not a valid"));
    }

    @Test
    void oversizedFilesAreRefused() {
        byte[] big = new byte[(int) TenderExcelReader.MAX_BYTES + 1];
        big[0] = 'P';
        big[1] = 'K';
        assertTrue(rejection(big, "t.xlsx").contains("MB"));
    }

    @Test
    void macrosAreRefusedEvenWhenNamedXlsx() throws IOException {
        // The template with a VBA project part added, saved under a .xlsx name.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(template()));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write(in.readAllBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        assertTrue(rejection(out.toByteArray(), "t.xlsx").contains("macros"));
    }
}
