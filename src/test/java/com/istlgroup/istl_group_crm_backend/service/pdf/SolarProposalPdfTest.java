package com.istlgroup.istl_group_crm_backend.service.pdf;

import com.istlgroup.istl_group_crm_backend.service.SolarProposalFixtures;
import com.istlgroup.istl_group_crm_backend.service.SolarProposalPdfService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the two signed-off reference proposals through the iText renderer and
 * asserts on the extracted text with PDFBox.
 *
 * <p>Pure JUnit — no Spring, no DB — mirroring {@code SolarProposalDocTest}, and
 * drawing its reference data from the same {@link SolarProposalFixtures} so the
 * .docx and .pdf renderings are provably built from identical numbers.
 *
 * <p>Proof files are written to {@code target/proposal-test-output/} so the
 * layout can be eyeballed.
 */
class SolarProposalPdfTest {

    private static final Path OUT = Path.of("target", "proposal-test-output");

    private static SolarProposalPdfService service() {
        return new SolarProposalPdfService(new ProposalCopy());
    }

    private static byte[] residential() throws Exception {
        return service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", true, "ROI", true),
                Map.of("BOM", SolarProposalFixtures.residentialBom()));
    }

    private static byte[] commercial() throws Exception {
        return service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.commercialBom()));
    }

    /** PDFTextStripper breaks lines at glyph-position jumps; flatten before asserting. */
    private static String flat(String s) { return s.replaceAll("\\s+", " ").trim(); }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {   // PDFBox 3 API
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    private static String pageText(byte[] pdf, int page) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(doc);
        }
    }

    private static void write(String name, byte[] bytes) throws Exception {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), bytes);
    }

    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void rendersTheResidentialReference() throws Exception {
        byte[] pdf = residential();
        write("residential.pdf", pdf);

        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        String t = flat(text(pdf));

        for (String heading : new String[]{
                "Our Company Overview", "Our Expertise", "EPC Capabilities",
                "Innovation & R&D", "Global Presence", "Safety & Quality Commitment",
                "Financial Pricing", "Subsidy Details", "ROI Analysis",
                "Note:", "Warranty:", "Bill of Material"}) {
            assertTrue(t.contains(heading), "missing section heading: " + heading);
        }

        // Boilerplate spot-checks, including the bullet the skeleton splits in two.
        assertTrue(t.contains("Sesola has cumulatively completed over 300 MW"));
        assertTrue(t.contains("zero-incident project execution"));
        assertTrue(t.contains("132 kV substations and 220 kV transmission lines"),
                   "children 25+26 should render as ONE bullet");

        // Values, straight from the token map.
        assertTrue(t.contains("₹2,53,461"), "total price missing (rupee glyph or value)");
        assertTrue(t.contains("₹10,97,039"), "ROI net benefit missing");
        assertTrue(t.contains("Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only."));
        assertTrue(t.contains("Quote valid for 10 days from Monday (01-01-26)."));

        // Signatory.
        assertTrue(t.contains("Sudhakar G"));
        assertTrue(t.contains("+91-7013759123"));
        assertTrue(t.contains("Executive Manager – Procurement"), "en dash should survive UTF-8");

        // BOM: first and last rows present.
        assertTrue(t.contains("PV Modules"));
        assertTrue(t.contains("45*45 PVC Trunking Profile"));

        assertFalse(t.contains("{{"), "an unsubstituted placeholder reached the PDF");
    }

    @Test
    void rendersTheCommercialReferenceWithoutSubsidyOrRoi() throws Exception {
        byte[] pdf = commercial();
        write("commercial.pdf", pdf);
        String t = flat(text(pdf));

        // Boilerplate is unconditional.
        assertTrue(t.contains("Our Company Overview"));
        assertTrue(t.contains("Bill of Material"));
        assertTrue(t.contains("Financial Pricing"));

        // Both optional blocks dropped.
        assertFalse(t.contains("Subsidy Details"), "subsidy block should be absent");
        assertFalse(t.contains("MNRE"), "subsidy notes should be absent");
        assertFalse(t.contains("ROI Analysis"), "ROI table should be absent");
        assertFalse(t.contains("Net Lifetime Financial Benefit"));

        // A commercial-only BOM make.
        assertTrue(t.contains("KAMPSOL"));
    }

    @Test
    void dropsBothOptionalBlocksIndependently() throws Exception {
        String roiOnly = flat(text(service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", false, "ROI", true),
                Map.of("BOM", SolarProposalFixtures.residentialBom()))));
        assertTrue(roiOnly.contains("ROI Analysis"));
        assertFalse(roiOnly.contains("Subsidy Details"));

        String subsidyOnly = flat(text(service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", true, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.residentialBom()))));
        assertTrue(subsidyOnly.contains("Subsidy Details"));
        assertFalse(subsidyOnly.contains("ROI Analysis"));

        String neither = flat(text(service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.residentialBom()))));
        assertFalse(neither.contains("Subsidy Details"));
        assertFalse(neither.contains("ROI Analysis"));
    }

    /**
     * Guards the whole reason for embedding Poppins: a silent fallback to a
     * WinAnsi standard font drops ₹ entirely, which would ship broken price lines.
     */
    @Test
    void embedsPoppinsSoTheRupeeSignRenders() throws Exception {
        byte[] pdf = residential();
        String t = text(pdf);
        assertTrue(t.contains("₹"), "the rupee sign did not survive extraction");
        assertTrue(t.contains("–"), "the en dash did not survive extraction");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            boolean poppins = false;
            for (var name : doc.getPage(1).getResources().getFontNames()) {
                var font = doc.getPage(1).getResources().getFont(name);
                if (font != null && font.getName() != null && font.getName().contains("Poppins")) {
                    poppins = true;
                    break;
                }
            }
            assertTrue(poppins, "Poppins is not embedded — the font fell back silently");
        }
    }

    /** The cover must be a page of its own, not share with the company overview. */
    @Test
    void coverIsItsOwnPage() throws Exception {
        byte[] pdf = residential();
        String p1 = flat(pageText(pdf, 1));
        assertTrue(p1.contains("Proposal for"));
        assertTrue(p1.contains("Client Residential"));
        assertTrue(p1.contains("Proposed Solar Capacity"));
        assertFalse(p1.contains("Our Company Overview"), "the overview should start on page 2");
    }

    /** Every page carries the running header logo band and the footer address. */
    @Test
    void everyPageCarriesTheFooterBand() throws Exception {
        byte[] pdf = residential();
        int n = pages(pdf);
        for (int i = 1; i <= n; i++) {
            String p = flat(pageText(pdf, i));
            assertTrue(p.contains("SESOLA POWER PROJECTS PRIVATE LIMITED"),
                       "footer missing on page " + i);
            assertTrue(p.contains("www.sesolaenergy.com"), "footer contact missing on page " + i);
        }
    }

    /** The 17-row ROI table is the one block at risk of splitting across a page. */
    @Test
    void roiTableStaysOnOnePage() throws Exception {
        byte[] pdf = residential();
        int titlePage = -1, lastRowPage = -1;
        for (int i = 1; i <= pages(pdf); i++) {
            String p = flat(pageText(pdf, i));
            if (p.contains("ROI Analysis")) titlePage = i;
            if (p.contains("Net Lifetime Financial Benefit")) lastRowPage = i;
        }
        assertTrue(titlePage > 0 && lastRowPage > 0, "ROI table not found");
        assertEquals(titlePage, lastRowPage,
                "the ROI table split across pages — reduce ProposalPdfTheme.ROI_CELL");
    }

    /** A long BOM must flow with its header repeated, and not lose the signatory. */
    @Test
    void bomGrowsAcrossPagesWithARepeatingHeader() throws Exception {
        List<Map<String, String>> big = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            big.add(SolarProposalFixtures.row("Component " + i,
                    "A deliberately long specification line for row " + i
                    + " so the table is forced to flow onto another page", "MAKE", "1", "No's"));
        }
        SolarProposalFixtures.numbered(big);

        byte[] pdf = service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", true, "ROI", true), Map.of("BOM", big));
        write("bom-40-rows.pdf", pdf);
        String t = flat(text(pdf));

        assertTrue(t.contains("Component 1 "), "first BOM row missing");
        assertTrue(t.contains("Component 40"), "last BOM row missing");
        assertTrue(pages(pdf) > pages(residential()), "40 rows should need more pages than 19");

        int specHeaders = t.split("Specifications", -1).length - 1;
        assertTrue(specHeaders >= 2, "the BOM header row did not repeat across pages");

        assertTrue(t.contains("Executive Manager"), "the signatory was lost after a long BOM");
    }

    /** An empty BOM still renders a table rather than collapsing. */
    @Test
    void emptyBomStillRendersTheTable() throws Exception {
        byte[] pdf = service().render(SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false), Map.of("BOM", List.of()));
        String t = flat(text(pdf));
        assertTrue(t.contains("Bill of Material"));
        assertTrue(t.contains("Specifications"));
    }

    /** The PDF path has no XML escaping, so markup must round-trip literally. */
    @Test
    void rendersMarkupCharactersLiterally() throws Exception {
        Map<String, String> t = SolarProposalFixtures.baseTokens();
        t.put("CLIENT_NAME", "Ram & Co <Solar> \"Pvt\"");
        byte[] pdf = service().render(t, Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.residentialBom()));
        assertTrue(flat(text(pdf)).contains("Ram & Co <Solar>"));
    }

    /** A long client name must shrink to fit the cover rather than overflow it. */
    @Test
    void longCoverTitlesShrinkToFit() throws Exception {
        Map<String, String> t = SolarProposalFixtures.baseTokens();
        t.put("COVER_TITLE", "A Very Long Client Name Private Limited Commercial Rooftop");
        byte[] pdf = service().render(t, Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.residentialBom()));
        write("long-cover-title.pdf", pdf);
        assertTrue(flat(pageText(pdf, 1)).contains("A Very Long Client Name"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // The parity guard: the PDF's copy must still match the Word skeleton.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Every non-empty, non-token paragraph of boilerplate in the .docx skeleton must
     * still be present in the PDF's copy resource. If sales rebuilds the skeleton and
     * the wording moves, this fails and names the paragraph — which is the only thing
     * stopping the two renderings from silently drifting apart.
     */
    @Test
    void copyMatchesTheWordSkeleton() throws Exception {
        Properties props = new Properties();
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("proposal-copy/solar-proposal.properties");
             var r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        Set<String> copyValues = new HashSet<>();
        for (String v : props.stringPropertyNames()) copyValues.add(flat(props.getProperty(v)));

        String documentXml = part(SolarProposalFixtures.template(), "word/document.xml");
        List<String> missing = new ArrayList<>();

        // Boilerplate lives in body children 9..99; walk every leaf paragraph.
        for (String para : leafParagraphs(documentXml)) {
            String s = flat(para);
            if (s.isEmpty() || s.contains("{{")) continue;          // spacers and token-bearing lines
            if (s.length() < 12) continue;                          // table labels, handled separately
            if (!copyValues.contains(s) && !containedInAValue(copyValues, s)) missing.add(s);
        }

        assertTrue(missing.isEmpty(),
                "the Word skeleton has boilerplate the PDF copy resource does not:\n  - "
                + String.join("\n  - ", missing.subList(0, Math.min(missing.size(), 8))));
    }

    /** Child 26 is merged into bullet 5, so a skeleton paragraph may be a substring. */
    private static boolean containedInAValue(Set<String> values, String s) {
        for (String v : values) if (v.contains(s)) return true;
        return false;
    }

    /** Text of every leaf <w:p> (paragraphs inside text boxes and tables included). */
    private static List<String> leafParagraphs(String xml) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<w:p[ >].*?</w:p>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String p = m.group();
            if (p.indexOf("<w:p ", 5) > 0 || p.indexOf("<w:p>", 5) > 0) continue;   // not a leaf
            StringBuilder sb = new StringBuilder();
            // (?=[ >]) is load-bearing: a bare <w:t[^>]*> also matches <w:tabs>,
            // which drags raw XML into the "text" and makes this test nonsense.
            java.util.regex.Matcher t = java.util.regex.Pattern
                    .compile("<w:t(?=[ >])[^>]*>(.*?)</w:t>", java.util.regex.Pattern.DOTALL).matcher(p);
            while (t.find()) sb.append(t.group(1));
            String s = sb.toString()
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String part(byte[] docx, String name) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(name)) return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("part not found: " + name);
    }
}
