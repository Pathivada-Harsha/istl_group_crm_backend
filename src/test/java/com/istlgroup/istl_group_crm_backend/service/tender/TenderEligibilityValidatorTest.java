package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The AI's eligibility rows checked against the real KPTCL Gholanoor section.
 *
 * <p>The anchors below are the ones gpt-oss-120b actually returned for this
 * document, including the two shapes that broke the first matcher: an opening
 * the model tidied ("The Bidder should…" for a clause that begins "Bidder
 * should…"), and OR-alternatives that open with the same eight words, where the
 * first prefix match is the wrong sibling.
 */
class TenderEligibilityValidatorTest {

    private static TenderText section;

    @BeforeAll
    static void locate() throws IOException {
        Path pdf = Paths.get("src", "test", "resources", "tender-samples", "kptcl-gholanoor.pdf");
        TenderText cleaned = TenderTextCleaner.clean(TenderText.fromPdf(Files.readAllBytes(pdf)));
        section = TenderSectionLocator.text(cleaned, TenderSectionLocator.locate(cleaned));
    }

    private static Map<String, Object> row(String category, String name, String required, String operator,
                                           String group, String start, String end) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("category", category);
        r.put("criterionName", name);
        r.put("requiredValue", required);
        r.put("operator", operator);
        if (group != null) r.put("altGroup", group);
        r.put("sourceStart", start);
        r.put("sourceEnd", end);
        return r;
    }

    private static final String TAIL = "minimum period of One (1) year prior to the date of submission of the bid";

    private static List<Map<String, Object>> aisAndLineAlternatives() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Technical", "AIS sub-station 66kV+ — nos. in last 10 yrs", "1", "gte", "3.2(b)(i)",
                "The Bidder should have Satisfactorily Constructed, Completed & Commissioned atleast", TAIL));
        // The document says "Bidder should have …" — the model added "The".
        rows.add(row("Technical", "AIS sub-station 33kV+ — nos. in last 10 yrs", "5", "gte", "3.2(b)(i)",
                "The Bidder should have Satisfactorily Constructed, Completed & Commissioned not less "
              + "than Five (5) Nos. of 33kV or above voltage class AIS Sub-Stations", TAIL));
        rows.add(row("Technical", "66kV+ augmentation / bay works — nos. in last 10 yrs", "2", "gte", "3.2(b)(i)",
                "The Bidder should have carried out erection of atleast 02 (Two) Nos. of 66kV",
                "minimum period of One(1) year prior to the date of submission of the Bid"));
        rows.add(row("Technical", "Transmission line 66kV+ — km in last 10 yrs", "1.82", "gte", "3.2(b)(ii)",
                "The Bidder should have satisfactorily Constructed, Completed & Commissioned the work of "
              + "Construction/Strengthening/Re-conductoring of Transmission Line of 66kV",
                "minimum period of One (1) year prior to date of submission of the Bid"));
        rows.add(row("Technical", "Transmission line 33kV+ — km in last 10 yrs", "9.11", "gte", "3.2(b)(ii)",
                "The Bidder Should have Satisfactorily Constructed, Completed & Commissioned the work of "
              + "Construction/Strengthening/Re-conductoring of Transmission Line of 33kV",
                "minimum period of One (1) year prior to date of submission of the Bid"));
        return rows;
    }

    private static Map<String, Object> byName(List<Map<String, Object>> rows, String prefix) {
        return rows.stream().filter(r -> r.get("criterionName").toString().startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + prefix));
    }

    @Test
    void keepsEveryOrAlternativeWithItsOwnClause() {
        var result = TenderEligibilityValidator.validate(aisAndLineAlternatives(), section);
        List<Map<String, Object>> rows = result.criteria();
        assertEquals(5, rows.size(), "discarded: " + result.discarded());
        assertTrue(result.discarded().isEmpty(), "discarded: " + result.discarded());

        assertEquals(3, rows.stream().filter(r -> "3.2(b)(i)".equals(r.get("altGroup"))).count());
        assertEquals(2, rows.stream().filter(r -> "3.2(b)(ii)".equals(r.get("altGroup"))).count());

        // Each alternative is sliced out of its own clause, not a sibling's.
        Map<String, Object> ais33 = byName(rows, "AIS sub-station 33kV+");
        assertEquals("5", ais33.get("requiredValue"));
        assertTrue(ais33.get("clauseText").toString().startsWith("Bidder should have Satisfactorily"));
        assertTrue(ais33.get("clauseText").toString().contains("Five (5) Nos. of 33kV"));
        assertTrue(ais33.get("clauseText").toString().endsWith("submission of the bid"));
        assertEquals(17, ais33.get("sourcePage"));

        Map<String, Object> line66 = byName(rows, "Transmission line 66kV+");
        assertEquals("1.82", line66.get("requiredValue"));
        assertTrue(line66.get("clauseText").toString().contains("atleast 1.82km"));
        assertTrue(!line66.get("clauseText").toString().contains("9.11km"), "ran into the next alternative");
        assertEquals(18, line66.get("sourcePage"));
        assertEquals("9.11", byName(rows, "Transmission line 33kV+").get("requiredValue"));
    }

    @Test
    void readsCroreAmountsAsTheRupeesTheClauseStates() {
        var result = TenderEligibilityValidator.validate(List.of(
                row("Financial", "Minimum financial turnover", "269000000", "gte", null,
                        "Achieved in atleast two financial years in the last five years",
                        "a minimum financial turnover of Rs.26.90Crore"),
                row("Financial", "Liquid assets / credit facility", "50400000", "gte", null,
                        "Liquid Assets and/or availability of credit facilities of not less than",
                        "certificates from banks for meeting the fund requirement etc")), section);
        assertEquals(2, result.criteria().size());
        assertEquals("269000000", result.criteria().get(0).get("requiredValue"));
        assertEquals("50400000", result.criteria().get(1).get("requiredValue"));
        assertEquals(20, result.criteria().get(1).get("sourcePage"));
    }

    @Test
    void blanksAFigureTheClauseDoesNotState() {
        var result = TenderEligibilityValidator.validate(List.of(
                row("Technical", "Transmission line 66kV+ — km in last 10 yrs", "2.5", "gte", null,
                        "The Bidder should have satisfactorily Constructed, Completed & Commissioned the work of "
                      + "Construction/Strengthening/Re-conductoring of Transmission Line of 66kV",
                        "minimum period of One (1) year prior to date of submission of the Bid")), section);
        Map<String, Object> r = result.criteria().get(0);
        assertEquals("", r.get("requiredValue"), "2.5 km is not in the clause — it must not survive");
        assertTrue(r.get("note").toString().contains("2.5"));
        assertEquals(1, result.discarded().size());
    }

    @Test
    void dropsACriterionWhoseClauseIsNotInTheSection() {
        var result = TenderEligibilityValidator.validate(List.of(
                row("Technical", "ISO 9001 certification", "", "boolean", null,
                        "The bidder shall possess a valid ISO 9001:2015 certificate",
                        "valid on the date of bid submission")), section);
        assertTrue(result.criteria().isEmpty());
        assertEquals(1, result.discarded().size());
    }

    /**
     * TREDA's CRITERIA | DOCUMENTS table interleaves its two columns word by
     * word in the text layer, and splits "Rs. 6.34" from its "crore". The AI's
     * clean quote must still anchor, and the crore figure must still verify.
     */
    @Test
    void anchorsAClauseWhoseColumnsAreInterleaved() throws IOException {
        Path pdf = Paths.get("src", "test", "resources", "tender-samples", "treda-tripura.pdf");
        TenderText cleaned = TenderTextCleaner.clean(TenderText.fromPdf(Files.readAllBytes(pdf)));
        TenderText treda = TenderSectionLocator.text(cleaned, TenderSectionLocator.locate(cleaned));

        var result = TenderEligibilityValidator.validate(List.of(
                row("Financial", "Minimum Average Annual Turnover (MAAT)", "63400000", "gte", null,
                        "Minimum Average Annual Turnover (MAAT) of the bidder in the",
                        "minimum Rs. 6.34 crore (Rupees six crore thirty four lakh)"),
                row("Technical", "Two LoAs — 363 kWp each", "363", "gte", null,
                        "TWO (2) Letter of Awards (LoAs) & Commissioning Certificate /",
                        "aggregated capacity of 363 kWp each.")), treda);

        assertTrue(result.discarded().isEmpty(), "discarded: " + result.discarded());
        assertEquals("63400000", result.criteria().get(0).get("requiredValue"));
        assertEquals(24, result.criteria().get(0).get("sourcePage"));
        assertEquals("363", result.criteria().get(1).get("requiredValue"));
    }

    @Test
    void aGroupOfOneIsNotAChoice() {
        var result = TenderEligibilityValidator.validate(List.of(
                row("Financial", "Liquid assets / credit facility", "50400000", "gte", "3.3(b)",
                        "Liquid Assets and/or availability of credit facilities of not less than",
                        "certificates from banks for meeting the fund requirement etc")), section);
        assertNull(result.criteria().get(0).get("altGroup"));
    }

    @Test
    void booleanConditionsCarryNoFigureAndUnknownLabelsAreNormalised() {
        var result = TenderEligibilityValidator.validate(List.of(
                row("Compliance", "Not blacklisted in the past five years", "5", "yes-no", null,
                        "The Tenderer/Bidder and consortium partners should not have been Blacklisted",
                        "in the past five years as on date of Bid opening")), section);
        Map<String, Object> r = result.criteria().get(0);
        assertEquals("General", r.get("category"));
        assertEquals("contains", r.get("operator"));
        assertEquals(15, r.get("sourcePage"));
    }
}
