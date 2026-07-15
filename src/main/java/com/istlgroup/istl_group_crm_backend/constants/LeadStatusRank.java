package com.istlgroup.istl_group_crm_backend.constants;

import java.util.Map;

/**
 * Single source of truth for the lead funnel ORDER on the backend — used ONLY to
 * detect a "downgrade" (a backward move in the pipeline, e.g. Proposal Sent →
 * Interested), which requires a reason.
 *
 * Mirrors src/constants/leadStatus.js on the frontend; keep both in sync.
 *
 * Deliberately UNRANKED (never a downgrade):
 *   • "Keep in View" — a legitimate HOLD at any stage (client asks for more time
 *     after a proposal). Moving in/out of it is not a regression.
 *   • "Closed Lost"  — the CORRECT terminal exit after a proposal; it already
 *     captures its own mandatory closedLostReason.
 *
 * "Not Interested" IS ranked (below "Interested") on purpose: Proposal Sent →
 * Not Interested doesn't make sense (that should be Closed Lost), so it must be
 * justified — it records BOTH the downgrade reason and its own
 * notInterestedReason.
 */
public final class LeadStatusRank {

    private LeadStatusRank() {
        // constants holder — not instantiable
    }

    /** Funnel rank — higher means further along. Absent = unranked (see above). */
    public static final Map<String, Integer> STATUS_RANK = Map.of(
        "new",            1,
        "not responded",  1,
        "not interested", 1,
        "interested",     2,
        "prospect",       2,
        "contacted",      3,
        "in discussion",  4,
        "proposal sent",  5,
        "closed won",     6
    );

    /**
     * Canonicalises a status so BOTH conventions resolve to the same key:
     *   • main leads page  → title case  ("Not Responded")
     *   • telecaller view  → UPPER_SNAKE ("NOT_RESPONDED")
     *
     * @return the funnel rank, or null when the status is blank/unranked.
     */
    public static Integer rankOf(String status) {
        if (status == null) return null;
        String key = status.trim().toLowerCase().replace('_', ' ');
        if (key.isEmpty()) return null;
        return STATUS_RANK.get(key);
    }

    /**
     * True only when BOTH statuses are ranked (i.e. active/positive funnel stages)
     * and the new one sits earlier in the funnel than the old one. Forward moves,
     * lateral moves (equal rank) and anything involving an unranked negative exit
     * return false.
     */
    public static boolean isDowngrade(String oldStatus, String newStatus) {
        Integer oldRank = rankOf(oldStatus);
        Integer newRank = rankOf(newStatus);
        return oldRank != null && newRank != null && newRank < oldRank;
    }
}
