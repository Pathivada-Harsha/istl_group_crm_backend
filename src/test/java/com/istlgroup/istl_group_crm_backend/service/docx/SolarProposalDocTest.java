package com.istlgroup.istl_group_crm_backend.service.docx;

import com.istlgroup.istl_group_crm_backend.service.SolarProposalFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.service.SolarProposalDocService;

/**
 * Fills the skeleton with the exact content of the two signed-off reference
 * proposals and checks the result reads back the same — the residential one with
 * Subsidy + ROI, the commercial one with neither.
 *
 * <p>Pure: no Spring, no DB. Writes the rendered files to
 * {@code target/proposal-test-output/} so they can be opened and eyeballed.
 */
class SolarProposalDocTest {

    private static final String TEMPLATE = "proposal-templates/solar-proposal-template.docx";
    private static final Path OUT_DIR = Path.of("target", "proposal-test-output");

    // ── Reference: Ashok Erugurala, 4 kWp residential ────────────────────────

    @Test
    void reproducesTheResidentialReference() throws Exception {
        Map<String, String> t = baseTokens();
        t.put("DOC_TITLE", "Ashok Erugurala 4 kWp Proposal");
        t.put("COVER_TITLE", "Ashok Erugurala Residential");
        t.put("COVER_SUBTITLE", "4 kWp Ongrid Rooftop Solar Plant");
        t.put("CLIENT_NAME", "Ashok Erugurala");
        t.put("SITE_LOCATION", "Godavarikhani, Telangana");
        t.put("CAPACITY_LABEL", "4 kWp");
        t.put("CAPACITY_LINE", "4 kWp On grid Rooftop Solar Plant");
        t.put("WORK_DESC", "4 kWp Rooftop Solar PV Plant with DCR panels");
        t.put("PRICE_BASE", "₹2,32,747");
        t.put("GST_PCT", "8.9%");
        t.put("GST_AMOUNT", "₹20,714");
        t.put("GST_AMOUNT_ROUNDED", "₹20,714");
        t.put("PRICE_TOTAL", "₹2,53,461");
        t.put("AMOUNT_WORDS", "Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only.");
        t.put("QUOTE_VALID_DAYS", "10");
        t.put("QUOTE_VALID_DAY", "Wednesday");
        t.put("QUOTE_VALID_DATE", "29-07-26");
        t.put("SUBSIDY_LINE", "Central Government Subsidy Applicable: ₹78,000");
        t.put("ROI_TITLE_CAP", "4 kWp");
        t.put("ROI_CAPACITY", "4 kWp");
        t.put("ROI_TARIFF", "₹9.25 / Unit");
        t.put("ROI_SPECIFIC_GEN", "1,460 Units/kWp/Year");
        t.put("ROI_ANNUAL_GEN", "5,840 Units");
        t.put("ROI_ANNUAL_SAVINGS", "₹54,020");
        t.put("ROI_MONTHLY_SAVINGS", "₹4,502");
        t.put("ROI_PAYBACK", "4.7 Years");
        t.put("ROI_ANNUAL_ROI", "21.31%");
        t.put("ROI_LIFE", "25+ Years");
        t.put("ROI_LIFETIME_GEN", "1,46,000 Units");
        t.put("ROI_LIFETIME_SAVINGS", "₹13,50,500");
        t.put("ROI_NET_BENEFIT", "₹10,97,039");

        byte[] docx = DocxTemplate.fill(template(), t,
                Map.of("SUBSIDY", true, "ROI", true),
                Map.of("BOM", residentialBom()));
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve("residential.docx"), docx);

        String text = documentText(docx);

