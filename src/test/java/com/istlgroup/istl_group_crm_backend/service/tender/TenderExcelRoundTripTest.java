package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderBoqItemWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderDocumentWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderEligibilityWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TenderWrapper;

/**
 * Export → import gives back the same tender, and a hand-filled (or
 * LLM-filled) template reads as the same values a person meant.
 */
class TenderExcelRoundTripTest {

    static TenderWrapper fullTender() {
        TenderWrapper t = new TenderWrapper();
        t.setTenderNumber("GEM/2026/B/5541203");
        t.setTenderName("2 MW Rooftop Solar — District Hospital Complex");
        t.setIssuingAuthority("Karnataka Health & Family Welfare Department");
        t.setClientCompany("Govt. of Karnataka");
        t.setClientType("Government");
        t.setClientGstin("29AAACG1234F1Z5");
        t.setClientPan("AAACG1234F");
        t.setClientCin("U40100KA2010SGC012345");
        t.setClientContactPerson("Ramesh K");
        t.setClientContactEmail("ee.solar@karhealth.gov.in");
        t.setClientContactPhone("08022334455");
        t.setClientAddress("Victoria Hospital Campus, Fort Road, Bengaluru 560002");
        t.setClientCity("Bengaluru");
        t.setClientState("Karnataka");
        t.setSector("Rooftop Solar");
        t.setTenderType("Open");
        t.setSource("GeM");
        t.setPortalLink("https://gem.gov.in/bid/5541203");
        t.setLocation("Victoria Hospital");
        t.setDistrict("Bengaluru Urban");
        t.setState("Karnataka");
        t.setFinancialYear("2026-27");
        t.setEstimatedValue("92000000");
        t.setEmdAmount("920000");
        t.setPerformanceSecurityPct("5");
        t.setSubmissionDeadline("2026-08-14");
        t.setTechnicalOpeningDate("2026-08-18");
        t.setFinancialOpeningDate("2026-08-25");
        t.setEmdValidTill("2027-02-14");
        t.setEmdBeneficiaryName("Director, Health & Family Welfare");
        t.setEmdBeneficiaryBank("State Bank of India");
        t.setEmdBeneficiaryAccount("00000012345678901");
        t.setEmdBeneficiaryIfsc("SBIN0001234");
        t.setFeeAmount("59000");
        t.setFeeRefundable("No");
        t.setFeeBeneficiaryName("Central Electronics Limited");
        t.setFeeBeneficiaryBank("Canara Bank");
        t.setFeeBeneficiaryAccount("87761250000014");
        t.setFeeBeneficiaryIfsc("CNRB0006999");

        List<TenderEligibilityWrapper> elig = new ArrayList<>();
        elig.add(criterion("Financial", "Average annual turnover (last 3 FY)", "30000000", "gte", ""));
        elig.add(criterion("Technical", "One 66kV sub-station", "1", "gte", "A1"));
        elig.add(criterion("Technical", "Five 33kV sub-stations", "5", "gte", "A1"));
        elig.add(criterion("Legal", "Not blacklisted", "yes", "boolean", ""));
        elig.get(0).setTier("Category A");
        t.setEligibilityCriteria(elig);

        List<TenderDocumentWrapper> docs = new ArrayList<>();
        for (String n : List.of("EMD / Bid Security", "GST Registration Certificate", "PAN Card")) {
            TenderDocumentWrapper d = new TenderDocumentWrapper();
            d.setDocumentName(n);
            docs.add(d);
        }
        t.setDocuments(docs);

        List<TenderBoqItemWrapper> boq = new ArrayList<>();
        boq.add(boqItem("1.1", "Supply", "Mono PERC Bifacial Module 550Wp", "Nos", "3640"));
        boq.add(boqItem("2.1", "Installation", "Module Mounting Structure (GI) & install", "kW", "2000.5"));
        t.setBoqItems(boq);
        return t;
    }

    private static TenderEligibilityWrapper criterion(String cat, String name, String req, String op, String alt) {
        TenderEligibilityWrapper e = new TenderEligibilityWrapper();
        e.setCategory(cat);
        e.setCriterionName(name);
        e.setRequiredValue(req);
        e.setOperator(op);
        e.setAltGroup(alt);
        return e;
    }

    private static TenderBoqItemWrapper boqItem(String no, String scope, String desc, String unit, String qty) {
        TenderBoqItemWrapper b = new TenderBoqItemWrapper();
        b.setItemNo(no);
        b.setScope(scope);
        b.setDescription(desc);
        b.setUnit(unit);
        b.setQuantity(qty);
        return b;
    }

