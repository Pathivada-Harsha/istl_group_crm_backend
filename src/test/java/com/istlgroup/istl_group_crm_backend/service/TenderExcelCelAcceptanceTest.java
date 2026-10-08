package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelImport;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelImport.FieldResult;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelImport.Result;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelOptions;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelVerifier;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelWriter;

/**
 * The acceptance case behind TENDER-XLSX-2: the CEL solar EPC empanelment EOI
 * (17-page text PDF), as ChatGPT filled the first template, and as a filled
 * second template — run through the real import service with the real PDF.
 */
class TenderExcelCelAcceptanceTest {

    private static final Path DIR = Path.of("src/test/resources/tender-samples/excel");

    /** The vocabularies tenderData.js sends — the CEL file's own _lists sheet carries the same. */
    private static TenderExcelOptions options() {
        return new TenderExcelOptions(
                List.of("Government", "PSU", "Private", "Cooperative", "Individual", "Developer"),
                List.of("Solar EPC", "Rooftop Solar", "Ground Mount", "Solar Pump", "O&M",
                        "Street Lighting", "BESS / Storage", "Electrical", "Civil", "Other"),
                List.of("Open", "Limited", "EOI", "RFP", "RFQ", "Reverse Auction", "Nomination"),
                List.of("GeM", "CPPP", "State Portal", "IREPS / Railways", "Newspaper", "Direct / Client", "Referral", "Other"),
                List.of("Technical", "Financial", "Legal"),
                List.of(new TenderExcelOptions.Operator("gte", "≥ (at least)"),
                        new TenderExcelOptions.Operator("lte", "≤ (at most)"),
                        new TenderExcelOptions.Operator("eq", "= (equals)"),
                        new TenderExcelOptions.Operator("contains", "contains"),
                        new TenderExcelOptions.Operator("boolean", "yes / no")));
    }

    private final TenderExcelService service = new TenderExcelService();
    private final MockHttpSession session = new MockHttpSession();

