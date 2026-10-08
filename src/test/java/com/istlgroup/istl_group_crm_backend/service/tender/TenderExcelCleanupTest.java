package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.istlgroup.istl_group_crm_backend.service.tender.RequiredValueCleaner.Cleaned;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Raw;

/**
 * Operator values and Required Values, written the many ways different LLMs
 * write them, come out as one stored shape — and every change is reported.
 */
class TenderExcelCleanupTest {

    private static final TenderExcelOptions O = TenderExcelValuesTest.options();

    @Nested
    class Operators {

        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource(delimiter = '|', value = {
                "≥|gte", ">=|gte", "at least|gte", "At Least|gte", "minimum|gte", "Not less than|gte",
                "NOT  LESS  THAN|gte", "more than|gte", "Min.|gte",
                "≤|lte", "<=|lte", "at most|lte", "Maximum|lte", "not more than|lte", "less than|lte",
                "=|eq", "equals|eq", "Exactly|eq",
                "contains|contains", "Class|contains", "grade|contains",
                "yes/no|boolean", "Yes / No|boolean", "YES|boolean", "required|boolean", "Mandatory|boolean",
                "≥ (at least)|gte", "gte|gte"})
        void everyCommonWayOfWritingAnOperatorMaps(String written, String code) {
            assertEquals(code, O.match(TenderExcelSchema.ListName.OPERATOR, written));
        }

        @Test
        void theDropdownOffersShortPlainValues() {
            assertEquals(List.of("≥", "≤", "=", "contains", "yes/no"), O.shown(TenderExcelSchema.ListName.OPERATOR));
        }

        @Test
        void anUnknownOperatorIsBlankedWithAWarningNeverSilently() {
            Map<String, Object> row = TenderExcelImport.eligibility(List.of(new TenderExcelReader.TableRow(2, Map.of(
                    "criterionName", Raw.text("Turnover"),
                    "requiredValue", Raw.text("5000000"),
                    "operator", Raw.text("approximately")))), O).get(0);
            assertEquals("", row.get("operator"));
            assertEquals(TenderExcelImport.WARNING, row.get("status"));
            assertTrue(row.get("issues").toString().contains("\"approximately\""));
        }

        @Test
        void yesNoListReadsTheUsualWords() {
            assertEquals("No", O.match(TenderExcelSchema.ListName.YES_NO, "Non-refundable"));
            assertEquals("Yes", O.match(TenderExcelSchema.ListName.YES_NO, "yes"));
            assertNull(O.match(TenderExcelSchema.ListName.YES_NO, "maybe"));
        }
    }

    @Nested
    class RequiredValues {

        private Cleaned clean(String value, String op, String criterion) {
            return RequiredValueCleaner.clean(Raw.text(value), op, criterion, code -> code);
        }

        @Test
        void aFigureWithWordsKeepsTheFigureAndMovesTheWords() {
            Cleaned c = clean("More than 10 MWp in SPV Power Plants", "", "Category A – Solar EPC experience");
            assertEquals("10", c.requiredValue());
            assertEquals("gte", c.operator(), "operator read from \"More than\"");
            assertEquals("Category A – Solar EPC experience (10 MWp in SPV Power Plants)", c.criterionName());
            assertEquals(1, c.warnings().size());
            assertTrue(c.warnings().get(0).startsWith("Required Value \"More than 10 MWp in SPV Power Plants\" → 10"));
        }

        @Test
        void anOperatorAlreadyGivenIsNotOverwritten() {
            Cleaned c = clean("At least 1 year", "eq", "Satisfactory discharge of AMC obligation");
            assertEquals("1", c.requiredValue());
            assertEquals("eq", c.operator());
        }

        @Test
        void wordsTheCriterionAlreadySaysAreNotRepeated() {
            Cleaned c = clean("At least 1 year", "", "AMC obligation of at least 1 year");
            assertEquals("1", c.requiredValue());
            assertEquals("gte", c.operator());
            assertEquals("AMC obligation of at least 1 year", c.criterionName());
        }

        @Test
        void twoFiguresAreFlaggedNotSilentlyHalved() {
            Cleaned c = clean("More than 10 MWp and 20 projects", "", "Experience");
            assertEquals("10", c.requiredValue());
            assertTrue(c.warnings().get(0).contains("more than one figure (10, 20)"));
        }

        @Test
        void requiredIsAYesNoCondition() {
            Cleaned c = clean("Required", "", "Consortium agreement");
            assertEquals("", c.requiredValue());
            assertEquals("boolean", c.operator());
            assertTrue(c.warnings().get(0).contains("\"Required\" → blank"));
            // Exported by the app itself as yes/no: left exactly as it was.
            Cleaned own = clean("yes", "boolean", "Not blacklisted");
            assertEquals("yes", own.requiredValue());
            assertTrue(own.warnings().isEmpty());
        }

        @Test
        void moneyInWordsBecomesRupees() {
            assertEquals("1000000000", clean("Rs. 100 Cr", "gte", "Turnover").requiredValue());
            assertEquals("5000000", clean("50 Lakhs", "gte", "Net worth").requiredValue());
            Cleaned c = clean("More than Rs. 50 Cr", "", "Turnover");
            assertEquals("500000000", c.requiredValue());
            assertEquals("gte", c.operator());
            assertEquals(1, c.warnings().size());
        }

        @Test
        void groupingCommasAreDroppedWithAWarning() {
            Cleaned c = clean("1,00,00,000", "gte", "Turnover");
            assertEquals("10000000", c.requiredValue());
            assertEquals(1, c.warnings().size());
            assertTrue(clean("10000000", "gte", "Turnover").warnings().isEmpty());
            assertEquals("1000000000", RequiredValueCleaner.clean(Raw.number(1e9, false), "gte", "T", x -> x).requiredValue());
        }

        @Test
        void plainTextIsLeftAlone() {
            for (String s : List.of("ISO 9001:2015", "Class I", "As per NIT")) {
                Cleaned c = clean(s, "contains", "Certification");
                assertEquals(s, c.requiredValue());
                assertTrue(c.warnings().isEmpty(), s);
            }
        }

        @Test
        void aRowOfTheCelTenderReadsAsMeant() {
            Map<String, Object> row = TenderExcelImport.eligibility(List.of(new TenderExcelReader.TableRow(7, Map.of(
                    "category", Raw.text("Technical"),
                    "criterionName", Raw.text("Category A – Solar EPC experience"),
                    "requiredValue", Raw.text("More than 10 MWp in SPV Power Plants"),
                    "tier", Raw.text("Category A"),
                    "altGroup", Raw.text("A1")))), O).get(0);
            assertEquals("10", row.get("requiredValue"));
            assertEquals("gte", row.get("operator"));
            assertEquals("Category A", row.get("tier"));
            assertEquals(TenderExcelImport.WARNING, row.get("status"));
            assertTrue(row.get("issues").toString().contains("operator ≥ from \"more than\""), row.get("issues").toString());
        }
    }
}
