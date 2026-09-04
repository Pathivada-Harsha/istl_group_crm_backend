package com.istlgroup.istl_group_crm_backend.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;

/**
 * Local, zero-cost OCR fallback for a scanned sanction-letter PDF — i.e. one
 * whose {@link SanctionDocExtractor#loadText} comes back with no usable text
 * layer. Tried before any Groq call, per {@link BorrowerService#parseSanction}.
 *
 * <p>Deliberately narrow: this class only renders PDF pages to images and runs
 * Tesseract over them. It has no sanction-domain knowledge — the resulting text
 * is fed straight into the SAME {@link SanctionDocExtractor#extractFromPdfText}
 * every digital PDF already uses, so field extraction is never duplicated here.
 *
 * <p>Pages are rendered in memory (grayscale, no temp files) and capped at
 * {@link #MAX_PAGES} so a mis-uploaded 100-page PDF can't tie up a request —
 * real sanction letters are a handful of pages at most (see
 * {@code SanctionDocAiExtractor}'s own "sanction letters are short" note).
 *
 * <p>Any failure to initialise or run Tesseract — missing native library for
 * this platform, missing {@code tessdata}, a corrupt page image — is caught
 * here and reported via {@link #isAvailable()} / a null page result rather
 * than thrown, so a broken OCR setup degrades to the existing Groq/manual-entry
 * fallback instead of ever failing the whole import.
 */
@Component
public class SanctionDocOcrService {

    private static final Logger log = LoggerFactory.getLogger(SanctionDocOcrService.class);

    /** Real sanction letters run 1–4 pages; this is a safety cap, not a target. */
    public static final int MAX_PAGES = 10;

    /** Good accuracy/size trade-off for a typed or clearly-scanned letter. */
    private static final float RENDER_DPI = 300f;

    private final String tessdataPath;
    private volatile Boolean available; // cached first-use probe result

    public SanctionDocOcrService(
            @Value("${sanction.ocr.tessdata-path:./tessdata}") String tessdataPath) {
        this.tessdataPath = tessdataPath;
    }

    /**
     * Renders up to {@link #MAX_PAGES} pages of the PDF to in-memory PNG bytes
     * (grayscale), for OCR and — only if OCR plus the existing text fallback
     * still aren't enough — reuse as-is by the Groq Vision last resort, so the
     * PDF is never rendered twice. Returns an empty list if the PDF can't be
     * loaded at all (caller already validated the upload, so this should be
     * rare — a corrupt/unreadable file).
     */
    public List<byte[]> renderPagesToPng(byte[] pdfBytes) {
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < total; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.GRAY);
                pages.add(toPng(img));
            }
            if (doc.getNumberOfPages() > MAX_PAGES) {
                log.info("Sanction OCR: PDF has {} pages, only the first {} are processed",
                        doc.getNumberOfPages(), MAX_PAGES);
            }
        } catch (Exception e) {
            log.warn("Sanction OCR: could not render PDF pages: {}", e.getMessage());
        }
        return pages;
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /**
     * OCR one already-rendered page. Returns {@code null} (never throws) on
     * any failure — missing tessdata, a platform whose native Tesseract lib
     * didn't load, a decode error — so a caller can treat "no text back" as
     * "OCR didn't help for this page" and move on to the next fallback tier.
     *
     * <p>Catches {@link Throwable}, not just {@link Exception}, deliberately:
     * a native-library load failure (missing/incompatible Tesseract binary
     * for this platform) surfaces as an {@link UnsatisfiedLinkError} /
     * {@link NoClassDefFoundError}, not a checked exception, and must not be
     * allowed to crash the request.
     */
    public String ocrPage(byte[] pngBytes) {
        try {
            ITesseract tesseract = newTesseract();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return null;
            String text = tesseract.doOCR(img);
            available = true;
            return text;
        } catch (Throwable t) {
            if (available == null || available) {
                log.warn("Sanction OCR: Tesseract unavailable or failed ({}); "
                        + "falling back to the existing AI/manual-entry path", t.getMessage());
            }
            available = false;
            return null;
        }
    }

    /** Whether the last OCR attempt (if any) actually ran. Purely informational/logging. */
    public boolean isAvailable() {
        return available != null && available;
    }

    private ITesseract newTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage("eng");
        return tesseract;
    }
}
