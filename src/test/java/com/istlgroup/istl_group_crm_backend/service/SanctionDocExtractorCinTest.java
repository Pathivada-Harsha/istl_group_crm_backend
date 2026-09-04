package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Role-aware borrower-CIN selection: a sanction letter can print both the
 * lender's own CIN (letterhead) and the borrower's (addressee block) — only
 * the latter must ever land in the "cin" field.
 */
class SanctionDocExtractorCinTest {

    private final SanctionDocExtractor extractor = new SanctionDocExtractor();

    private static final String LENDER_CIN = "U65990TG1994PLC987654";
    private static final String BORROWER_CIN = "U40106MH2026PTC223978";

    /** Case 1 / 3 / 6: bank CIN in the letterhead, borrower CIN in the addressee block. */
    @Test
    void bankCinAndBorrowerCin_selectsBorrower() {
        String text = String.join("\n",
                "RASHTRA VIKAS BANK LIMITED",
                "Corporate & Infrastructure Banking Group",
                "RVB Towers, Road No. 2, Banjara Hills, Hyderabad - 500 034, Telangana",
                "CIN: " + LENDER_CIN,
                "Ref. No.: RVB/CIB/SAN/2026-27/015446",
                "Date: July 5, 2026",
                "PRIVATE & CONFIDENTIAL",
                "To,",
                "The Board of Directors",
                "Marutha Dhule Wind-Solar Hybrid Power Private Limited",
                "Dhule District, Maharashtra",
                "CIN: " + BORROWER_CIN,
                "Subject: Intimation of sanction of Rupee Term Loan of Rs. 233.28 Crore");

        Map<String, Object> out = extractor.extractFromPdfText(text);
        assertEquals(BORROWER_CIN, out.get("cin"));
    }

    /** Case 2: only the borrower's CIN is present at all. */
    @Test
    void onlyBorrowerCin_selectsIt() {
        String text = String.join("\n",
                "To,",
                "The Board of Directors",
                "Some Standalone Company Private Limited",
                "CIN: " + BORROWER_CIN,
                "Subject: Intimation of sanction");

        Map<String, Object> out = extractor.extractFromPdfText(text);
        assertEquals(BORROWER_CIN, out.get("cin"));
    }

    /** Case 6 in isolation: a prominent bank CIN with no borrower CIN anywhere must never be used. */
    @Test
    void onlyLenderCin_neverSelected() {
        String text = String.join("\n",
                "RASHTRA VIKAS BANK LIMITED",
                "Corporate & Infrastructure Banking Group",
                "CIN: " + LENDER_CIN,
                "Subject: Intimation of sanction");

        Map<String, Object> out = extractor.extractFromPdfText(text);
        assertNull(out.get("cin"));
    }
}
