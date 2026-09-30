package com.istlgroup.istl_group_crm_backend.service.tender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The AI's BOQ and documents checklist, verified against the real PDFs: TREDA's
 * NIC item-wise schedule and KPTCL's clause 3.10 upload list. The rows below
 * reproduce what gpt-oss-120b returned for these documents, plus the failures
 * the checks exist for.
 */
class TenderBoqAndDocumentsValidatorTest {

    private static TenderText treda;
    private static TenderBoqLocator.Result tredaBoq;
    private static TenderText kptclDocuments;

    @BeforeAll
    static void load() throws IOException {
        TenderText t = clean("treda-tripura.pdf");
        tredaBoq = TenderBoqLocator.locate(t);
        treda = TenderBoqLocator.text(t, tredaBoq);
        TenderText k = clean("kptcl-gholanoor.pdf");
        kptclDocuments = TenderSectionLocator.text(k, TenderSectionLocator.locate(k, TenderSectionLocator.DOCUMENTS));
    }

    private static TenderText clean(String name) throws IOException {
        byte[] pdf = Files.readAllBytes(Paths.get("src", "test", "resources", "tender-samples", name));
        return TenderTextCleaner.clean(TenderText.fromPdf(pdf));
    }

    private static Map<String, Object> boq(String item, String qty, String unit, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemNo", item);
        m.put("quantity", qty);
        m.put("unit", unit);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> doc(String name, String start, String end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentName", name);
        m.put("sourceStart", start);
        m.put("sourceEnd", end);
        return m;
    }

    // ── BOQ ──────────────────────────────────────────────────────────────────

    @Test
    void takesTheAiDescriptionWhenItemAndQuantityMatchTheScheduleLine() {
        var result = TenderBoqValidator.validate(List.of(
                boq("5.1", "130500", "Meters",
                        "Wiring for Circuit/Sub‑main — 2 x 2.5 sq.mm PVC insulated copper "
                      + "conductor cable (FR) in PVC casing and capping etc. as required")),
                tredaBoq.rows(), treda);

        assertEquals(27, result.rows().size(), "every schedule row is kept, matched or not");
        Map<String, Object> r = result.rows().stream().filter(x -> "5.1".equals(x.get("itemNo"))).findFirst().orElseThrow();
        assertEquals("130500", r.get("quantity"));
        assertEquals("Meters", r.get("unit"));
        assertTrue(r.get("description").toString().startsWith("Wiring for Circuit/Sub-main"),
                "AI description, with its typography folded: " + r.get("description"));
        assertEquals(103, r.get("page"));
    }

    @Test
    void dropsAnAiRowWithAQuantityTheScheduleDoesNotHave() {
        var result = TenderBoqValidator.validate(List.of(
                boq("5.1", "13050", "Meters", "Wiring for circuit 2 x 2.5 sq. mm")), tredaBoq.rows(), treda);
        assertEquals(1, result.discarded().size());
        Map<String, Object> r = result.rows().stream().filter(x -> "5.1".equals(x.get("itemNo"))).findFirst().orElseThrow();
        assertEquals("130500", r.get("quantity"), "the schedule's figure, never the AI's");
    }

    @Test
    void keepsTheLayoutDescriptionWhenTheAiInventsWording() {
        var result = TenderBoqValidator.validate(List.of(
                boq("14", "435", "Meters", "Premium fibre-optic backbone cabling with lifetime vendor warranty")),
                tredaBoq.rows(), treda);
        Map<String, Object> r = result.rows().stream().filter(x -> "14".equals(x.get("itemNo"))).findFirst().orElseThrow();
        assertFalse(r.get("description").toString().contains("fibre-optic"));
    }

    @Test
    void theEnvelopeLabelIsNotAScope() {
        assertTrue(tredaBoq.rows().stream().noneMatch(r -> r.scope().toUpperCase().contains("PRICE BID")));
    }

    // ── documents ────────────────────────────────────────────────────────────

    @Test
    void keepsAnchoredDocumentsWithTheirClauseAndPage() {
        var result = TenderDocumentsValidator.validate(List.of(
                doc("Bank Guarantee / Insurance Surety Bond towards Bid Security (EMD)",
                        "i. Bank Guarantee/Insurance Surety Bond towards Bid Security (EMD) shall be furnished",
                        "uploaded in accordance with Cl. No. 13 of ITT."),
                doc("No Deviation Certificate", "xvi. No Deviation Certificate.", "No Deviation Certificate."),
                doc("Consortium Agreement (if consortium)", "viii. Consortium Agreement (In case of Consortium).",
                        "Consortium Agreement (In case of Consortium).")),
                kptclDocuments);
        assertEquals(3, result.documents().size(), "discarded: " + result.discarded());
        Map<String, Object> emd = result.documents().get(0);
        assertEquals(23, emd.get("sourcePage"));
        assertTrue(emd.get("notes").toString().startsWith("p.23 — "));
        assertTrue(emd.get("notes").toString().contains("Bid Security (EMD)"));
    }

    @Test
    void dropsADocumentTheTenderNeverAsksFor() {
        var result = TenderDocumentsValidator.validate(List.of(
                doc("MSME / Udyam registration certificate",
                        "The bidder shall upload a valid Udyam registration certificate issued by",
                        "the Ministry of Micro, Small and Medium Enterprises.")),
                kptclDocuments);
        assertTrue(result.documents().isEmpty());
        assertEquals(1, result.discarded().size());
    }

    @Test
    void theSameDocumentTwiceIsOneRow() {
        var d = doc("No Deviation Certificate", "xvi. No Deviation Certificate.", "No Deviation Certificate.");
        assertEquals(1, TenderDocumentsValidator.validate(List.of(d, d), kptclDocuments).documents().size());
    }
}