        // Boilerplate that must be identical on every proposal
        assertTrue(text.contains("Our Company Overview"));
        assertTrue(text.contains("Sesola has cumulatively completed over 300 MW"));
        assertTrue(text.contains("Our Expertise"));
        assertTrue(text.contains("EPC Capabilities"));
        assertTrue(text.contains("Innovation & R&D"));
        assertTrue(text.contains("Global Presence"));
        assertTrue(text.contains("Safety & Quality Commitment"));
        assertTrue(text.contains("How Net Metering Works"));
        assertTrue(text.contains("Solar Modules: 25 years"));
        assertTrue(text.contains("Inverter: 8 years"));
        assertTrue(text.contains("Executive Manager – Procurement"));
        assertTrue(text.contains("Sudhakar G"));

        // Variable regions
        assertTrue(text.contains("Ashok Erugurala Residential"));
        assertTrue(text.contains("Godavarikhani, Telangana"));
        assertTrue(text.contains("Price for 4 kWp Plant"));
        assertTrue(text.contains("₹2,53,461"));
        assertTrue(text.contains("Amount in Words: Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only."));
        assertTrue(text.contains("Quote valid for 10 days from Wednesday (29-07-26)."));

        // Conditional sections present
        assertTrue(text.contains("Subsidy Details"));
        assertTrue(text.contains("Central Government Subsidy Applicable: ₹78,000"));
        assertTrue(text.contains("ROI Analysis"));
        assertTrue(text.contains("Net Lifetime Financial Benefit"));
        assertTrue(text.contains("₹10,97,039"));

        // BOM rows, in order, from the lead's tab
        assertTrue(text.contains("POLYCAB"));
        assertTrue(text.contains("Govt.Approved ALMM DCR Panels"));
        assertTrue(text.contains("45*45 PVC Trunking Profile"));
        assertEquals(19, countRows(docx), "one BOM row per supplied line");

