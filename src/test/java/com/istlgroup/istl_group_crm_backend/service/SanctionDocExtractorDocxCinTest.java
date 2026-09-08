package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/**
 * A .docx letter that interleaves a table (the lender's CIN) between
 * paragraphs, mirroring a letterhead/addressee block laid out with a table —
 * common in real Word sanction letters. {@code flattenDocxText} must walk the
 * document's actual body order (paragraphs and tables as they appear) rather
 * than all paragraphs followed by all tables, or the lender's CIN row gets
 * detached from the "bank"-flagged paragraph right above it and ends up
 * misread as the borrower's.
 */
class SanctionDocExtractorDocxCinTest {

    private final SanctionDocExtractor extractor = new SanctionDocExtractor();

    private static final String LENDER_CIN = "U65990TG1994PLC987654";
    private static final String BORROWER_CIN = "U40106MH2026PTC223978";

    @Test
    void lenderCinInTableBetweenParagraphs_selectsBorrowerCin() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            addParagraph(doc, "RASHTRA VIKAS BANK LIMITED");

            XWPFTable lenderTable = doc.createTable(1, 1);
            lenderTable.getRow(0).getCell(0).setText("CIN: " + LENDER_CIN);

            addParagraph(doc, "Ref. No.: RVB/CIB/SAN/2026-27/015446");
            addParagraph(doc, "Date: July 5, 2026");
            addParagraph(doc, "PRIVATE & CONFIDENTIAL");
            addParagraph(doc, "To,");
            addParagraph(doc, "The Board of Directors");
            addParagraph(doc, "Marutha Dhule Wind-Solar Hybrid Power Private Limited");
            addParagraph(doc, "CIN: " + BORROWER_CIN);
            addParagraph(doc, "Subject: Intimation of sanction of Rupee Term Loan of Rs. 233.28 Crore");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            bytes = bos.toByteArray();
        }

        Map<String, Object> out = extractor.extractDocx(bytes);
        assertEquals(BORROWER_CIN, out.get("cin"));
    }

    private static void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
    }
}
