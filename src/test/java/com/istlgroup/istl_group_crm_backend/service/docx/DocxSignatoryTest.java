package com.istlgroup.istl_group_crm_backend.service.docx;

import com.istlgroup.istl_group_crm_backend.service.SolarProposalFixtures;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Word proposal's closing block is signed by the proposal's own company and its chosen
 * signatory — never by the signer the skeleton was cut from.
 */
class DocxSignatoryTest {

    @Test
    void signsAsTheChosenSignatory() throws Exception {
        byte[] signature = png();
        byte[] out = DocxSignatory.apply(SolarProposalFixtures.template(), "ORG B ENERGY PVT LTD",
                "Asha Rao", "+91-9000000000", "Director", signature);

        Map<String, byte[]> parts = DocxTemplate.read(out);
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        String text = DocxBranding.textOf(xml);

        assertTrue(text.contains("For ORG B ENERGY PVT LTD"));
        assertTrue(text.contains("Asha Rao"));
        assertTrue(text.contains("+91-9000000000"));
        assertTrue(text.contains("Director"));
        assertFalse(text.contains("Sudhakar"), "the skeleton's signer survived");
        assertFalse(text.contains("7013759123"), "the skeleton's signer's phone survived");
        assertFalse(text.contains("For Sesola Power Projects"), "the skeleton's company survived");

        assertArrayEquals(signature, parts.get("word/media/signatory.png"),
                "the chosen signatory's image is not the one in the document");
        assertNull(parts.get("word/media/image9.jpeg"), "the skeleton's signature scan is still packaged");
        String rels = new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8);
        assertTrue(rels.contains("Target=\"media/signatory.png\""));
        assertTrue(new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8)
                .contains("Extension=\"png\""));
    }

    @Test
    void withoutASignerTheBlockIsBlank() throws Exception {
        byte[] out = DocxSignatory.apply(SolarProposalFixtures.template(), "ORG B ENERGY PVT LTD",
                null, null, null, null);

        Map<String, byte[]> parts = DocxTemplate.read(out);
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        String text = DocxBranding.textOf(xml);

        assertTrue(text.contains("For ORG B ENERGY PVT LTD"));
        assertFalse(text.contains("Sudhakar"));
        assertFalse(text.contains("Executive Manager"));
        assertFalse(xml.contains("r:embed=\"rId18\""), "the skeleton's signature drawing is still in the body");
        assertNull(parts.get("word/media/image9.jpeg"), "the skeleton's signature scan is still packaged");
        assertFalse(new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8)
                .contains("Id=\"rId18\""), "a relationship points at a removed image");
        assertNotNull(parts.get("word/media/image1.jpeg"), "other artwork must be left alone");
    }

    @Test
    void aBlankProfileLeavesTheForLineEmpty() throws Exception {
        byte[] out = DocxSignatory.apply(SolarProposalFixtures.template(), "  ", null, null, null, null);
        String text = DocxBranding.textOf(new String(DocxTemplate.read(out).get("word/document.xml"),
                StandardCharsets.UTF_8));
        assertFalse(text.contains("Sesola Power Projects Pvt. Ltd."),
                "a blank profile must print blank, never the skeleton's company");
    }

    private static byte[] png() throws Exception {
        var image = new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