        assertFalse(text.contains("{{"), "no unresolved tokens");
        assertMediaIntact(docx);
    }

    // ── Reference: LKR Convention Center, 50 kWp commercial ──────────────────

    @Test
    void reproducesTheCommercialReferenceWithoutSubsidyOrRoi() throws Exception {
        Map<String, String> t = baseTokens();
        t.put("DOC_TITLE", "LKR Convention Center 50 kWp Proposal");
        t.put("COVER_TITLE", "LKR Convention Center Commercial");
        t.put("COVER_SUBTITLE", "50 kWp Ongrid Rooftop Solar Plant");
        t.put("CLIENT_NAME", "Kalyan Reddy");
        t.put("SITE_LOCATION", "AndhraPradesh");
        t.put("CAPACITY_LABEL", "50 kWp");
        t.put("CAPACITY_LINE", "50 kWp On grid Rooftop Solar Plant");
        t.put("WORK_DESC", "50 kWp Rooftop Solar PV Plant with DCR panels");
        t.put("PRICE_BASE", "₹26,07,897");
        t.put("GST_PCT", "8.9%");
        t.put("GST_AMOUNT", "₹2,32,103");
        t.put("PRICE_TOTAL", "₹28,40,000");
        t.put("AMOUNT_WORDS", "Rupees Twenty Eight Lakhs Forty Thousand Only.");
        t.put("QUOTE_VALID_DAYS", "10");
        t.put("QUOTE_VALID_DAY", "Friday");
        t.put("QUOTE_VALID_DATE", "12-06-26");

        byte[] docx = DocxTemplate.fill(template(), t,
                Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", commercialBom()));
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve("commercial.docx"), docx);

        String text = documentText(docx);

        // Same boilerplate as the residential document
        assertTrue(text.contains("Our Company Overview"));
        assertTrue(text.contains("Safety & Quality Commitment"));
        assertTrue(text.contains("How Net Metering Works"));
        assertTrue(text.contains("Solar Modules: 25 years"));
        assertTrue(text.contains("Executive Manager – Procurement"));

        assertTrue(text.contains("LKR Convention Center Commercial"));
        assertTrue(text.contains("Price for 50 kWp Plant"));
        assertTrue(text.contains("₹28,40,000"));
        assertTrue(text.contains("Quote valid for 10 days from Friday (12-06-26)."));

        // Both optional sections gone, wholesale
        assertFalse(text.contains("Subsidy Details"), "commercial proposals carry no subsidy block");
        assertFalse(text.contains("MNRE"), "no subsidy notes left behind");
        assertFalse(text.contains("ROI Analysis"), "commercial proposals carry no ROI table");
        assertFalse(text.contains("Net Lifetime Financial Benefit"));

        assertTrue(text.contains("SWELECT/ JAKSON"));
        assertTrue(text.contains("KAMPSOL"));
        assertEquals(19, countRows(docx));

        assertFalse(text.contains("{{"), "no unresolved tokens");
        assertMediaIntact(docx);
    }

    // ── Amount in words ──────────────────────────────────────────────────────

    @Test
    void spellsIndianAmounts() {
        assertEquals("Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only.",
                AmountInWords.rupees(new BigDecimal("253461")));
        assertEquals("Rupees Twenty Eight Lakhs Forty Thousand Only.",
                AmountInWords.rupees(new BigDecimal("2840000")));
        assertEquals("Rupees One Lakh Only.", AmountInWords.rupees(new BigDecimal("100000")));
        assertEquals("Rupees One Crore Twenty Three Lakhs Forty Five Thousand Six Hundred Seventy Eight Only.",
                AmountInWords.rupees(new BigDecimal("12345678")));
        assertEquals("Rupees Zero Only.", AmountInWords.rupees(BigDecimal.ZERO));
        // Paise round into the rupee figure that is printed on the document.
        assertEquals("Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty Two Only.",
                AmountInWords.rupees(new BigDecimal("253461.60")));
    }

    // ── Which leads take this path ───────────────────────────────────────────

    /**
     * Solar is an EPC <em>sub-group</em>, not a group. Getting this wrong silently
     * leaves every real solar lead on the old generic form, so it is pinned here
     * against the live taxonomy.
     */
    @Test
    void routesOnlySolarSubGroupsToTheGeneratedDocument() {
        for (String sub : new String[] { "Solar_Rooftop", "Solar_ground_mounted", "Solar_carports", "Solar Wind" }) {
            assertTrue(SolarProposalDocService.isSolar("EPC", sub), sub + " must use the solar document");
        }
        for (String sub : new String[] { "Substations", "Pm_kusum", "CCMS", "ITMS", "MCMS", "", null }) {
            assertFalse(SolarProposalDocService.isSolar("EPC", sub), sub + " must keep the generic proposal path");
        }
        assertFalse(SolarProposalDocService.isSolar("IoT", "CCMS"));
        assertFalse(SolarProposalDocService.isSolar("CBG", null));
        // Honoured in case the taxonomy is ever flattened to a group.
        assertTrue(SolarProposalDocService.isSolar("Solar", null));
    }

    // ── Structural safety ────────────────────────────────────────────────────

    @Test
    void dropsBothOptionalBlocksIndependently() throws Exception {
        String withRoiOnly = documentText(DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", false, "ROI", true), Map.of("BOM", residentialBom())));
        assertFalse(withRoiOnly.contains("Subsidy Details"));
        assertTrue(withRoiOnly.contains("ROI Analysis"));

        String withSubsidyOnly = documentText(DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", true, "ROI", false), Map.of("BOM", residentialBom())));
        assertTrue(withSubsidyOnly.contains("Subsidy Details"));
        assertFalse(withSubsidyOnly.contains("ROI Analysis"));
    }

    @Test
    void escapesMarkupInSuppliedValues() throws Exception {
        Map<String, String> t = baseTokens();
        t.put("CLIENT_NAME", "Ram & Co <Solar> \"Pvt\"");
        List<Map<String, String>> bom = List.of(row("PV Modules", "540Wp & above", "A&B", "10", "No's"));
        byte[] docx = DocxTemplate.fill(template(), t, Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", bom));
        String xml = part(docx, "word/document.xml");
        assertTrue(xml.contains("Ram &amp; Co &lt;Solar&gt;"), "ampersands and angle brackets escaped");
        assertTrue(documentText(docx).contains("Ram & Co <Solar> \"Pvt\""));
    }

    @Test
    void keepsAllMediaAndPartsWhenBothBlocksAreDropped() throws Exception {
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", commercialBom()));
        assertMediaIntact(docx);
        assertEquals(2, countOccurrences(part(docx, "word/document.xml"), "<w:sectPr"),
                "only the continuous cover break and the body section survive");
    }

    /**
     * The reference pinned its pagination with hard page breaks. A BOM whose
     * length varies per lead makes those wrong every time — the page before the
     * signatory ends half empty, and whatever follows a grown table still starts
     * on a fresh page. The skeleton must flow instead.
     */
    @Test
    void containsNoHardPageBreaks() throws Exception {
        for (List<Map<String, String>> bom : List.of(
                residentialBom(),
                List.of(row("PV Modules", "540Wp", "Tata", "6", "No's")))) {
            byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                    Map.of("SUBSIDY", true, "ROI", true), Map.of("BOM", bom));
            String xml = part(docx, "word/document.xml");

            assertEquals(0, countOccurrences(xml, "w:type w:val=\"nextPage\""), "no nextPage section break");
            assertEquals(0, countOccurrences(xml, "w:br w:type=\"page\""), "no manual page break");
            // The one surviving break installs the header/footer without paginating.
            assertEquals(1, countOccurrences(xml, "w:type w:val=\"continuous\""));

            // Signatory flows straight on from the Bill of Material. The only
            // section break past the table is the body-level one that closes the
            // document — it sits after the last paragraph and paginates nothing.
            int afterBom = xml.lastIndexOf("</w:tbl>");
            int lastParagraphEnd = xml.lastIndexOf("</w:p>");
            assertEquals(0, countOccurrences(xml.substring(afterBom, lastParagraphEnd), "<w:sectPr"),
                    "nothing between the BOM and the signatory forces a new page");
            assertEquals(1, countOccurrences(xml.substring(lastParagraphEnd), "<w:sectPr"),
                    "document still closes with its body section properties");

            assertTrue(documentText(docx).contains("Executive Manager – Procurement"));
        }
    }

    /** Long BOM specifications must grow their row, not be sliced by a page break. */
    @Test
    void bomRowsGrowAndNeverSplitAcrossPages() throws Exception {
        List<Map<String, String>> bom = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            bom.add(row("Component " + i,
                    "A deliberately long specification line that wraps over several lines inside its cell "
                  + "so the row has to grow to fit it — item " + i,
                    "Make " + i, String.valueOf(i), "No's"));
        }
        numbered(bom);
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", bom));

        assertEquals(40, countRows(docx));
        String xml = part(docx, "word/document.xml");
        int table = xml.lastIndexOf("<w:tbl>");
        String bomTable = xml.substring(table, xml.indexOf("</w:tbl>", table));
        assertEquals(41, countOccurrences(bomTable, "<w:cantSplit/>"), "every row incl. header keeps itself whole");
        assertEquals(1, countOccurrences(bomTable, "<w:tblHeader/>"), "header repeats on each page");
        assertTrue(documentText(docx).contains("Component 40"));
        assertTrue(documentText(docx).contains("Executive Manager – Procurement"));
    }

    /**
     * No heading may be left alone at the foot of a page while the block it
     * introduces starts on the next one. Each is bound to what follows with
     * w:keepNext, through any spacer paragraph in between.
     */
    @Test
    void everyHeadingIsBoundToTheBlockItIntroduces() throws Exception {
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", true, "ROI", true), Map.of("BOM", residentialBom()));
        String xml = part(docx, "word/document.xml");

        for (String heading : new String[] {
                "Our Company Overview", "Our Expertise", "EPC Capabilities", "Innovation & R&D",
                "Global Presence:", "Safety & Quality Commitment:", "Our safety practices include:",
                "Our Sample PROJECT EXECUTION / INSTALLATION WORKS", "About the System",
                "How Net Metering Works", "Financial Pricing", "Subsidy Details:-",
                "Note:", "Warranty:", "Bill of Material",
        }) {
            assertTrue(paragraphWithText(xml, heading).contains("<w:keepNext/>"),
                    "«" + heading + "» must keep with the block it introduces");
        }
        // The inverse — ordinary body text must stay free, or a long section
        // would be dragged onto the next page wholesale. "Net Metering" appears
        // in an EPC bullet too, which is why headings are matched on full text.
        for (String body : new String[] { "Grid Synchronization & Net Metering", "Inverter: 8 years" }) {
            assertFalse(paragraphWithText(xml, body).contains("<w:keepNext/>"),
                    "«" + body + "» must be free to flow");
        }
    }

    /** Widow/orphan control: the reference disables it, which strands single lines. */
    @Test
    void widowControlIsOn() throws Exception {
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", commercialBom()));
        String styles = part(docx, "word/styles.xml");
        assertFalse(styles.contains("<w:widowControl w:val=\"0\"/>"),
                "widow/orphan control must not be disabled document-wide");
        assertTrue(styles.contains("<w:widowControl/>"), "docDefaults must enable it");
    }

    /** Short fixed-size tables hold together; the BOM stays free to flow. */
    @Test
    void shortTablesStayWholeAndTheBomStillFlows() throws Exception {
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", true, "ROI", true), Map.of("BOM", residentialBom()));
        String xml = part(docx, "word/document.xml");

        String roi = tableContaining(xml, "ROI Analysis");
        assertTrue(countOccurrences(roi, "<w:keepNext/>") > 0,
                "ROI rows hold on to the row below so the table is not split");
        assertTrue(countOccurrences(roi, "<w:cantSplit/>") >= 16, "no ROI row is torn in half");

        // The BOM must NOT be pinned together — a 40-row table has to paginate.
        int bomStart = xml.lastIndexOf("<w:tbl>");
        String bom = xml.substring(bomStart, xml.indexOf("</w:tbl>", bomStart));
        assertEquals(0, countOccurrences(bom, "<w:keepNext/>"),
                "the BOM must stay free to flow across pages");
        assertTrue(countOccurrences(bom, "<w:cantSplit/>") > 0, "though its individual rows stay whole");
    }

    /** Both footer defects the reference carried: split pincode and a crooked name line. */
    @Test
    void footerAddressIsCorrectedAndAligned() throws Exception {
        byte[] docx = DocxTemplate.fill(template(), baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", commercialBom()));
        for (String part : new String[] { "word/footer1.xml", "word/footer2.xml" }) {
            String xml = part(docx, part);
            String text = xml.replaceAll("<[^>]+>", "");
            assertTrue(text.contains("Telangana India 500084"), part + ": pincode must not be split");
            assertFalse(text.contains("5 00084"), part + ": stray space inside the pincode");
            assertFalse(xml.contains("<w:ind w:left=\"812\"/>"),
                    part + ": company name must be centred with the address, not indented");
        }
    }

    // ── Fixtures / helpers ───────────────────────────────────────────────────

    // ── Reference data lives in SolarProposalFixtures so SolarProposalPdfTest
    //    renders the SAME proposal from the SAME numbers. ──────────────────────

    private static Map<String, String> baseTokens() { return SolarProposalFixtures.baseTokens(); }

    private static Map<String, String> row(String component, String spec, String make, String qty, String unit) {
        return SolarProposalFixtures.row(component, spec, make, qty, unit);
    }

    private static List<Map<String, String>> residentialBom() { return SolarProposalFixtures.residentialBom(); }

    private static List<Map<String, String>> commercialBom() { return SolarProposalFixtures.commercialBom(); }

    private static List<Map<String, String>> numbered(List<Map<String, String>> rows) {
        return SolarProposalFixtures.numbered(rows);
    }

    private static byte[] template() throws Exception { return SolarProposalFixtures.template(); }
    private static String part(byte[] docx, String name) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().equals(name)) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing part " + name);
    }

    /** Visible text of the document body, with runs joined per paragraph. */
    private static String documentText(byte[] docx) throws Exception {
        String xml = part(docx, "word/document.xml");
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|</w:p>").matcher(xml);
        while (m.find()) {
            if (m.group(1) == null) sb.append('\n');
            else sb.append(unescape(m.group(1)));
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Data rows in the Bill of Material — the last table in the document. */
    private static int countRows(byte[] docx) throws Exception {
        String xml = part(docx, "word/document.xml");
        int start = xml.lastIndexOf("<w:tbl>");
        int end = xml.indexOf("</w:tbl>", start);
        String table = xml.substring(start, end);
        // "<w:tr" alone would also match <w:trPr> and <w:trHeight>.
        int rows = countOccurrences(table, "<w:tr>") + countOccurrences(table, "<w:tr ");
        return rows - 1; // minus the repeating header row
    }

    /**
     * The paragraph whose runs spell out exactly {@code text}.
     *
     * <p>Matching on the whole paragraph rather than a substring of the XML
     * matters twice over: Word splits a heading across several {@code <w:t>}
     * runs so the phrase never appears contiguously in the markup, and a
     * fragment like "Net Metering" also occurs in an EPC bullet that must stay
     * unpinned.
     */
    private static String paragraphWithText(String xml, String text) {
        for (String p : leafParagraphs(xml)) {
            if (runText(p).replaceAll("\\s+", " ").trim().equals(text)) return p;
        }
        throw new AssertionError("no paragraph reads exactly: " + text);
    }

    /**
     * Innermost paragraphs only. Text boxes nest real paragraphs inside a
     * paragraph, and for any {@code </w:p>} the nearest preceding {@code <w:p}
     * is its own opening tag.
     */
    private static List<String> leafParagraphs(String xml) {
        List<String> out = new ArrayList<>();
        int close = xml.indexOf("</w:p>");
        while (close >= 0) {
            int open = xml.lastIndexOf("<w:p ", close);
            int bare = xml.lastIndexOf("<w:p>", close);
            int start = Math.max(open, bare);
            if (start >= 0) out.add(xml.substring(start, close));
            close = xml.indexOf("</w:p>", close + 1);
        }
        return out;
    }

    private static String runText(String paragraphXml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>").matcher(paragraphXml);
        while (m.find()) sb.append(unescape(m.group(1)));
        return sb.toString();
    }

    /** The {@code <w:tbl>…</w:tbl>} that contains {@code needle}. */
    private static String tableContaining(String xml, String needle) {
        int at = xml.indexOf(needle);
        assertTrue(at >= 0, "text not found in the document: " + needle);
        int start = xml.lastIndexOf("<w:tbl>", at);
        int end = xml.indexOf("</w:tbl>", at);
        assertTrue(start >= 0 && end > start, "could not bound the table for: " + needle);
        return xml.substring(start, end);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + needle.length()); }
        return n;
    }

    /** Every image and part the reference carries must still be in the package. */
    private static void assertMediaIntact(byte[] docx) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) names.add(e.getName());
        }
        for (String required : new String[] {
                "word/document.xml", "word/styles.xml", "word/numbering.xml",
                "word/header1.xml", "word/header2.xml", "word/footer1.xml", "word/footer2.xml",
                "word/media/image1.jpeg", // cover
                "word/media/image2.png",  // cover
                "word/media/image5.jpeg", // running-header logo
                "word/media/image6.jpeg", // sample works
                "word/media/image7.jpeg", // about the system
                "word/media/image8.jpeg", // net metering
                "word/media/image9.jpeg", // signature
        }) {
            assertTrue(names.contains(required), "package must still contain " + required);
        }
    }
}