    private static TenderExcelImport.Result importBytes(byte[] bytes) {
        TenderExcelReader.Workbook wb = TenderExcelReader.read(bytes, "t.xlsx");
        return TenderExcelImport.evaluate(new TenderExcelImport.Input(wb.fields(), wb.tables()),
                TenderExcelValuesTest.options(), wb.version(), "t.xlsx", wb.warnings());
    }

    private static Map<String, TenderExcelImport.FieldResult> byField(TenderExcelImport.Result r) {
        Map<String, TenderExcelImport.FieldResult> m = new LinkedHashMap<>();
        r.fields().forEach(f -> m.put(f.field(), f));
        return m;
    }

    @Test
    void exportThenImportGivesTheSameTender() {
        TenderWrapper t = fullTender();
        TenderExcelImport.Result r = importBytes(TenderExcelWriter.write(t, TenderExcelValuesTest.options()));

        assertTrue(r.warnings().isEmpty(), () -> "warnings: " + r.warnings());
        Map<String, TenderExcelImport.FieldResult> f = byField(r);
        for (TenderExcelSchema.VerticalSheet vs : TenderExcelSchema.VERTICAL) {
            for (TenderExcelSchema.Field field : vs.fields()) {
                TenderExcelImport.FieldResult got = f.get(field.key());
                assertEquals(TenderExcelWriter.scalar(t, field.key()), got == null ? null : got.value(), field.key());
            }
        }
        // Only the provisional and date-relative warnings may appear — never an error.
        assertEquals(0, r.errorCount(), () -> "errors: " + r.fields());

        assertEquals(4, r.eligibilityCriteria().size());
        for (int i = 0; i < 4; i++) {
            TenderEligibilityWrapper want = t.getEligibilityCriteria().get(i);
            Map<String, Object> got = r.eligibilityCriteria().get(i);
            assertEquals(want.getCategory(), got.get("category"));
            assertEquals(want.getCriterionName(), got.get("criterionName"));
            assertEquals(want.getRequiredValue(), got.get("requiredValue"));
            assertEquals(want.getOperator(), got.get("operator"), "operator label maps back to its code");
            assertEquals(want.getAltGroup(), got.get("altGroup"));
            assertEquals(want.getTier() == null ? "" : want.getTier(), got.get("tier"));
            assertEquals(TenderExcelImport.OK, got.get("status"), () -> "issues: " + got.get("issues"));
            // No PDF given here, so nothing can be verified — and nothing claims to be.
            assertEquals(TenderExcelVerifier.UNVERIFIED, got.get("verification"));
        }
        assertEquals(List.of("EMD / Bid Security", "GST Registration Certificate", "PAN Card"),
                r.documents().stream().map(d -> d.get("documentName")).toList());
        assertEquals(2, r.boqItems().size());
        assertEquals("1.1", r.boqItems().get(0).get("itemNo"));
        assertEquals("Mono PERC Bifacial Module 550Wp", r.boqItems().get(0).get("description"));
        assertEquals("3640", r.boqItems().get(0).get("quantity"));
        assertEquals("2000.5", r.boqItems().get(1).get("quantity"));
        assertEquals("kW", r.boqItems().get(1).get("unit"));
    }

