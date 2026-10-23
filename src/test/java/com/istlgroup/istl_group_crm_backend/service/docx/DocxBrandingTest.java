package com.istlgroup.istl_group_crm_backend.service.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.service.SolarProposalFixtures;

/**
 * The Word proposal's header logo and footer band carry the proposal's company,
 * never the skeleton's (Sesola's). Pure: no Spring, no DB.
 */
class DocxBrandingTest {

    private static final Path OUT_DIR = Path.of("target", "proposal-test-output");

    private static byte[] filled() throws Exception {
        return DocxTemplate.fill(SolarProposalFixtures.template(), SolarProposalFixtures.baseTokens(),
                Map.of("SUBSIDY", false, "ROI", false),
                Map.of("BOM", SolarProposalFixtures.commercialBom()));
    }

    /** A 400x100 PNG — the box is wider still, so it scales by height. */
    private static byte[] png() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB), "png", bos);
        return bos.toByteArray();
    }

    private static String part(Map<String, byte[]> parts, String name) {
        byte[] b = parts.get(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void footerAndLogoCarryTheCompany() throws Exception {
        byte[] docx = DocxBranding.apply(filled(), png(),
                "ACME SOLAR PRIVATE LIMITED", "1 Main Road, Pune 411001",
                "www.acme.example | Email: hi@acme.example | M: +91 90000 00000");
        Map<String, byte[]> parts = DocxTemplate.read(docx);

        for (String f : new String[] { "word/footer1.xml", "word/footer2.xml" }) {
            String xml = part(parts, f);
            assertTrue(xml.contains("ACME SOLAR PRIVATE LIMITED"), f);
            assertTrue(xml.contains("1 Main Road, Pune 411001"), f);
            assertTrue(xml.contains("hi@acme.example"), f);
            assertFalse(xml.toUpperCase().contains("SESOLA"), f);
            assertFalse(xml.contains("Pranava"), f);
            assertFalse(xml.contains("<w:hyperlink"), f);
            assertFalse(part(parts, "word/_rels/" + f.substring(5) + ".rels").contains("sesolaenergy"), f);
        }
        for (String h : new String[] { "word/header1.xml", "word/header2.xml" }) {
            String rels = part(parts, "word/_rels/" + h.substring(5) + ".rels");
            assertTrue(rels.contains("media/company-logo.png"), h);
            // 400x100 into 1991867x443484 EMU: height-bound, right edge kept.
            String xml = part(parts, h);
            assertTrue(xml.contains("cx=\"1773936\" cy=\"443484\""), h);
            assertTrue(xml.contains("<wp:posOffset>5175502</wp:posOffset>"), h);
        }
        assertNull(parts.get("word/media/image5.jpeg"), "the skeleton's logo is not shipped");
        assertTrue(parts.containsKey("word/media/company-logo.png"));

        // Opens as a valid document.
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertEquals(2, doc.getFooterList().size());
        }
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve("branded-proposal.docx"), docx);
    }

    @Test
    void noLogoRemovesTheHeaderPicture() throws Exception {
        byte[] docx = DocxBranding.apply(filled(), null, "", "", "");
        Map<String, byte[]> parts = DocxTemplate.read(docx);
        assertFalse(part(parts, "word/header1.xml").contains("<w:drawing>"));
        assertFalse(part(parts, "word/header2.xml").contains("<w:drawing>"));
        assertNull(parts.get("word/media/image5.jpeg"));
        assertFalse(part(parts, "word/footer1.xml").toUpperCase().contains("SESOLA"));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertEquals(2, doc.getHeaderList().size());
        }
    }

    @Test
    void unsupportedLogoFormatIsOmitted() {
        assertNull(DocxBranding.extensionOf("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("jpeg", DocxBranding.extensionOf(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 }));
    }
}
