package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Regex extractor tests over the exact wording of real NIT documents — no PDF,
 * no Spring context: the text below is what PDFBox produces for the summary
 * pages, so a template that parses here parses the file.
 *
 * <p>The KPTCL sample is the one that used to fail two ways: its Lakh/Crore
 * amounts came out unscaled and its 440-character work title overflowed
 * {@code tender_name}.
 */
class TenderPdfExtractorTest {

    private final TenderPdfExtractor extractor = new TenderPdfExtractor();

    /** Cover + IFT summary of the KPTCL Gholanoor sub-station tender. */
    private static final String KPTCL = String.join("\n",
        "110kV S/s at Gholnoor (WI3689) Page 1 of 205",
        "KARNATAKA POWER TRANSMISSION CORPORATION LIMITED",
        "(Corporate Identity Number (CIN): U40109KA1999SGC025521)",
        "Tender Inviting Authority : Chief Engineer, Electricity, Tendering & Procurement, KPTCL",
        "Procurement Entity : Karnataka Power Transmission Corporation Limited",
        "Address : Chief Engineer, Electricity, Tendering & Procurement, KPTCL, 9th Floor,",
        "Indhana Bhavana, Bengaluru- 560009.",
        "Telephone No's :",
        "Email ID : ceetnp@kptcl.org",
        "TENDER FOR THE WORK OF",
        "Establishing 2x20MVA, 110/11kV Sub-Station at Gholanoor and construction of 110kV LILO",
        "line using Lynx conductor from existing 110kV Afzalpur-Chowdapur SC line to the proposed",
        "110/11kV sub-station at Gholanoor for a distance of about 7.289kms in Afzalpur Taluk,",
        "Kalaburagi District on “Partial Turnkey Basis” including Supply of all matching",
        "Materials/Equipments (Excluding Supply 11kV Switchgear) and Erection (Including",
        "Dismantling and Civil Works) of all Materials/ Equipment, Testing and Commissioning.",
        "Tender Reference /Bid Enquiry No. : KPTCL/2026-27/SS/WORK_INDENT3689",
        "Availability of Tender Documents In KPP Portal : Refer Tender Notification (NIT) & KPP Portal",
        "Amount Put To Tender, excluding GST : Rs.2017.56Lakhs",
        "EMD/Bid security : Rs.20.18Lakhs",
        "Validity of Tender : 180 Days from the Last date of bid submission as notified.",
        "Completion Period : 18 (Eighteen) Months from the date of Letter of Intent",
        "The Bid Notification, Bidding Documents along with Drawings are available on website",
        "https://kptcl.karnataka.gov.in with hyperlink or https://kppp.karnataka.gov.in.",
        "a minimum financial turnover of Rs.26.90Crore.",
        "Liquid Assets and/or availability of credit facilities of not less than Rs.5.04Crore",
        "the successful Tenderer shall deliver to the Employer a Security Deposit equivalent to",
        "5% of Amount Put to tender (Excluding GST) plus additional security.");

    // ── the two bugs the KPTCL file exposed ──────────────────────────────────

    @Test
    void scalesLakhAndCroreToPlainRupees() {
        Map<String, Object> f = extractor.extractFromText(KPTCL);
        assertEquals("201756000", f.get("estimatedValue"), "Rs.2017.56Lakhs");
        assertEquals("2018000", f.get("emdAmount"), "Rs.20.18Lakhs");
    }

    @Test
    void capturesTheMultiLineWorkTitle() {
        String name = (String) extractor.extractFromText(KPTCL).get("tenderName");
        // Spans six lines of the cover page, well past the old varchar(255).
        assertTrue(name.startsWith("Establishing 2x20MVA, 110/11kV Sub-Station at Gholanoor "
                + "and construction of 110kV LILO line using Lynx conductor"), name);
        assertTrue(name.contains("Afzalpur Taluk"), name);
        // Longer than the old column allowed, but capped to stay a title.
        assertTrue(name.length() > 255 && name.length() <= 300, "length=" + name.length());
    }

    // ── the rest of the KPTCL summary ────────────────────────────────────────

    @Test
    void readsKptclIdentityAndParties() {
        Map<String, Object> f = extractor.extractFromText(KPTCL);
        // "Bid Enquiry No." sits inside "Tender Reference /Bid Enquiry No." — the
        // digit check stops "Bid" being taken as the reference.
        assertEquals("KPTCL/2026-27/SS/WORK_INDENT3689", f.get("tenderNumber"));
        assertEquals("Chief Engineer, Electricity, Tendering & Procurement, KPTCL",
                f.get("issuingAuthority"));
        assertEquals("Karnataka Power Transmission Corporation Limited", f.get("clientCompany"));
        assertEquals("PSU", f.get("clientType"));
        assertEquals("U40109KA1999SGC025521", f.get("clientCin"));
        assertEquals("ceetnp@kptcl.org", f.get("clientContactEmail"));
    }

