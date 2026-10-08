package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelReader.FieldCell;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Parsed;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Raw;

/**
 * Normalising what an LLM writes into a cell. The cases are the shapes models
 * actually produce for Indian tenders — scale words, Indian digit grouping,
 * day-first dates — plus the ones that must be refused rather than guessed.
 */
class TenderExcelValuesTest {

    /** The vocabularies tenderData.js sends. */
    static TenderExcelOptions options() {
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

    private static String money(String s) {
        Parsed p = TenderExcelValues.money(Raw.text(s));
        return p.failed() ? "ERR" : p.value();
    }

    private static String date(String s) {
        Parsed p = TenderExcelValues.date(Raw.text(s));
        return p.failed() ? "ERR" : p.value();
    }

    @Nested
    class Money {

        @Test
        void scaleWordsBecomePlainRupees() {
            assertEquals("269000000", money("₹26.90 Crore"));
            assertEquals("25000000", money("₹2.5 Cr"));
            assertEquals("2500000", money("25 Lakhs"));
            assertEquals("100000", money("1 Lakh"));
            assertEquals("150000", money("1.5 lacs"));
            assertEquals("10000000", money("Rs. 1 Crore only"));
        }

        @Test
        void indianAndWesternGroupingAndCurrencyMarks() {
            assertEquals("25000000", money("2,50,00,000"));
            assertEquals("25000000", money("25,000,000"));
            assertEquals("126667376", money("Rs.12,66,67,376/-"));
            assertEquals("920000", money("INR 9,20,000.00"));
            assertEquals("68918769.5", money("68918769.50"));
        }

        @Test
        void numericCellsAreReadAsTheyAre() {
            assertEquals("269000000", TenderExcelValues.money(Raw.number(269000000d, false)).value());
            assertEquals("920000", TenderExcelValues.money(Raw.number(920000d, false)).value());
        }

        @Test
        void wordsAndJunkAreRefusedNotGuessed() {
            assertEquals("ERR", money("Twenty five lakh"));
            assertEquals("ERR", money("As per NIT"));
            assertEquals("ERR", money("0"));
            assertEquals("ERR", money("-5000"));
        }

        @Test
        void blankIsBlankNotAnError() {
            Parsed p = TenderExcelValues.money(Raw.text("  "));
            assertNull(p.value());
            assertNull(p.error());
        }
    }

    @Nested
    class Dates {

        @Test
        void textDatesAreDayFirst() {
            assertEquals("2026-07-08", date("08/07/2026"));
            assertEquals("2026-07-08", date("08-07-2026"));
            assertEquals("2026-07-08", date("8.7.2026"));
            assertEquals("2026-07-08", date("2026-07-08"));
            assertEquals("2026-07-08", date("08 Jul 2026"));
            assertEquals("2026-07-08", date("08/07/2026 15:00"));
        }

        @Test
        void excelDateCellsAndSerials() {
            assertEquals("2026-07-08", TenderExcelValues.date(Raw.date(LocalDate.of(2026, 7, 8))).value());
            // 46211 is 2026-07-08 in Excel's 1900 system.
            assertEquals("2026-07-08", TenderExcelValues.date(Raw.number(46211d, false)).value());
        }

        @Test
        void impossibleDatesAreErrors() {
            assertEquals("ERR", date("31/02/2026"));
            assertEquals("ERR", date("13/13/2026"));
            assertEquals("ERR", date("next Monday"));
            assertTrue(TenderExcelValues.date(Raw.number(12d, false)).failed());   // a count, not a serial
        }
    }

    @Nested
    class Percent {

        @Test
        void acceptsPercentSignBareNumberAndPercentFormattedCells() {
            assertEquals("5", TenderExcelValues.percent(Raw.text("5%")).value());
            assertEquals("5", TenderExcelValues.percent(Raw.text("5")).value());
            assertEquals("2.5", TenderExcelValues.percent(Raw.text("2.5 %")).value());
            assertEquals("5", TenderExcelValues.percent(Raw.number(0.05, true)).value());
            assertEquals("3", TenderExcelValues.percent(Raw.number(3d, false)).value());
        }

        @Test
        void wordsAreErrors() {
            assertTrue(TenderExcelValues.percent(Raw.text("five percent")).failed());
        }
    }

    @Nested
    class Choices {

        @Test
        void matchIgnoresCaseAndSpacing() {
            TenderExcelOptions o = options();
            assertEquals("PSU", o.match(TenderExcelSchema.ListName.CLIENT_TYPE, "  psu "));
            assertEquals("Rooftop Solar", o.match(TenderExcelSchema.ListName.SECTOR, "rooftop   SOLAR"));
            assertEquals("IREPS / Railways", o.match(TenderExcelSchema.ListName.SOURCE, "ireps / railways"));
            assertNull(o.match(TenderExcelSchema.ListName.TENDER_TYPE, "Global"));
        }

        @Test
        void operatorsMatchByValueLabelOrSymbol() {
            TenderExcelOptions o = options();
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, "≥ (at least)"));
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, "gte"));
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, "≥"));
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, ">="));
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, "at least"));
            assertEquals("boolean", o.match(TenderExcelSchema.ListName.OPERATOR, "Yes / No"));
            assertNull(o.match(TenderExcelSchema.ListName.OPERATOR, "approximately"));
        }

        /** The exact JSON tenderExcelOptions() sends (OPERATORS are {value, label} objects). */
        @Test
        void optionsDeserialiseFromTheFrontendShape() throws Exception {
            String json = "{\"clientTypes\":[\"Government\",\"PSU\"],\"sectors\":[\"Rooftop Solar\"],"
                    + "\"tenderTypes\":[\"Open\"],\"sources\":[\"GeM\"],\"eligibilityCategories\":[\"Technical\"],"
                    + "\"operators\":[{\"value\":\"gte\",\"label\":\"≥ (at least)\"}],\"extra\":1}";
            TenderExcelOptions o = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, TenderExcelOptions.class);
            assertEquals("PSU", o.match(TenderExcelSchema.ListName.CLIENT_TYPE, "psu"));
            assertEquals("gte", o.match(TenderExcelSchema.ListName.OPERATOR, "at least"));
            // The dropdown offers the bare symbol, which LLMs copy exactly.
            assertEquals(List.of("≥"), o.shown(TenderExcelSchema.ListName.OPERATOR));
        }

        @Test
        void unmatchedChoiceIsLeftBlankWithAWarning() {
            List<TenderExcelImport.FieldResult> r = TenderExcelImport.fields(List.of(
                    new FieldCell("Basic Info", "Sector", "sector", Raw.text("Hydro"))),
                    options(), LocalDate.of(2026, 1, 1));
            assertEquals(1, r.size());
            assertNull(r.get(0).value());
            assertEquals(TenderExcelImport.WARNING, r.get(0).status());
            assertTrue(r.get(0).reason().contains("Hydro"));
        }
    }

    @Nested
    class Rules {

        private Map<String, TenderExcelImport.FieldResult> run(FieldCell... cells) {
            Map<String, TenderExcelImport.FieldResult> m = new java.util.LinkedHashMap<>();
            for (TenderExcelImport.FieldResult f : TenderExcelImport.fields(List.of(cells), options(),
                    LocalDate.of(2026, 1, 1))) {
                m.put(f.field(), f);
            }
            return m;
        }

        private FieldCell cell(String key, String v) {
            return new FieldCell("Dates & Money", key, key, Raw.text(v));
        }

        @Test
        void unreadableMoneyIsAnErrorAndKeepsTheRawText() {
            TenderExcelImport.FieldResult f = run(cell("estimatedValue", "about two crore")).get("estimatedValue");
            assertEquals(TenderExcelImport.ERROR, f.status());
            assertEquals("about two crore", f.raw());
            assertNull(f.value());
        }

        @Test
        void datesOutOfOrderAreAnError() {
            Map<String, TenderExcelImport.FieldResult> r = run(
                    cell("submissionDeadline", "20/08/2026"),
                    cell("technicalOpeningDate", "18/08/2026"));
            assertEquals(TenderExcelImport.OK, r.get("submissionDeadline").status());
            assertEquals(TenderExcelImport.ERROR, r.get("technicalOpeningDate").status());
        }

        @Test
        void emdOutsideTheUsualShareIsOnlyAWarning() {
            Map<String, TenderExcelImport.FieldResult> r = run(
                    cell("estimatedValue", "1 Crore"), cell("emdAmount", "20 Lakhs"));
            assertEquals(TenderExcelImport.WARNING, r.get("emdAmount").status());
            assertEquals("2000000", r.get("emdAmount").value());
        }

        @Test
        void identityNumbersAreShapeChecked() {
            Map<String, TenderExcelImport.FieldResult> r = run(
                    cell("clientGstin", "29aaacg1234f1z5"), cell("clientPan", "AAACG1234"));
            assertEquals(TenderExcelImport.OK, r.get("clientGstin").status());
            assertEquals("29AAACG1234F1Z5", r.get("clientGstin").value());
            assertEquals(TenderExcelImport.ERROR, r.get("clientPan").status());
        }

        @Test
        void financialYearMustAgreeWithTheTenderNumber() {
            Map<String, TenderExcelImport.FieldResult> r = run(
                    cell("tenderNumber", "TREDA/2025-26/EPC/14"), cell("financialYear", "2026-27"));
            assertEquals(TenderExcelImport.ERROR, r.get("financialYear").status());
        }

        @Test
        void aPastDeadlineIsAWarning() {
            TenderExcelImport.FieldResult f = run(cell("submissionDeadline", "2025-03-01")).get("submissionDeadline");
            assertEquals(TenderExcelImport.WARNING, f.status());
        }

        @Test
        void emdOnTwoSheetsMustAgree() {
            List<TenderExcelImport.FieldResult> r = TenderExcelImport.fields(List.of(
                    new FieldCell("Dates & Money", "EMD Amount", "emdAmount", Raw.text("9,20,000")),
                    new FieldCell("EMD Requirements", "EMD Amount", "emdAmount", Raw.number(920000d, false))),
                    options(), LocalDate.of(2026, 1, 1));
            assertEquals(1, r.size());
            assertEquals(TenderExcelImport.OK, r.get(0).status());

            r = TenderExcelImport.fields(List.of(
                    new FieldCell("Dates & Money", "EMD Amount", "emdAmount", Raw.text("9,20,000")),
                    new FieldCell("EMD Requirements", "EMD Amount", "emdAmount", Raw.text("10 Lakh"))),
                    options(), LocalDate.of(2026, 1, 1));
            assertEquals(TenderExcelImport.ERROR, r.get(0).status());
        }

        @Test
        void financialThresholdsInEligibilityBecomeFigures() {
            List<java.util.Map<String, Object>> rows = TenderExcelImport.eligibility(List.of(
                    new TenderExcelReader.TableRow(2, Map.of(
                            "category", Raw.text("financial"),
                            "criterionName", Raw.text("Average annual turnover"),
                            "requiredValue", Raw.text("₹26.90 Crore"),
                            "operator", Raw.text(">=")))), options());
            assertEquals("Financial", rows.get(0).get("category"));
            assertEquals("269000000", rows.get(0).get("requiredValue"));
            assertEquals("gte", rows.get(0).get("operator"));
            // An auto-correction is never silent: original and result side by side.
            assertEquals(TenderExcelImport.WARNING, rows.get(0).get("status"));
            assertTrue(rows.get(0).get("issues").toString().contains("\"₹26.90 Crore\" → 269000000"));
        }

        @Test
        void boqQuantityMustBeANumber() {
            List<java.util.Map<String, Object>> rows = TenderExcelImport.boq(List.of(
                    new TenderExcelReader.TableRow(2, Map.of("description", Raw.text("Module"), "quantity", Raw.text("1,200 Nos"))),
                    new TenderExcelReader.TableRow(3, Map.of("description", Raw.text("Inverter"), "quantity", Raw.text("lot")))));
            assertEquals("1200", rows.get(0).get("quantity"));
            assertEquals(TenderExcelImport.OK, rows.get(0).get("status"));
            assertEquals(TenderExcelImport.ERROR, rows.get(1).get("status"));
            assertEquals(new BigDecimal("1200"), new BigDecimal((String) rows.get(0).get("quantity")));
        }
    }
}