    private static MockMultipartFile xlsx(String name, byte[] bytes) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("pdf", "cel-eoi.pdf", "application/pdf", bytes);
    }

    private static byte[] celPdf() throws Exception {
        return Files.readAllBytes(DIR.resolve("cel-eoi.pdf"));
    }

    private Result importFile(byte[] excel, byte[] pdfBytes) throws CustomException {
        return service.importFile(xlsx("filled.xlsx", excel), pdfBytes == null ? null : pdf(pdfBytes),
                null, options(), session);
    }

    private static Map<String, FieldResult> byField(Result r) {
        Map<String, FieldResult> m = new LinkedHashMap<>();
        r.fields().forEach(f -> m.put(f.field(), f));
        return m;
    }

    // ── the file ChatGPT filled from the first template ─────────────────────

    @Test
    void theChatGptFilledV1FileImportsWithEveryFixShownAndNothingVerified() throws Exception {
        Result r = importFile(Files.readAllBytes(DIR.resolve("cel-eoi-chatgpt-v1.xlsx")), celPdf());

        assertEquals(TenderExcelSchema.V1, r.version());
        assertEquals("v1", r.pdfStatus());
        assertNotNull(r.pdfNote());
        assertNotNull(r.importId());
        assertTrue(r.warnings().isEmpty(), () -> "structural warnings: " + r.warnings());
        assertEquals(0, r.errorCount(), () -> "errors: " + r.fields() + r.eligibilityCriteria());

        // Old files have no Source Text: nothing can be verified, and nothing claims to be.
        assertEquals(0, r.verifiedCount());
        assertEquals(0, r.mismatchCount());
        r.fields().forEach(f -> assertEquals(TenderExcelVerifier.UNVERIFIED, f.verification(), f.field()));
        for (Map<String, Object> row : r.eligibilityCriteria()) {
            assertEquals(TenderExcelVerifier.UNVERIFIED, row.get("verification"));
        }

        List<Map<String, Object>> elig = r.eligibilityCriteria();
        assertEquals(17, elig.size());
        Map<String, Object> catA = elig.get(5);                          // "More than 10 MWp in SPV Power Plants"
        assertEquals("10", catA.get("requiredValue"));
        assertEquals("gte", catA.get("operator"));
        assertTrue(catA.get("criterionName").toString().contains("10 MWp in SPV Power Plants"));
        assertEquals("A1", catA.get("altGroup"));

        Map<String, Object> amc = elig.get(11);                          // "At least 1 year"
        assertEquals("1", amc.get("requiredValue"));
        assertEquals("gte", amc.get("operator"));

        Map<String, Object> capability = elig.get(12);                   // "Required"
        assertEquals("", capability.get("requiredValue"));
        assertEquals("boolean", capability.get("operator"));

        Map<String, Object> iso = elig.get(14);                          // "ISO 9001:2015" — plain text
        assertEquals("ISO 9001:2015", iso.get("requiredValue"));

        Map<String, Object> turnoverA = elig.get(0);                     // 1000000000 with "≥"
        assertEquals("1000000000", turnoverA.get("requiredValue"));
        assertEquals("gte", turnoverA.get("operator"));
        assertEquals(TenderExcelImport.OK, turnoverA.get("status"));

        // Every auto-fix is a warning that names the original — nothing silently changed or blanked.
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(DIR.resolve("cel-eoi-chatgpt-v1.xlsx")))) {
            XSSFSheet sheet = wb.getSheet("Eligibility");
            org.apache.poi.ss.usermodel.DataFormatter shown = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 0; i < elig.size(); i++) {
                Cell cell = sheet.getRow(i + 1).getCell(2);
                String written = cell == null ? "" : shown.formatCellValue(cell).strip();
                Map<String, Object> row = elig.get(i);
                boolean changed = !written.isEmpty() && !written.equals(row.get("requiredValue"));
                if (changed) {
                    assertEquals(TenderExcelImport.WARNING, row.get("status"), "row " + (i + 2));
                    assertTrue(row.get("issues").toString().contains("\"" + written + "\""),
                            () -> "the warning quotes the original: " + row.get("issues"));
                }
            }
        }

        // Old-template files still carry the fields they had; the new ones simply are not there.
        Map<String, FieldResult> f = byField(r);
        assertEquals("C-2(b)/EOI/704/0388/2025", f.get("tenderNumber").value());
        assertEquals("PSU", f.get("clientType").value());
        assertEquals("2026-03-31", f.get("submissionDeadline").value());
        assertNull(f.get("feeAmount"));
        assertNull(f.get("emdAmount"));
    }

    // ── a filled second template, with Source Text ───────────────────────────

    /** The CEL tender as a careful LLM fills TENDER-XLSX-2: values plus the PDF words they came from. */
    private static byte[] celV2(Consumer<XSSFWorkbook> extra) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(TenderExcelWriter.write(null, options())))) {
            XSSFSheet basic = wb.getSheet("Basic Info");
            set(basic, "Tender Number", "C-2(b)/EOI/704/0388/2025", "EOI No. C-2(b)/EOI/704/0388/2025");
            set(basic, "Issuing Authority", "Central Electronics Limited", "CENTRAL ELECTRONICS LIMITED");
            set(basic, "Client Type", "PSU", "Mini Ratna, government of India enterprises");
            set(basic, "Tender Type", "EOI", "NOTICE INVITING EXPRESSION OF INTEREST");
            set(basic, "Client Contact Email", "spv@celindia.co.in", "For any clarifications: spv@celindia.co.in");
            set(basic, "Portal Link", "https://www.etenders.gov.in", "ONLINE on www.etenders.gov.in");
            // Planted: the phone the PDF does not give.
            set(basic, "Client Contact Phone", "0120-2895155", "Telephone: 0120-2895144/45");
            // No Source Text at all: cannot be verified.
            set(basic, "Client City", "Sahibabad", null);

            XSSFSheet money = wb.getSheet("Dates & Money");
            set(money, "Submission Deadline", "2026-03-31", "perpetually open up to: 31/03/2026");

            XSSFSheet fee = wb.getSheet("Tender Fee");
            set(fee, "Fee Amount", "59000", "non-refundable processing fees of Rs.59,000/-");
            set(fee, "Fee Refundable", "No", "non-refundable processing fees");
            set(fee, "Fee Beneficiary Name", "Central Electronics Limited", "Beneficiary Name: Central Electronics Limited");
            set(fee, "Fee Beneficiary Bank", "Canara Bank", "Beneficiary Bank Name: Canara Bank");
            set(fee, "Fee Beneficiary Account No", "87761250000014", "Beneficiary Account Number/IBAN: 87761250000014");
            set(fee, "Fee Beneficiary IFSC", "CNRB0006999", "Beneficiary Bank IFSC Code: CNRB0006999");

            XSSFSheet elig = wb.getSheet("Eligibility");
            int r = 1;
            row(elig, r++, Map.of("Category", "Financial", "Criterion", "Average turnover (last 3 FY)",
                    "Required Value", "1000000000", "Operator", "≥", "Tier", "Category A",
                    "Source Text", "Average Turnover greater or equal to Rs. 100 Cr."));
            row(elig, r++, Map.of("Category", "Technical", "Criterion", "Solar EPC experience in SPV Power Plants (MWp)",
                    "Required Value", "10", "Operator", "≥", "Tier", "Category A", "Alternative Group", "A1",
                    "Source Text", "More than 10 MWp in SPV Power Plants"));
            // Reworded by the LLM ("Turnover" for the PDF's letter-spaced "T u r n o v e r"): not found.
            row(elig, r++, Map.of("Category", "Financial", "Criterion", "Average turnover (last 3 FY)",
                    "Required Value", "500000000", "Operator", "≥", "Tier", "Category B",
                    "Source Text", "Average Turnover greater or equal to Rs. 50 Cr"));
            row(elig, r++, Map.of("Category", "Technical", "Criterion", "Solar EPC experience in SPV Power Plants (MWp)",
                    "Required Value", "5", "Operator", "≥", "Tier", "Category B",
                    "Source Text", "More than 5 MWp in SPV Power Plants"));
            row(elig, r++, Map.of("Category", "Technical", "Criterion", "Solar EPC experience in SPV Power Plants (MWp)",
                    "Required Value", "1", "Operator", "≥", "Tier", "Category C",
                    "Source Text", "1 MWp in SPV Power Plants"));
            // Planted: 6 Cr where the PDF says 5 Cr.
            row(elig, r++, Map.of("Category", "Financial", "Criterion", "Average turnover (last 3 FY)",
                    "Required Value", "60000000", "Operator", "≥", "Tier", "Category C",
                    "Source Text", "Rs. 5 Cr and less than Rs. 50 Cr."));
            row(elig, r++, Map.of("Category", "Technical", "Criterion", "Valid ISO 9001:2015 certification",
                    "Operator", "yes/no", "Source Text", "The party must have valid ISO: 9001:2015 certification."));

            XSSFSheet docs = wb.getSheet("Documents");
            row(docs, 1, Map.of("Document Name", "Valid ISO 9001:2015 certificate",
                    "Source Text", "Valid ISO: 9001:2015 certificate must be submitted."));
            if (extra != null) extra.accept(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void aV2FillIsVerifiedAgainstThePdfValueByValue() throws Exception {
        Result r = importFile(celV2(null), celPdf());
        assertEquals(TenderExcelSchema.VERSION, r.version());
        assertEquals("ok", r.pdfStatus());
        assertTrue(r.warnings().isEmpty(), () -> "structural warnings: " + r.warnings());
        Map<String, FieldResult> f = byField(r);

        // The processing fee and its Canara Bank details land in the Fee fields, verified; EMD stays empty.
        for (String key : List.of("feeAmount", "feeRefundable", "feeBeneficiaryName", "feeBeneficiaryBank",
                "feeBeneficiaryAccount", "feeBeneficiaryIfsc")) {
            assertEquals(TenderExcelVerifier.VERIFIED, f.get(key).verification(),
                    () -> key + ": " + f.get(key).verifyReason());
        }
        assertEquals("59000", f.get("feeAmount").value());
        assertEquals("No", f.get("feeRefundable").value());
        assertEquals("CNRB0006999", f.get("feeBeneficiaryIfsc").value());
        assertNotNull(f.get("feeAmount").verifyPage());
        assertTrue(f.get("feeAmount").verifyText().contains("59,000"), f.get("feeAmount").verifyText());
        assertNull(f.get("emdAmount"));

        for (String key : List.of("tenderNumber", "issuingAuthority", "clientType", "tenderType",
                "clientContactEmail", "portalLink", "submissionDeadline")) {
            assertEquals(TenderExcelVerifier.VERIFIED, f.get(key).verification(),
                    () -> key + ": " + f.get(key).verifyReason());
        }
        // Planted phone: the source is real, the value is not what it says → error.
        assertEquals(TenderExcelVerifier.MISMATCH, f.get("clientContactPhone").verification());
        assertEquals(TenderExcelImport.ERROR, f.get("clientContactPhone").status());
        // No source → unverified, but not an error.
        assertEquals(TenderExcelVerifier.UNVERIFIED, f.get("clientCity").verification());
        assertFalse(TenderExcelImport.ERROR.equals(f.get("clientCity").status()));

        // Three tiers, as the EOI defines them.
        List<Map<String, Object>> elig = r.eligibilityCriteria();
        Set<Object> tiers = new TreeSet<>();
        for (Map<String, Object> row : elig) if (!"".equals(row.get("tier"))) tiers.add(row.get("tier"));
        assertEquals(Set.of("Category A", "Category B", "Category C"), tiers);

        assertEquals(TenderExcelVerifier.VERIFIED, elig.get(0).get("verification"), () -> "" + elig.get(0));
        assertEquals(TenderExcelVerifier.VERIFIED, elig.get(1).get("verification"));
        assertEquals(TenderExcelVerifier.UNVERIFIED, elig.get(2).get("verification"), "reworded source");
        assertEquals(TenderExcelVerifier.VERIFIED, elig.get(3).get("verification"));
        assertEquals(TenderExcelVerifier.VERIFIED, elig.get(4).get("verification"));
        assertEquals(TenderExcelVerifier.MISMATCH, elig.get(5).get("verification"));
        assertEquals(TenderExcelImport.ERROR, elig.get(5).get("status"));
        assertEquals(TenderExcelVerifier.VERIFIED, elig.get(6).get("verification"));
        assertEquals("boolean", elig.get(6).get("operator"));
        assertNotNull(elig.get(1).get("verifyPage"));
        assertEquals(TenderExcelVerifier.VERIFIED, r.documents().get(0).get("verification"));

        assertEquals(2, r.errorCount(), "exactly the two planted mismatches block the save");
        assertEquals(2, r.mismatchCount());
    }

    @Test
    void fixingAValueInTheReviewIsReCheckedAgainstTheSamePdf() throws Exception {
        Result r = importFile(celV2(null), celPdf());

        Map<String, String> fields = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        fields.put("clientContactPhone", "0120-2895144");
        sources.put("clientContactPhone", "Telephone: 0120-2895144/45");
        Result again = service.validate(fields, sources, Map.of(), options(), r.version(), r.importId(), session);
        FieldResult phone = byField(again).get("clientContactPhone");
        assertEquals(TenderExcelVerifier.VERIFIED, phone.verification(), phone.verifyReason());

        Result gone = service.validate(fields, sources, Map.of(), options(), r.version(), "no-such-import", session);
        assertEquals("expired", gone.pdfStatus());
        assertEquals(TenderExcelVerifier.UNVERIFIED, byField(gone).get("clientContactPhone").verification());
    }

    @Test
    void twoFillsOfTheSameTenderAgreeOnEveryValueBothVerify() throws Exception {
        Result a = importFile(celV2(null), celPdf());
        // A second model: a slip in the fee, a source left out, a different bank account format.
        Result b = importFile(celV2(wb -> {
            XSSFSheet fee = wb.getSheet("Tender Fee");
            set(fee, "Fee Amount", "50000", "processing fees of Rs.59,000/-");
            set(fee, "Fee Beneficiary IFSC", "CNRB0006999", null);
        }), celPdf());

        Map<String, FieldResult> fa = byField(a);
        Map<String, FieldResult> fb = byField(b);
        List<String> differ = new ArrayList<>();
        for (String key : fa.keySet()) {
            FieldResult x = fa.get(key);
            FieldResult y = fb.get(key);
            if (y == null) continue;
            boolean bothVerified = TenderExcelVerifier.VERIFIED.equals(x.verification())
                    && TenderExcelVerifier.VERIFIED.equals(y.verification());
            if (bothVerified) assertEquals(x.value(), y.value(), key);
            if (!java.util.Objects.equals(x.value(), y.value()) || !java.util.Objects.equals(x.verification(), y.verification())) {
                differ.add(key);
                assertFalse(bothVerified, key + " differs yet both claim to be verified");
            }
        }
        assertEquals(TenderExcelVerifier.MISMATCH, fb.get("feeAmount").verification());
        assertEquals(TenderExcelVerifier.UNVERIFIED, fb.get("feeBeneficiaryIfsc").verification());
        assertTrue(differ.containsAll(List.of("feeAmount", "feeBeneficiaryIfsc")), differ::toString);
    }

    // ── no text to check against ─────────────────────────────────────────────

    @Test
    void aScannedPdfStillImportsWithEverythingUnverified() throws Exception {
        ByteArrayOutputStream scan = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            d.save(scan);
        }
        Result r = importFile(celV2(null), scan.toByteArray());
        assertEquals("scanned", r.pdfStatus());
        assertTrue(r.pdfNote().contains("scan"));
        assertEquals(0, r.verifiedCount());
        assertEquals(0, r.mismatchCount());
        r.fields().forEach(f -> assertEquals(TenderExcelVerifier.UNVERIFIED, f.verification(), f.field()));
        assertEquals("59000", byField(r).get("feeAmount").value());
    }

    @Test
    void anImportWithoutAPdfIsRefused() {
        CustomException e = assertThrows(CustomException.class, () -> importFile(celV2(null), null));
        assertTrue(e.getMessage().contains("PDF"));
    }

    // ── sheet helpers ────────────────────────────────────────────────────────

    private static void set(XSSFSheet s, String label, String value, String source) {
        for (Row r : s) {
            if (r.getCell(0) != null && label.equals(r.getCell(0).getStringCellValue())) {
                r.getCell(1).setCellValue(value);
                Cell src = r.getCell(2) == null ? r.createCell(2) : r.getCell(2);
                if (source == null) src.setBlank();
                else src.setCellValue(source);
                return;
            }
        }
        throw new AssertionError("no row " + label + " on " + s.getSheetName());
    }

    private static void row(XSSFSheet s, int rowIndex, Map<String, String> byHeader) {
        Row header = s.getRow(0);
        Row r = s.getRow(rowIndex) == null ? s.createRow(rowIndex) : s.getRow(rowIndex);
        for (Cell h : header) {
            String v = byHeader.get(h.getStringCellValue());
            if (v != null) r.createCell(h.getColumnIndex()).setCellValue(v);
        }
    }
}