    @Test
    void readsKptclClassificationAndLocation() {
        Map<String, Object> f = extractor.extractFromText(KPTCL);
        assertEquals("Karnataka", f.get("state"));
        assertEquals("Kalaburagi", f.get("district"));
        assertEquals("Afzalpur", f.get("location"));
        assertEquals("Bengaluru", f.get("clientCity"));
        assertEquals("Electrical", f.get("sector"));
        assertEquals("State Portal", f.get("source"));
        assertEquals("2026-27", f.get("financialYear"));
        assertEquals("5", f.get("performanceSecurityPct"));
    }

    @Test
    void readsKptclEligibilityWithScaledAmounts() {
        @SuppressWarnings("unchecked")
        var criteria = (java.util.List<Map<String, Object>>)
                extractor.extractFromText(KPTCL).get("eligibilityCriteria");
        assertTrue(criteria.stream().anyMatch(c ->
                "269000000".equals(c.get("requiredValue"))), "turnover Rs.26.90Crore");
        assertTrue(criteria.stream().anyMatch(c ->
                "50400000".equals(c.get("requiredValue"))), "liquid assets Rs.5.04Crore");
    }

    // ── the previously-supported template must keep working ──────────────────

    @Test
    void stillReadsPlainRupeeCppTemplate() {
        String cppp = String.join("\n",
            "Tender No.: DPA/ELEC/2025/17",
            "Name of Work : Design, supply and installation of 5 MW rooftop solar PV plant",
            "Tender Type : Open Tender",
            "Tender Inviting Authority : Chief Engineer, Deendayal Port Authority",
            "Estimated Cost : Rs. 11,28,82,269",
            "Earnest Money Deposit (EMD) : Rs. 22,57,645",
            "Bid Submission End Date : 15.01.2026 at 15:00 hrs",
            "Bid Opening Date : 16.01.2026",
            "Tender Portal : https://eprocure.gov.in/eprocure/app");

        Map<String, Object> f = extractor.extractFromText(cppp);
        assertEquals("DPA/ELEC/2025/17", f.get("tenderNumber"));
        assertEquals("112882269", f.get("estimatedValue"));
        assertEquals("2257645", f.get("emdAmount"));
        assertEquals("2026-01-15", f.get("submissionDeadline"));
        assertEquals("2026-01-16", f.get("technicalOpeningDate"));
        assertEquals("2025-26", f.get("financialYear"));
        assertEquals("Open", f.get("tenderType"));
        assertEquals("CPPP", f.get("source"));
        assertEquals("Rooftop Solar", f.get("sector"));
    }

    // ── value grammars shared by every template ──────────────────────────────

    @Test
    void readsTheDateSpellingsTendersUse() {
        assertEquals("2026-01-15", submissionOf("Last Date for submission of Tenders: 15-Jan-2026"));
        assertEquals("2026-01-15", submissionOf("Last date of bid submission : 15 January 2026"));
        assertEquals("2026-01-15", submissionOf("Closing Date - January 15, 2026"));
        assertEquals("2026-01-15", submissionOf("Due date for submission 2026-01-15"));
        assertEquals("2026-01-15", submissionOf("Bid Submission Closing Date 15/01/2026"));
    }

    @Test
    void honoursAnInLakhsColumnHeader() {
        // The figure carries no unit of its own; the header between label and
        // number supplies the scale.
        Map<String, Object> f = extractor.extractFromText(
                "Amount Put To Tender (Rs. in lakhs) excluding GST 2017.56");
        assertEquals("201756000", f.get("estimatedValue"));
    }

    @Test
    void ignoresPageNumbersWhenHuntingForAnAmount() {
        // "Page 3 of 205" sits between the label and the real figure; a bare
        // number under Rs.1000 is rejected so the scan continues.
        Map<String, Object> f = extractor.extractFromText(
                "Estimated Cost Page 3 of 205 Rs. 45,00,000");
        assertEquals("4500000", f.get("estimatedValue"));
    }

    @Test
    void omitsFieldsTheDocumentDoesNotState() {
        // KPTCL defers every date to the portal — better an empty field than a
        // wrong one scraped from unrelated text.
        Map<String, Object> f = extractor.extractFromText(KPTCL);
        assertNull(f.get("submissionDeadline"));
        assertNull(f.get("technicalOpeningDate"));
        assertNull(f.get("financialOpeningDate"));
    }

    @Test
    void survivesNonBreakingSpacesAndCurlyPunctuation() {
        // PDFBox routinely emits NBSP inside labels and curly quotes in titles.
        Map<String, Object> f = extractor.extractFromText(
                "Tender No. : ABC/2026/99 "
              + "Estimated Cost : Rs. 12.50 Crore");
        assertEquals("ABC/2026/99", f.get("tenderNumber"));
        assertEquals("125000000", f.get("estimatedValue"));
    }