    @Test
    void aTemplateFilledTheWayAnLlmWritesReadsAsMeant() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                TenderExcelWriter.write(null, TenderExcelValuesTest.options())))) {
            XSSFSheet basic = wb.getSheet("Basic Info");
            set(basic, "Tender Number", "TREDA/2025-26/EPC/14");
            set(basic, "Client Type", "government");
            set(basic, "Sector", "Hydro");                     // not an option
            XSSFSheet money = wb.getSheet("Dates & Money");
            set(money, "Estimated Value", "₹26.90 Crore");
            set(money, "EMD Amount", "Rs. 53,80,000/-");
            set(money, "Performance Security %", "5%");
            set(money, "Submission Deadline", "08/07/2026");
            Row deadline = find(money, "Technical Opening Date");
            deadline.getCell(1).setCellValue(LocalDate.of(2026, 7, 10));   // a real date cell
            XSSFSheet elig = wb.getSheet("Eligibility");
            Row e = elig.createRow(1);
            e.createCell(0).setCellValue("Financial");
            e.createCell(1).setCellValue("Average annual turnover");
            e.createCell(2).setCellValue("₹26.90 Crore");
            e.createCell(3).setCellValue("≥ (at least)");
            XSSFSheet docs = wb.getSheet("Documents");
            docs.createRow(1).createCell(0).setCellValue("Solvency Certificate");
            docs.createRow(3).createCell(0).setCellValue("PAN Card");      // a blank row between
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }

        TenderExcelImport.Result r = importBytes(bytes);
        Map<String, TenderExcelImport.FieldResult> f = byField(r);
        assertEquals("269000000", f.get("estimatedValue").value());
        assertEquals("5380000", f.get("emdAmount").value());
        assertEquals("5", f.get("performanceSecurityPct").value());
        assertEquals("2026-07-08", f.get("submissionDeadline").value());
        assertEquals("2026-07-10", f.get("technicalOpeningDate").value());
        assertEquals("Government", f.get("clientType").value());
        assertEquals(TenderExcelImport.WARNING, f.get("sector").status());
        assertEquals(null, f.get("sector").value());
        assertEquals("269000000", r.eligibilityCriteria().get(0).get("requiredValue"));
        assertEquals("gte", r.eligibilityCriteria().get(0).get("operator"));
        assertEquals(List.of("Solvency Certificate", "PAN Card"),
                r.documents().stream().map(d -> d.get("documentName")).toList());
    }

    @Test
    void theTemplateCarriesTheContractItPromises() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                TenderExcelWriter.write(null, TenderExcelValuesTest.options())))) {
            List<String> visible = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) visible.add(wb.getSheetName(i));
            assertEquals(List.of("Instructions", "Basic Info", "Dates & Money", "EMD Requirements", "Tender Fee",
                    "Eligibility", "Documents", "BOQ", "_meta", "_lists"), visible);
            assertEquals(SheetVisibility.HIDDEN, wb.getSheetVisibility(wb.getSheetIndex("_meta")));
            assertEquals(SheetVisibility.VERY_HIDDEN, wb.getSheetVisibility(wb.getSheetIndex("_lists")));
            assertEquals(TenderExcelSchema.VERSION, wb.getSheet("_meta").getRow(0).getCell(1).getStringCellValue());

            XSSFSheet basic = wb.getSheet("Basic Info");
            assertTrue(basic.getProtect(), "Basic Info is protected");
            assertTrue(basic.getRow(1).getCell(0).getCellStyle().getLocked(), "Field column locked");
            assertTrue(!basic.getRow(1).getCell(1).getCellStyle().getLocked(), "Value column open");
            assertEquals("Source Text", basic.getRow(0).getCell(2).getStringCellValue());
            assertTrue(!basic.getRow(1).getCell(2).getCellStyle().getLocked(), "Source Text column open");
            assertEquals("Format", basic.getRow(0).getCell(3).getStringCellValue());

            // Coloured headers: navy with white text, Source Text amber.
            assertEquals("FF1F4E79", fill(basic.getRow(0).getCell(0)));
            assertEquals("FFFFC000", fill(basic.getRow(0).getCell(2)));
            XSSFSheet eligibility = wb.getSheet("Eligibility");
            Row head = eligibility.getRow(0);
            assertEquals("Source Text", head.getCell(head.getLastCellNum() - 1).getStringCellValue());
            assertEquals("FFFFC000", fill(head.getCell(head.getLastCellNum() - 1)));
            assertEquals("Tier", head.getCell(4).getStringCellValue());
            assertTrue(eligibility.getTabColor() != null, "sheet tabs are coloured");

            // Client Type, Sector, Tender Type, Source on Basic Info; Category + Operator on
            // Eligibility; Fee Refundable on Tender Fee.
            assertEquals(4, basic.getDataValidations().size());
            assertEquals(2, eligibility.getDataValidations().size());
            assertEquals(1, wb.getSheet("Tender Fee").getDataValidations().size());
            for (DataValidation dv : basic.getDataValidations()) {
                assertTrue(dv.getValidationConstraint().getFormula1().startsWith("list_"));
            }
            String prompt = wb.getSheet("Instructions").getRow(12).getCell(0).getStringCellValue();
            assertTrue(prompt.contains("YYYY-MM-DD"));
            assertTrue(prompt.contains("10000000"));
            assertTrue(prompt.contains("leave the cell blank"));
            assertTrue(prompt.contains("Write only data into the cells. Do not copy these instructions"));
            assertTrue(prompt.contains("\"Source Text\""));
            assertTrue(prompt.contains("\"Tier\""));
            assertTrue(prompt.contains("never in EMD"));
            assertTrue(prompt.contains("Do not convert it to CSV"));
            for (String brand : List.of("Claude", "ChatGPT", "Gemini", "GPT")) {
                assertTrue(!prompt.contains(brand), "the prompt is model-neutral: " + brand);
            }
        }
    }

    private static String fill(org.apache.poi.ss.usermodel.Cell c) {
        return ((org.apache.poi.xssf.usermodel.XSSFCellStyle) c.getCellStyle()).getFillForegroundXSSFColor().getARGBHex();
    }

    private static Row find(XSSFSheet s, String label) {
        for (Row r : s) {
            if (r.getCell(0) != null && label.equals(r.getCell(0).getStringCellValue())) return r;
        }
        throw new AssertionError("no row " + label);
    }

    private static void set(XSSFSheet s, String label, String value) {
        find(s, label).getCell(1).setCellValue(value);
    }
}
