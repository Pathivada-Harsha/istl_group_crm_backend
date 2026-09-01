package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;

/**
 * Where a proposal came from, and whether it may back an order book.
 *
 * <p>There is no stored flag for "built in the CRM", and the signal differs by era:
 *
 * <ul>
 *   <li><b>Current</b> — a row in {@code proposal_documents}. The solar generator
 *       ({@code SolarProposalDocService}) writes the .docx/.pdf there and leaves the
 *       proposal row's JSON columns null, so this is the only signal for anything
 *       generated today. It is also exactly what the lead page's "System generated"
 *       badge tests, so the dropdown and the badge agree by construction.</li>
 *   <li><b>Legacy</b> — {@code system_pricing} / {@code bom_items} JSON on the
 *       proposal row, written by the older in-app builder.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> the absence of an uploaded PDF: a signed PDF is routinely
 * attached to a system-generated proposal after the fact (see
 * {@code ProposalsService.updateOfflinePdf}) and that must not disqualify it.
 *
 * <p>Note that {@code updateOfflinePdf} also force-sets the status to "Approved", so
 * an approved status on its own says nothing about the origin — both halves of
 * {@link #qualifiesForOrderBook} are load-bearing.
 *
 * <p>The document check needs a repo, so callers resolve it once (in bulk, via
 * {@code ProposalDocumentRepo.findProposalIdsWithDocuments}) and pass the answer in
 * as {@code hasGeneratedDocument} — never a per-row query, and never an entity load:
 * {@code ProposalDocumentEntity} drags ~10 MB of blobs.
 */
public final class ProposalSource {

    private ProposalSource() { }

    /**
     * Built inside the CRM rather than supplied as a document.
     *
     * @param hasGeneratedDocument whether this proposal has a {@code proposal_documents}
     *                             row, resolved by the caller
     */
    public static boolean isSystemGenerated(ProposalsEntity p, boolean hasGeneratedDocument) {
        if (p == null) return false;
        return hasGeneratedDocument || hasLegacyBuilderContent(p);
    }

    /** The legacy in-app builder's structured content on the proposal row itself. */
    public static boolean hasLegacyBuilderContent(ProposalsEntity p) {
        return p != null && (hasJsonContent(p.getBomItems()) || hasJsonContent(p.getSystemPricing()));
    }

    public static boolean isApproved(ProposalsEntity p) {
        return p != null && p.getStatus() != null && "Approved".equalsIgnoreCase(p.getStatus().trim());
    }

    /** Live, approved and system-generated — the only proposals an order book may cite. */
    public static boolean qualifiesForOrderBook(ProposalsEntity p, boolean hasGeneratedDocument) {
        return p != null && p.getDeletedAt() == null
            && isApproved(p) && isSystemGenerated(p, hasGeneratedDocument);
    }

    /** Non-blank JSON that actually carries something — an empty array/object does not count. */
    private static boolean hasJsonContent(String json) {
        if (json == null) return false;
        String t = json.trim();
        return !t.isEmpty() && !"[]".equals(t) && !"{}".equals(t) && !"null".equalsIgnoreCase(t);
    }
}
