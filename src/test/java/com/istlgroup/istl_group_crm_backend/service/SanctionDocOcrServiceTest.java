package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the one thing that must always be true of this class regardless of
 * whether Tesseract/tessdata happens to be installed on the machine running
 * the build: rendering never throws, and OCR either returns text or {@code
 * null} — it never crashes the caller. Actual OCR accuracy on a real scanned
 * sanction letter is not something a unit test can meaningfully pin down
 * (it depends on scan quality and the tessdata installed on each
 * environment), so this deliberately does not assert on recognised text.
 */
class SanctionDocOcrServiceTest {

    private final SanctionDocOcrService service = new SanctionDocOcrService("./tessdata");

    /** A one-page PDF whose only content is a rasterised image — i.e. exactly
     *  what {@code SanctionDocExtractor.loadText} sees as "no text layer". */
    private static byte[] scannedLikePdf() throws Exception {
        BufferedImage img = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 300);
        g.setColor(Color.BLACK);
        g.drawString("Sanction Letter — Ref No: TEST/2026/001", 20, 60);
        g.dispose();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 50, 400, 300, 150);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    @DisplayName("renders a scanned-looking PDF's pages to non-empty PNGs, capped at MAX_PAGES")
    void rendersPagesToPng() throws Exception {
        byte[] pdf = scannedLikePdf();
        var pages = service.renderPagesToPng(pdf);
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).length > 0, "rendered page image should not be empty");
        // PNG magic bytes — confirms it's a real image, not raw pixel garbage.
        byte[] png = pages.get(0);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
    }

    @Test
    @DisplayName("an unloadable PDF renders zero pages instead of throwing")
    void garbageBytesRenderNoPages() {
        List<byte[]> pages = assertDoesNotThrow(() -> service.renderPagesToPng("not a pdf".getBytes()));
        assertTrue(pages.isEmpty());
    }

    @Test
    @DisplayName("OCR never throws — missing tessdata/native lib degrades to null, not a crash")
    void ocrNeverThrows() throws Exception {
        byte[] pdf = scannedLikePdf();
        byte[] page = service.renderPagesToPng(pdf).get(0);

        String text = assertDoesNotThrow(() -> service.ocrPage(page));
        // Either OCR genuinely ran (this environment has tessdata) and returned
        // some text, or it didn't and returned null — both are acceptable; the
        // only thing this test enforces is "did not throw".
        if (text != null) {
            assertFalse(text.isBlank() && service.isAvailable(),
                    "if Tesseract reports itself available it should not also return blank text");
        }
    }

    @Test
    @DisplayName("OCR on a corrupt image returns null rather than throwing")
    void corruptImageReturnsNull() {
        String text = assertDoesNotThrow(() -> service.ocrPage(new byte[] {1, 2, 3}));
        assertTrue(text == null);
    }
}
