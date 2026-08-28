package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;

/**
 * Which proposals an order book may cite.
 *
 * Two traps are pinned here.
 *
 * The first: uploading an offline PDF force-sets the status to "Approved"
 * ({@code ProposalsService.updateOfflinePdf}), so status alone would let every
 * uploaded document into the dropdown — where selecting one silently yields an order
 * book with no line items.
 *
 * The second, and the one that actually bit: a proposal generated today by
 * {@code SolarProposalDocService} writes its document to {@code proposal_documents}
 * and leaves {@code bom_items} / {@code system_pricing} NULL. Testing only those JSON
 * columns rejects every modern generated proposal. The document row is the current
 * signal; the JSON is the legacy one; either counts.
 *
 * {@code hasGeneratedDocument} is the caller-resolved "has a proposal_documents row".
 */
class ProposalSourceTest {

    private static final String BOM = "[{\"item\":\"Module\",\"quantity\":120}]";
    private static final String PRICING = "{\"perKw\":42000}";

    private static final boolean HAS_DOC = true;
    private static final boolean NO_DOC  = false;

    private static ProposalsEntity proposal(String status, String bomItems, String systemPricing) {
        ProposalsEntity p = new ProposalsEntity();
        p.setId(1L);
        p.setProposalNo("PROP-2026-0001");
        p.setStatus(status);
        p.setBomItems(bomItems);
        p.setSystemPricing(systemPricing);
        return p;
    }

    // ── The current generator: a document row, no JSON on the proposal row ──────

    @Test
    void aGeneratedDocumentAloneMakesAnApprovedProposalEligible() {
        // Exactly what SolarProposalDocService produces: status flipped to Approved,
        // a proposal_documents row, and both JSON columns still null.
        assertTrue(ProposalSource.qualifiesForOrderBook(
            proposal("Approved", null, null), HAS_DOC));
    }

    @Test
    void aGeneratedDocumentWithASignedPdfAttachedAfterwardsStillQualifies() {
        ProposalsEntity p = proposal("Approved", null, null);
        p.setOfflinePdfName("signed-proposal.pdf");
        p.setOfflinePdfData(new byte[] { 1, 2, 3 });
        assertTrue(ProposalSource.qualifiesForOrderBook(p, HAS_DOC),
            "presence of generated content is the test, not absence of a PDF");
    }

    @Test
    void aGeneratedButUnapprovedProposalIsExcluded() {
        assertFalse(ProposalSource.qualifiesForOrderBook(proposal("Draft", null, null), HAS_DOC));
    }

    // ── The legacy builder: JSON on the proposal row, no document ───────────────

    @Test
    void legacyBuilderContentQualifiesWithoutADocumentRow() {
        assertTrue(ProposalSource.qualifiesForOrderBook(proposal("Approved", BOM, PRICING), NO_DOC));
    }

    @Test
    void eitherLegacyColumnAloneCounts() {
        assertTrue(ProposalSource.qualifiesForOrderBook(proposal("Approved", null, PRICING), NO_DOC));
        assertTrue(ProposalSource.qualifiesForOrderBook(proposal("Approved", BOM, null), NO_DOC));
    }

    @Test
    void aLegacyProposalWithAPdfAttachedAfterTheFactStillQualifies() {
        ProposalsEntity p = proposal("Approved", BOM, PRICING);
        p.setOfflinePdfName("signed-proposal.pdf");
        p.setOfflinePdfData(new byte[] { 1, 2, 3 });
        assertTrue(ProposalSource.qualifiesForOrderBook(p, NO_DOC));
    }

    // ── Uploaded documents ─────────────────────────────────────────────────────

    @Test
    void anUploadedPdfIsApprovedButNotSystemGenerated() {
        // updateOfflinePdf sets status = "Approved" on upload; nothing was generated.
        ProposalsEntity p = proposal("Approved", null, null);
        p.setOfflinePdfName("client-supplied.pdf");
        p.setOfflinePdfData(new byte[] { 1, 2, 3 });
        assertFalse(ProposalSource.qualifiesForOrderBook(p, NO_DOC));
    }

    // ── Status ─────────────────────────────────────────────────────────────────

    @Test
    void nonApprovedStatusesAreExcludedEvenWhenSystemGenerated() {
        assertFalse(ProposalSource.qualifiesForOrderBook(proposal("Draft", BOM, PRICING), HAS_DOC));
        assertFalse(ProposalSource.qualifiesForOrderBook(proposal("Rejected", BOM, PRICING), HAS_DOC));
        assertFalse(ProposalSource.qualifiesForOrderBook(proposal("Sent", BOM, PRICING), HAS_DOC));
        assertFalse(ProposalSource.qualifiesForOrderBook(proposal("On Hold", BOM, PRICING), HAS_DOC));
    }

    @Test
    void statusMatchingIgnoresCaseAndSurroundingSpace() {
        assertTrue(ProposalSource.qualifiesForOrderBook(proposal(" approved ", BOM, null), NO_DOC));
    }

    // ── Edges ──────────────────────────────────────────────────────────────────

    @Test
    void emptyJsonIsNotContent() {
        assertFalse(ProposalSource.isSystemGenerated(proposal("Approved", "[]", "{}"), NO_DOC));
        assertFalse(ProposalSource.isSystemGenerated(proposal("Approved", "   ", null), NO_DOC));
        assertFalse(ProposalSource.isSystemGenerated(proposal("Approved", "null", null), NO_DOC));
    }

    @Test
    void aSoftDeletedProposalNeverQualifies() {
        ProposalsEntity p = proposal("Approved", BOM, PRICING);
        p.setDeletedAt(LocalDateTime.now());
        assertFalse(ProposalSource.qualifiesForOrderBook(p, HAS_DOC));
    }

    @Test
    void nullsAreSafe() {
        assertFalse(ProposalSource.qualifiesForOrderBook(null, HAS_DOC));
        assertFalse(ProposalSource.isSystemGenerated(proposal(null, null, null), NO_DOC));
        assertFalse(ProposalSource.isApproved(proposal(null, BOM, null)));
    }
}
