package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureMasterListPdfExtractorTest {

    private final InfrastructureMasterListPdfExtractor extractor = new InfrastructureMasterListPdfExtractor();

    @Test
    void hashIsDeterministicAndMatchesRawShaOfBytes() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = readResource("hml-2025-19-09.pdf");

        InfrastructureMasterListPdfExtractor.ExtractionResult first = extractor.extract(bytes);
        InfrastructureMasterListPdfExtractor.ExtractionResult second = extractor.extract(bytes);

        assertEquals(first.documentHash(), second.documentHash(), "hash must be stable across repeated extraction");
        assertEquals(rawSha256Hex(bytes), first.documentHash());
    }

    @Test
    void twoDifferentDocumentsHaveDifferentHashes() throws IOException {
        byte[] a = readResource("hml-2025-19-09.pdf");
        byte[] b = readResource("hml-2022-11-10.pdf");

        InfrastructureMasterListPdfExtractor.ExtractionResult resultA = extractor.extract(a);
        InfrastructureMasterListPdfExtractor.ExtractionResult resultB = extractor.extract(b);

        assertFalse(resultA.documentHash().equals(resultB.documentHash()));
    }

    @Test
    void extractsColumnSeparatedRunsFromARealTableRow() throws IOException {
        byte[] bytes = readResource("hml-2025-19-09.pdf");
        InfrastructureMasterListPdfExtractor.ExtractionResult result = extractor.extract(bytes);
        List<ExtractedRun> runs = result.runs();

        assertFalse(runs.isEmpty());
        // The Sr No, Category and first bullet item of row 1 land on the same
        // baseline but must come out as separate runs at increasing x, not
        // fused into one string — that per-run x is what the parser depends on.
        boolean foundSeparateCategoryAndBulletOnSameLine = false;
        for (int i = 0; i < runs.size() - 1; i++) {
            ExtractedRun r1 = runs.get(i);
            ExtractedRun r2 = runs.get(i + 1);
            if (r1.page() == r2.page() && Math.abs(r1.y() - r2.y()) < 0.5f
                    && r1.text().contains("Transport") && r2.minX() > r1.minX()) {
                foundSeparateCategoryAndBulletOnSameLine = true;
                break;
            }
        }
        assertTrue(foundSeparateCategoryAndBulletOnSameLine,
                "expected the Category and following column to be split into separate runs on row 1");
    }

    private byte[] readResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/infrastructure/" + name)) {
            if (in == null) throw new IOException("Missing test fixture: " + name);
            return in.readAllBytes();
        }
    }

    private static String rawSha256Hex(byte[] data) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