    @Test
    void readsBoqRowsAcrossUnitVocabularies() {
        String boq = String.join("\n",
            "Part - A: Supply of Materials",
            "1 Supply of 110kV SF6 Circuit Breaker Nos 6",
            "2 Supply of ACSR Lynx conductor Km 7.289",
            "3 Construction of control room building Sqm 240",
            "Section-9 Bill of Quantities .......... 204",
            "4 Comprehensive annual maintenance Month 60");

        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Map<String, Object>>)
                extractor.extractFromText(boq).get("boqItems");
        assertEquals(4, rows.size(), "the dotted table-of-contents row must not count");
        assertEquals("Supply of Materials", rows.get(0).get("scope"));
        assertEquals("Nos", rows.get(0).get("unit"));
        assertEquals("6", rows.get(0).get("quantity"));
        assertEquals("7.289", rows.get(1).get("quantity"));
        assertEquals("Month", rows.get(3).get("unit"));
    }

    // ── title tidy-up: cover pages run the work into the agency's letterhead ──

    /** Exactly what the LLM handed back for the TREDA Tripura solar tender. */
    private static final String TRIPURA_COVER =
        "SPV POWER PLANTS AT BLOCK OFFICES IN TRIPURA DETAILED e-TENDER DOCUMENT FOR Design, "
      + "survey, manufacture, supply, erection, testing, commissioning of 10 kWp & 5 kWp Solar "
      + "Photovoltaic Power Plant (Hybrid) at Block Offices etc. including Warranty /Guarantee, "
      + "Annual Maintenance Contract and insurance coverage for 5 (five) years on turn-key basis "
      + "throughout the state of Tripura DNIe-T No.F.6 (542)/TREDA/NCES/2025-26/2110, dated "
      + "12/06/2026 TRIPURA RENEWABLE ENERGY DEVELOPMENT AGENCY (Department of Power, Government "
      + "of Tripura) Vigyan Bhawan, Pandit Nehru Complex, Gorkhabasti, Agartala, Tripura "
      + "Tele-fax: 0381-2326139, email-tredatenders25@gmail.com URL: www.treda.tripura.gov.in "
      + "SIGNATURE OF THE BIDDER WITH SEAL & DATE";

    @Test
    void dropsTheLetterheadThatFollowsTheWorkDescription() {
        String t = TenderPdfExtractor.tidyTenderName(TRIPURA_COVER);
        assertTrue(t.startsWith("SPV POWER PLANTS AT BLOCK OFFICES IN TRIPURA DETAILED e-TENDER "
                + "DOCUMENT FOR Design, survey, manufacture, supply, erection, testing, "
                + "commissioning of 10 kWp & 5 kWp Solar Photovoltaic Power Plant (Hybrid)"), t);
        // Everything from the DNIT reference onward is paperwork, not the work.
        assertFalse(t.contains("DNIe-T"), t);
        assertFalse(t.contains("TRIPURA RENEWABLE ENERGY DEVELOPMENT AGENCY"), t);
        assertFalse(t.contains("Gorkhabasti"), t);
        assertFalse(t.contains("Tele-fax"), t);
        assertFalse(t.contains("gmail.com"), t);
        assertFalse(t.contains("www."), t);
        assertFalse(t.contains("SIGNATURE OF THE BIDDER"), t);
    }

    @Test
    void capsTheTitleOnAClauseBoundary() {
        String t = TenderPdfExtractor.tidyTenderName(TRIPURA_COVER);
        assertTrue(t.length() <= 300, "length=" + t.length());
        // Cut cleanly, not mid-word and not on dangling punctuation.
        assertFalse(t.endsWith(","), t);
        assertFalse(t.endsWith("-"), t);
        assertTrue(t.matches(".*[A-Za-z0-9)]$"), t);
    }

    @Test
    void keepsAShortTitleExactlyAsWritten() {
        String plain = "Supply and installation of 5 MW ground mounted solar PV plant at Bhuj";
        assertEquals(plain, TenderPdfExtractor.tidyTenderName(plain));
    }

    @Test
    void doesNotTrimATitleThatOpensWithItsReference() {
        // The cut only applies once a real title is in hand, so a document that
        // leads with the reference isn't reduced to nothing.
        String s = "NIT No. 42/2026 Construction of 33/11kV substation at Anand";
        assertEquals(s, TenderPdfExtractor.tidyTenderName(s));
    }

    @Test
    void tidiesTheKptclTitleItAlreadyHandled() {
        // The KPTCL title is pure work description — it must survive untouched
        // apart from being capped.
        String t = (String) extractor.extractFromText(KPTCL).get("tenderName");
        assertTrue(t.startsWith("Establishing 2x20MVA, 110/11kV Sub-Station at Gholanoor"), t);
        assertTrue(t.length() <= 300, "length=" + t.length());
        assertFalse(t.contains("Tender Reference"), t);
    }

    private String submissionOf(String text) {
        return (String) extractor.extractFromText(text).get("submissionDeadline");
    }
}
