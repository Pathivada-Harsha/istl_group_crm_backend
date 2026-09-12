package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, non-AI extraction of the Harmonized Master List PDF into
 * column-tagged {@link ExtractedRun}s, plus the document's SHA-256 hash for
 * change detection. No OCR, no LLM — pure PDFBox glyph positions.
 *
 * <p>A plain reading-order text dump (verified against the real 19-Sep-2025
 * and 11-Oct-2022 Gazette notifications with {@code pdftotext -layout})
 * shifts the Category↔sub-sector pairing by one row exactly at the page
 * break inside the Annexure-I table — a naive "bullet = current category"
 * line scanner would misattribute items across that boundary. Splitting each
 * visual line into column runs by horizontal glyph gap (this class) and
 * leaving column *interpretation* to {@link InfrastructureMasterListParser}
 * avoids that failure mode entirely, since the parser works from real x
 * positions instead of a re-flowed line order.
 */
@Component
public class InfrastructureMasterListPdfExtractor {

    /** Minimum horizontal gap (PDF points) between glyphs to start a new column run on a line. */
    private static final float COLUMN_GAP_PT = 18f;

    public record ExtractionResult(String documentHash, List<ExtractedRun> runs) {
    }

    public ExtractionResult extract(byte[] pdfBytes) throws IOException {
        List<ExtractedRun> runs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            RunCapturingStripper stripper = new RunCapturingStripper(runs);
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return new ExtractionResult(sha256(pdfBytes), runs);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Splits each PDFBox-detected line into column runs by horizontal gap.
     * PDFBox calls {@link #writeString(String, List)} once per visual line
     * with every glyph on that baseline (regardless of how far apart table
     * cells are) — this override is the documented extension point for
     * reconstructing table columns from that list.
     */
    private static final class RunCapturingStripper extends PDFTextStripper {
        private final List<ExtractedRun> out;
        private int currentPage = 0;

        RunCapturingStripper(List<ExtractedRun> out) throws IOException {
            this.out = out;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPage++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (textPositions == null || textPositions.isEmpty()) return;

            List<TextPosition> sorted = new ArrayList<>(textPositions);
            sorted.sort(Comparator.comparing(TextPosition::getXDirAdj));

            StringBuilder run = new StringBuilder();
            float runStartX = sorted.get(0).getXDirAdj();
            float runY = sorted.get(0).getYDirAdj();
            float lastEndX = runStartX;

            for (TextPosition tp : sorted) {
                float x = tp.getXDirAdj();
                if (run.length() > 0 && (x - lastEndX) > COLUMN_GAP_PT) {
                    flush(run, runStartX, runY);
                    run.setLength(0);
                    runStartX = x;
                }
                run.append(tp.getUnicode());
                lastEndX = x + tp.getWidthDirAdj();
            }
            flush(run, runStartX, runY);
        }

        private void flush(StringBuilder run, float startX, float y) {
            String text = run.toString().trim();
            if (!text.isEmpty()) {
                out.add(new ExtractedRun(currentPage, startX, y, text));
            }
        }
    }
}
