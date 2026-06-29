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

    // ── Per-subgroup: total + won (how many leads came to each subgroup and
    //    how many converted). Falls back to group_name only when subgroup blank.
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(sub_group_name),''), NULLIF(TRIM(group_name),''), 'Unknown') AS label, " +
            "COUNT(*) AS total, SUM(CASE WHEN status='Closed Won' THEN 1 ELSE 0 END) AS won " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "GROUP BY label ORDER BY total DESC", nativeQuery = true)
    List<Object[]> groupTotalsAndWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Per-employee handling: leads assigned, won, and conversion ────────────
    @Query(value =
        "SELECT u.id, u.name, " +
        "  COUNT(l.id) AS handled, " +
        "  SUM(CASE WHEN l.status='Closed Won' THEN 1 ELSE 0 END) AS won " +
        "FROM users u " +
        "JOIN leads l ON l.assigned_to = u.id AND l.deleted_at IS NULL " +
        "  AND l.created_at >= :from AND l.created_at < :to " +
        "WHERE u.is_active = 1 " +
        "GROUP BY u.id, u.name " +
        "HAVING handled > 0 " +
        "ORDER BY handled DESC LIMIT 12", nativeQuery = true)
    List<Object[]> employeeHandling(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

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
    @Query(value =
        "SELECT u.id, u.name, u.role, " +
        "  (SELECT COUNT(*) FROM leads lc WHERE lc.deleted_at IS NULL AND lc.created_by = u.id) AS created, " +
        // handled = assigned to this user OR closed by this user (DISTINCT to avoid double-count within this metric)
        "  (SELECT COUNT(DISTINCT la.id) FROM leads la WHERE la.deleted_at IS NULL " +
        "   AND (la.assigned_to = u.id OR la.closed_by_user_id = u.id)) AS assigned, " +
        "  (SELECT COUNT(*) FROM leads lo WHERE lo.deleted_at IS NULL AND TRIM(lo.lead_owner) = TRIM(u.name)) AS owned, " +
        // won = Closed Won, closer gets the win; assignee gets it only when no closer is recorded
        "  (SELECT COUNT(DISTINCT lw.id) FROM leads lw WHERE lw.deleted_at IS NULL " +
        "   AND lw.status = 'Closed Won' " +
        "   AND (lw.closed_by_user_id = u.id " +
        "        OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id))) AS won " +
        "FROM users u " +
        "WHERE u.is_active = 1 AND u.id IN (:userIds) " +
        "ORDER BY created DESC, assigned DESC", nativeQuery = true)
    List<Object[]> teamLeadBreakdown(@Param("userIds") List<Long> userIds);

    @Query(value = "SELECT id FROM users WHERE is_active = 1", nativeQuery = true)
    List<Long> findAllActiveUserIds();

    // ── Headline counts ──────────────────────────────────────────────────────
    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to", nativeQuery = true)
    long countGenerated(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "AND status = 'Closed Won'", nativeQuery = true)
    long countWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COUNT(*) FROM leads " +
            "WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "AND status IN ('Closed Won','Closed Lost')", nativeQuery = true)
    long countClosed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Breakdown by status (the funnel / distribution) ──────────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(status),''),'Unknown') AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "GROUP BY label ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> countByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Breakdown by source ───────────────────────────────────────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(source),''),'Unknown') AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "GROUP BY label ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> countBySource(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Per-priority: total + won (drives weighted conversion) ────────────────
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(priority),''),'Unknown') AS label, " +
            "COUNT(*) AS total, SUM(CASE WHEN status='Closed Won' THEN 1 ELSE 0 END) AS won " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "GROUP BY label", nativeQuery = true)
    List<Object[]> priorityTotalsAndWon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Monthly time series: generated vs won, last 12 months from :to ────────
    // Avoid DATE_FORMAT here: its '%Y-%m' literal collides with Spring's native
    // parameter parsing. Build the 'YYYY-MM' bucket from YEAR()/MONTH() instead,
    // which contains no percent signs.
    @Query(value = "SELECT CONCAT(YEAR(created_at), '-', LPAD(MONTH(created_at), 2, '0')) AS ym, " +
            "COUNT(*) AS gen_count, " +
            "SUM(CASE WHEN status='Closed Won' THEN 1 ELSE 0 END) AS won_count " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "GROUP BY ym ORDER BY ym", nativeQuery = true)
    List<Object[]> monthlySeries(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Top states (geographic spread) ────────────────────────────────────────
    // ── Top states (geographic spread). Excludes leads with no state captured
    //    so the chart shows only real locations, not an 'Unknown' bucket. Groups
    //    case-insensitively so 'Telangana' and 'telangana' merge (does not fix
    //    spelling variants like 'Andhra Pradesh' vs 'Andhrapradesh').
    @Query(value = "SELECT MIN(TRIM(state)) AS label, COUNT(*) AS cnt " +
            "FROM leads WHERE deleted_at IS NULL AND created_at >= :from AND created_at < :to " +
            "AND state IS NOT NULL AND TRIM(state) <> '' " +
            "GROUP BY LOWER(TRIM(state)) ORDER BY cnt DESC LIMIT 8", nativeQuery = true)
    List<Object[]> countByState(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Time-to-convert: days between lead creation and the FIRST transition to
    //    Closed Won, per lead, using lead_history. Leads created already-won
    //    resolve to ~0 days. Only leads whose creation falls in the window count.
    @Query(value =
        "SELECT l.id, " +
        "  TIMESTAMPDIFF(HOUR, l.created_at, MIN(h.created_at)) AS hours " +
        "FROM leads l " +
        "JOIN lead_history h ON h.lead_id = l.id " +
        "WHERE l.deleted_at IS NULL AND l.created_at >= :from AND l.created_at < :to " +
        "  AND l.status = 'Closed Won' " +
        "  AND ( (h.action_type = 'STATUS_CHANGED' AND h.new_value = 'Closed Won') " +
        "        OR h.action_type IN ('CONVERTED_TO_CUSTOMER','CONVERTED') ) " +
        "GROUP BY l.id, l.created_at", nativeQuery = true)
    List<Object[]> hoursToConvertPerLead(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}