package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.service.pdf.GenericProposalCopy;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.GenericProposalDocRequest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the standard proposal and asserts on the extracted text with PDFBox.
 *
 * <p>Pure JUnit — no Spring, no DB — mirroring {@code SolarProposalPdfTest}. Proof
 * files land in {@code target/proposal-test-output/} so the layout can be eyeballed.
 *
 * <p>The load-bearing assertion is {@link #saysNothingAboutSesolaOrSolar()}: this
 * document is rendered for every organization on the platform, so a single leaked
 * word of the solar boilerplate would be a defect in every tenant's quote at once.
 */
class GenericProposalPdfTest {

    private static final Path OUT = Path.of("target", "proposal-test-output");
    private static final String ORG = "Northwind Infra Systems";

    private static GenericProposalPdfService service() {
        return new GenericProposalPdfService(new GenericProposalCopy());
    }

    /** A fully populated request — every section present. */
    private static GenericProposalDocRequest full() {
        GenericProposalDocRequest r = new GenericProposalDocRequest();
        r.setLeadId(101L);
        r.setTitle("Supply and Installation of CCMS Panels");
        r.setClientName("Greenfield Municipal Corporation");
        r.setClientAddress("Civic Centre, Ward 4, Pune, Maharashtra, 411001");
        r.setSiteLocation("Ward 4 arterial roads");
        r.setValidityDays(30);
        r.setQuoteValidFrom("2026-01-05");
        r.setDescription("We are pleased to submit our proposal for the works described below.");
        r.setAboutUs("Northwind Infra Systems has delivered municipal infrastructure projects since 2009.");
        r.setAboutSystem("The proposed solution comprises networked control panels with remote monitoring.");
        r.setWorkDescription("Design, supply, installation and commissioning of 40 control panels.");
        r.setPaymentTerms("30% advance along with the purchase order\n"
                + "50% against delivery at site\n"
                + "20% after commissioning and handover");
        r.setDefectLiabilityPeriod("12 months from the date of commissioning.");
        r.setWarrantyNotes("Manufacturer warranty applies to bought-out items.");
        r.setBasePrice(new BigDecimal("2500000"));
        r.setGstPercent(new BigDecimal("18"));
        r.setPreparedByName("A. Preparer");
        r.setPreparedByDesignation("Manager - Projects");
        r.setPreparedByEmail("sales@example.test");

        List<GenericProposalDocRequest.ScopeRow> scope = new ArrayList<>();
        scope.add(scopeRow("Survey and design", "Route survey and load calculation", "1", "Lot"));
        scope.add(scopeRow("Panel installation", "Foundation, mounting and termination", "40", "Nos"));
        r.setScopeRows(scope);

        List<GenericProposalDocRequest.BomRow> bom = new ArrayList<>();
        bom.add(bomRow("Control panel", "IP65, 63A", "Acme", "40", "Nos"));
        bom.add(bomRow("Armoured cable", "4C x 16 sq mm", "Polycab", "2200", "Mtr"));
        r.setBomRows(bom);
        return r;
    }

    /** The thinnest request the service will accept: one BOM line and a price. */
    private static GenericProposalDocRequest minimal() {
        GenericProposalDocRequest r = new GenericProposalDocRequest();
        r.setLeadId(102L);
        r.setTitle("Annual Maintenance Contract");
        r.setClientName("Riverside Estates");
        r.setBasePrice(new BigDecimal("180000"));
        r.setGstPercent(new BigDecimal("18"));
        List<GenericProposalDocRequest.BomRow> bom = new ArrayList<>();
        bom.add(bomRow("Preventive maintenance visits", "Quarterly", "", "4", "Visits"));
        r.setBomRows(bom);
        return r;
    }

    private static byte[] render(GenericProposalDocRequest r, byte[] logo) throws Exception {
        SolarProposalDocService.Money money =
                SolarProposalDocService.Money.of(r.getBasePrice(), r.getGstPercent(), r.getTotalCost());
        return service().render(r, money,
                new GenericProposalPdfService.Branding(ORG, logo),
                new GenericProposalPdfService.DocumentRef("PROP-2026-000123", 1, LocalDate.of(2026, 1, 5)));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** PDFTextStripper breaks lines at glyph-position jumps; flatten before asserting. */
    private static String flat(String s) { return s.replaceAll("\\s+", " ").trim(); }

    private static void write(String name, byte[] pdf) throws Exception {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), pdf);
    }

    // =========================================================================

    @Test
    void rendersEverySectionOfAFullyPopulatedProposal() throws Exception {
        byte[] pdf = render(full(), null);
        write("generic-proposal-full.pdf", pdf);
        String t = flat(text(pdf));

        // Cover: the organization's identity, and the four facts a buyer checks.
        assertTrue(t.contains(ORG), "the cover must carry the rendering organization's name");
        assertTrue(t.contains("PROP-2026-000123"));
        assertTrue(t.contains("05-01-2026"));
        assertTrue(t.contains("04-02-2026"), "valid until = issue date + 30 days");
        assertTrue(t.contains("Greenfield Municipal Corporation"));
        assertTrue(t.contains("Supply and Installation of CCMS Panels"));

        for (String heading : new String[] {
                "Introduction", "About Us", "Proposed Solution", "Scope of Work",
                "Scope of Supply", "Commercial Summary", "Payment Terms",
                "Warranty", "Validity & Acceptance" }) {
            assertTrue(t.contains(heading), "missing section heading: " + heading);
        }

        // Both tables, and their content.
        assertTrue(t.contains("Panel installation"));
        assertTrue(t.contains("Armoured cable"));
        assertTrue(t.contains("Polycab"));

        // The commercial summary: base, GST and the prominent total.
        assertTrue(t.contains("25,00,000"), "base price, Indian-grouped");
        assertTrue(t.contains("18%"));
        assertTrue(t.contains("29,50,000"), "total contract value");
        assertTrue(t.contains("Rupees Twenty Nine Lakhs Fifty Thousand Only."),
                "amount in words must accompany the total");

        // Payment terms authored one per line become separate clauses.
        assertTrue(t.contains("30% advance"));
        assertTrue(t.contains("20% after commissioning"));

        // Signature blocks, both sides.
        assertTrue(t.contains("For " + ORG));
        assertTrue(t.contains("Accepted for Greenfield Municipal Corporation"));
        assertTrue(t.contains("A. Preparer"));
    }

    @Test
    void omitsSectionsWhoseSourceFieldsAreBlank() throws Exception {
        byte[] pdf = render(minimal(), null);
        write("generic-proposal-minimal.pdf", pdf);
        String t = flat(text(pdf));

        // An empty heading in a client-facing quote reads as an oversight, so the
        // whole section goes rather than the prose alone.
        assertFalse(t.contains("About Us"), "About Us must not appear with nothing under it");
        assertFalse(t.contains("Proposed Solution"));
        assertFalse(t.contains("Introduction"));
        assertFalse(t.contains("Scope of Work"), "no scope rows were supplied");
        assertFalse(t.contains("Payment Terms"));
        assertFalse(t.contains("Warranty"));

        // What is present is still a complete, signable document.
        assertTrue(t.contains(ORG));
        assertTrue(t.contains("Annual Maintenance Contract"));
        assertTrue(t.contains("Scope of Supply"));
        assertTrue(t.contains("Commercial Summary"));
        assertTrue(t.contains("Validity & Acceptance"));
        assertTrue(t.contains("2,12,400"), "180000 + 18% GST");
    }

    @Test
    void saysNothingAboutSesolaOrSolar() throws Exception {
        String t = text(render(full(), null)) + text(render(minimal(), null));
        String lower = t.toLowerCase();
        for (String banned : new String[] {
                "sesola", "sesolaenergy", "solar", "kwp", "mnre", "subsidy",
                "rooftop", "surya ghar", "power projects" }) {
            assertFalse(lower.contains(banned),
                    "the standard proposal must be industry-neutral, but it contains: " + banned);
        }
    }

    @Test
    void aBrokenLogoDoesNotCostTheDocument() throws Exception {
        // The logo is decoration; an organization that uploaded something iText cannot
        // decode must still get its proposal, branded by name.
        byte[] pdf = render(full(), new byte[] { 1, 2, 3, 4 });
        String t = flat(text(pdf));
        assertTrue(t.contains(ORG));
        assertTrue(t.contains("Commercial Summary"));
    }

    @Test
    void everyPageCarriesTheOrganizationAndAPageNumber() throws Exception {
        byte[] pdf = render(full(), null);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 2, "cover plus at least one content page");
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(p);
                s.setEndPage(p);
                String page = flat(s.getText(doc));
                assertTrue(page.contains(ORG), "page " + p + " footer must name the organization");
                assertTrue(page.contains("Page " + p), "page " + p + " must be numbered");
            }
        }
    }

    // =========================================================================

    private static GenericProposalDocRequest.ScopeRow scopeRow(String activity, String spec,
                                                               String qty, String unit) {
        GenericProposalDocRequest.ScopeRow r = new GenericProposalDocRequest.ScopeRow();
        r.setActivity(activity);
        r.setSpecification(spec);
        r.setQuantity(qty);
        r.setUnit(unit);
        return r;
    }

    private static GenericProposalDocRequest.BomRow bomRow(String component, String spec,
                                                           String make, String qty, String unit) {
        GenericProposalDocRequest.BomRow r = new GenericProposalDocRequest.BomRow();
        r.setComponent(component);
        r.setSpecification(spec);
        r.setMake(make);
        r.setQuantity(qty);
        r.setUnit(unit);
        return r;
    }
}
