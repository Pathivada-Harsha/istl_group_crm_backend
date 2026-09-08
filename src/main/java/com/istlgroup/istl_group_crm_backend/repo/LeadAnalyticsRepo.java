package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;

/**
 * Read-only aggregate queries for the Analytics page. All queries scope to a
 * [from, to] created_at window and ignore soft-deleted leads (deleted_at IS NULL).
 * Conversion is defined as status = 'Closed Won'.
 */
public interface LeadAnalyticsRepo extends JpaRepository<LeadsEntity, Long> {

    // ── Team scoping ─────────────────────────────────────────────────
    // Every lead aggregate below is restricted to the viewer's reporting subtree
    // using the SAME ownership rule DashboardRepo documents, so Analytics and the
    // Dashboard can never disagree about the same person's numbers:
    //
    //     assigned_to IN ids OR bd_assigned_to IN ids OR closed_by_user_id IN ids
    //
    // created_by is deliberately NOT part of it — it records who typed the lead
    // into the form, not who holds it.
    //
    // :allScope is 1 for a top-level viewer (UserScopeService.isTopLevel) and
    // short-circuits the predicate, so admins keep seeing company-wide figures
    // INCLUDING unassigned leads — which an id list alone could never match.
    // :userIds is never empty (a leaf user resolves to themselves), so IN (...)
    // cannot degenerate into invalid SQL.
    String SCOPE   = "AND (:allScope = 1 OR (assigned_to IN (:userIds) "
                   + "OR bd_assigned_to IN (:userIds) OR closed_by_user_id IN (:userIds))) ";
    String SCOPE_L = "AND (:allScope = 1 OR (l.assigned_to IN (:userIds) "
                   + "OR l.bd_assigned_to IN (:userIds) OR l.closed_by_user_id IN (:userIds))) ";

    // ── Per-subgroup: total + won (how many leads came to each subgroup and
    //    how many converted). Falls back to group_name only when subgroup blank.
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(sub_group_name),''), NULLIF(TRIM(group_name),''), 'Unknown') AS label, " +
            "COUNT(*) AS total, SUM(CASE WHEN LOWER(TRIM(status))='closed won' THEN 1 ELSE 0 END) AS won " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "GROUP BY label ORDER BY total DESC", nativeQuery = true)
    List<Object[]> groupTotalsAndWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Per-employee handling: leads handled, won, and conversion ─────────────
    //    Attribution matches the Team Lead Performance page (teamLeadBreakdown):
    //      HANDLED = assigned_to = u.id OR closed_by_user_id = u.id (DISTINCT)
    //      WON     = Closed Won; closer gets the win, assignee only when no closer
    //    Kept date-bounded on created_at so the Analytics range selector applies
    //    (equals Team Lead Performance at all-time, narrows for shorter ranges).
    @Query(value =
        "SELECT u.id, u.name, " +
        "  (SELECT COUNT(DISTINCT lh.id) FROM leads lh " +
        "     WHERE lh.deleted_at IS NULL " +
        "       AND lh.created_at >= :from AND lh.created_at < :to " +
        "       AND (lh.assigned_to = u.id OR lh.closed_by_user_id = u.id)) AS handled, " +
        "  (SELECT COUNT(DISTINCT lw.id) FROM leads lw " +
        "     WHERE lw.deleted_at IS NULL " +
        "       AND lw.created_at >= :from AND lw.created_at < :to " +
        "       AND LOWER(TRIM(lw.status)) = 'closed won' " +
        "       AND (lw.closed_by_user_id = u.id " +
        "            OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id))) AS won " +
        "FROM users u " +
        // Scoped by WHICH PEOPLE are listed: each subquery above already counts only
        // that user's own leads, so restricting the user set is the whole filter.
        "WHERE u.is_active = 1 AND (:allScope = 1 OR u.id IN (:userIds)) " +
        "HAVING handled > 0 " +
        "ORDER BY handled DESC LIMIT 12", nativeQuery = true)
    List<Object[]> employeeHandling(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Per-person lead breakdown for the Team Lead Performance page.
    //    Each metric counts a DIFFERENT person-field so management can see who
    //    created vs owns vs handles vs closed each lead — these are distinct.
    //    Scoped to a set of user ids (the viewer's allowed team).
    //
    //    HANDLED: assigned_to = u.id  OR  closed_by_user_id = u.id
    //      → if you were assigned OR you personally closed it, you handled it.
    //        Managers/seniors who close a junior's lead get credited too.
    //        Two users can both be credited for handling the same lead.
    //
    //    WON credit uses priority — single credit per lead, no double-count:
    //      • closed_by_user_id IS SET  → the closer gets the win
    //      • closed_by_user_id IS NULL → the current assignee gets the win
    //
    //    Conv% = Won / Handled — reflects how many of the leads a person was
    //    involved with they actually converted.
    //
    //    EVERY metric is bounded by [from, to) on the lead's created_at, so the
    //    same query serves the Team Lead Performance page (called with an
    //    all-time window) and the analytics report's Team Performance section
    //    (called with whatever range is selected). There is no second per-person
    //    query to drift out of step with this one.
    @Query(value =
        "SELECT u.id, u.name, u.role, " +
        "  (SELECT COUNT(*) FROM leads lc WHERE lc.deleted_at IS NULL " +
        "   AND lc.created_at >= :from AND lc.created_at < :to " +
        "   AND lc.created_by = u.id) AS created, " +
        // handled = assigned to this user OR closed by this user (DISTINCT to avoid double-count within this metric)
        "  (SELECT COUNT(DISTINCT la.id) FROM leads la WHERE la.deleted_at IS NULL " +
        "   AND la.created_at >= :from AND la.created_at < :to " +
        "   AND (la.assigned_to = u.id OR la.closed_by_user_id = u.id)) AS assigned, " +
        "  (SELECT COUNT(*) FROM leads lo WHERE lo.deleted_at IS NULL " +
        "   AND lo.created_at >= :from AND lo.created_at < :to " +
        "   AND TRIM(lo.lead_owner) = TRIM(u.name)) AS owned, " +
        // won = Closed Won, closer gets the win; assignee gets it only when no closer is recorded
        "  (SELECT COUNT(DISTINCT lw.id) FROM leads lw WHERE lw.deleted_at IS NULL " +
        "   AND lw.created_at >= :from AND lw.created_at < :to " +
        "   AND lw.status = 'Closed Won' " +
        "   AND (lw.closed_by_user_id = u.id " +
        "        OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id))) AS won " +
        "FROM users u " +
        "WHERE u.is_active = 1 AND u.id IN (:userIds) " +
        "ORDER BY assigned DESC, created DESC", nativeQuery = true)
    List<Object[]> teamLeadBreakdown(@Param("userIds") List<Long> userIds,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT id FROM users WHERE is_active = 1", nativeQuery = true)
    List<Long> findAllActiveUserIds();

    // ── Headline counts ──────────────────────────────────────────────────────
    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE, nativeQuery = true)
    long countGenerated(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "AND LOWER(TRIM(status)) = 'closed won'", nativeQuery = true)
    long countWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "AND LOWER(TRIM(status)) IN ('closed won','closed lost')", nativeQuery = true)
    long countClosed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Breakdown by status (the funnel / distribution) ──────────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(status),''),'Unknown') AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "GROUP BY label ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> countByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Breakdown by source ───────────────────────────────────────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(source),''),'Unknown') AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "GROUP BY label ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> countBySource(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Per-priority: total + won (drives weighted conversion) ────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(priority),''),'Unknown') AS label, " +
            "COUNT(*) AS total, SUM(CASE WHEN LOWER(TRIM(status))='closed won' THEN 1 ELSE 0 END) AS won " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "GROUP BY label", nativeQuery = true)
    List<Object[]> priorityTotalsAndWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Time series (daily grain). We aggregate per-DAY in SQL and let the
    //    service roll the days up into day/week/month buckets — this avoids
    //    fragile SQL-vs-Java bucket-key format matching and keeps label control
    //    (month names, full year) entirely in Java.
    //
    //    GENERATED is keyed by created_at; WON is keyed by the date the lead
    //    actually became Closed Won (see dailyWonByWinDate), so a lead created
    //    in one bucket but won in another shows Generated and Won in the right
    //    periods (rather than both in the creation bucket).

    // Leads generated per day.
    @Query(value = "SELECT DATE(created_at) AS d, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "GROUP BY DATE(created_at)", nativeQuery = true)
    List<Object[]> dailyGenerated(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // Wins per day, keyed by WIN DATE. Win date = the FIRST Closed-Won transition
    // in lead_history; if a Closed-Won lead has no such history row, fall back to
    // its created_at. Only wins whose win date falls in [from, to) are counted.
    @Query(value =
        "SELECT DATE(win_date) AS d, COUNT(*) AS cnt FROM ( " +
        "  SELECT l.id AS lid, " +
        "    COALESCE(MIN(CASE WHEN (h.action_type='STATUS_CHANGED' AND h.new_value='Closed Won') " +
        "                       OR h.action_type IN ('CONVERTED_TO_CUSTOMER','CONVERTED') " +
        "                  THEN h.created_at END), l.created_at) AS win_date " +
        "  FROM leads l LEFT JOIN lead_history h ON h.lead_id = l.id " +
        "  WHERE l.deleted_at IS NULL AND LOWER(TRIM(l.status)) = 'closed won' " + SCOPE_L +
        "  GROUP BY l.id, l.created_at " +
        ") t WHERE t.win_date >= :from AND t.win_date < :to " +
        "GROUP BY DATE(win_date)", nativeQuery = true)
    List<Object[]> dailyWonByWinDate(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Top states (geographic spread) ────────────────────────────────────────
    // ── Top states (geographic spread). Excludes leads with no state captured
    //    so the chart shows only real locations, not an 'Unknown' bucket. Groups
    //    case-insensitively so 'Telangana' and 'telangana' merge (does not fix
    //    spelling variants like 'Andhra Pradesh' vs 'Andhrapradesh').
    @Query(value = "SELECT MIN(TRIM(state)) AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " + SCOPE +
            "AND state IS NOT NULL AND TRIM(state) <> '' " +
            "GROUP BY LOWER(TRIM(state)) ORDER BY cnt DESC LIMIT 8", nativeQuery = true)
    List<Object[]> countByState(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);

    // ── Time-to-convert: minutes between lead creation and the FIRST transition
    //    to Closed Won, per lead, using lead_history. Minute precision (÷1440 in
    //    the service) so sub-hour conversions are not truncated to 0. Leads
    //    created already-won resolve to ~0. Only leads whose creation falls in
    //    the window count.
    @Query(value =
        "SELECT l.id, " +
        "  TIMESTAMPDIFF(MINUTE, l.created_at, MIN(h.created_at)) AS minutes " +
        "FROM leads l " +
        "JOIN lead_history h ON h.lead_id = l.id " +
        "WHERE l.deleted_at IS NULL AND l.created_at >= :from AND l.created_at < :to " + SCOPE_L +
        "  AND LOWER(TRIM(l.status)) = 'closed won' " +
        "  AND ( (h.action_type = 'STATUS_CHANGED' AND h.new_value = 'Closed Won') " +
        "        OR h.action_type IN ('CONVERTED_TO_CUSTOMER','CONVERTED') ) " +
        "GROUP BY l.id, l.created_at", nativeQuery = true)
    List<Object[]> minutesToConvertPerLead(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userIds") List<Long> userIds, @Param("allScope") int allScope);
}