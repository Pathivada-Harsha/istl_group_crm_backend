package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * All dashboard aggregation queries live here.
 *
 * REVENUE FIX (2026-04-23):
 *   Revenue is now attributed to the employee who CLOSED (won) the lead
 *   (leads.closed_by_user_id), NOT to who created the order_book entry.
 *   Accounts team entering data will no longer incorrectly receive revenue credit.
 *
 *   Logic:
 *     - If leads.closed_by_user_id IS NOT NULL → credit that user
 *     - Else fall back to leads.assigned_to (BD executive who owned the lead)
 *
 * Design rationale:
 *  - Every KPI is a single COUNT/SUM native SQL query — no loading full entity lists.
 *  - Table rows are fetched with LIMIT so they are always fast.
 *  - Complex team-stat aggregations are done in one GROUP BY query.
 *  - All queries filter deleted_at IS NULL from the start.
 */
@Repository
public interface DashboardRepo extends JpaRepository<LeadsEntity, Long> {

    // ══════════════════════════════════════════════════════════════════════════
    // GLOBAL KPI COUNTS (used by Admin dashboard)
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = "SELECT COUNT(*) FROM leads WHERE deleted_at IS NULL", nativeQuery = true)
    long countTotalLeads();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND created_at >= :startOfMonth
        """, nativeQuery = true)
    long countLeadsThisMonth(@Param("startOfMonth") LocalDateTime startOfMonth);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND LOWER(status) = 'closed won'
        """, nativeQuery = true)
    long countClosedWon();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND LOWER(status) NOT IN ('closed won','closed lost')
        """, nativeQuery = true)
    long countActiveLeads();

    @Query(value = "SELECT COUNT(*) FROM proposals WHERE deleted_at IS NULL", nativeQuery = true)
    long countTotalProposals();

    @Query(value = "SELECT COUNT(*) FROM order_book WHERE deleted_at IS NULL", nativeQuery = true)
    long countTotalOrders();

    @Query(value = """
        SELECT COALESCE(SUM(total_amount), 0) FROM order_book WHERE deleted_at IS NULL
        """, nativeQuery = true)
    java.math.BigDecimal sumOrderBookValue();

    // ── Funnel counts ──────────────────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND LOWER(status) = 'contacted'
        """, nativeQuery = true)
    long countContacted();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND LOWER(status) = 'in discussion'
        """, nativeQuery = true)
    long countInDiscussion();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND LOWER(status) = 'proposal sent'
        """, nativeQuery = true)
    long countProposalSent();

    // ── Follow-up counts (global, for Admin) ──────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending'
        """, nativeQuery = true)
    long countPendingFollowupsGlobal();

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending' AND scheduled_at < :now
        """, nativeQuery = true)
    long countOverdueFollowupsGlobal(@Param("now") LocalDateTime now);

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending'
          AND DATE(scheduled_at) = DATE(:today)
        """, nativeQuery = true)
    long countTodayFollowupsGlobal(@Param("today") LocalDateTime today);

    // ── Follow-up counts (per user) ────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE assigned_to = :userId AND status = 'Pending'
        """, nativeQuery = true)
    long countPendingFollowupsForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE assigned_to = :userId AND status = 'Pending'
          AND scheduled_at < :now
        """, nativeQuery = true)
    long countOverdueFollowupsForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE assigned_to = :userId AND status = 'Pending'
          AND DATE(scheduled_at) = DATE(:today)
        """, nativeQuery = true)
    long countTodayFollowupsForUser(@Param("userId") Long userId, @Param("today") LocalDateTime today);

    // ══════════════════════════════════════════════════════════════════════════
    // MONTHLY LEADS CHART  (last 6 months, grouped by month)
    // Returns rows: [year, month, count]
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT YEAR(created_at)  AS yr,
               MONTH(created_at) AS mo,
               COUNT(*)          AS cnt
        FROM leads
        WHERE deleted_at IS NULL
          AND created_at >= :since
        GROUP BY YEAR(created_at), MONTH(created_at)
        ORDER BY yr, mo
        """, nativeQuery = true)
    List<Object[]> countLeadsGroupedByMonth(@Param("since") LocalDateTime since);

    // ── Monthly leads for a specific manager's team ───────────────────────────

    @Query(value = """
        SELECT YEAR(l.created_at)  AS yr,
               MONTH(l.created_at) AS mo,
               COUNT(*)            AS cnt
        FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.assigned_to IN (
              SELECT id FROM users WHERE is_active = 1 AND created_by = :managerId
          )
          AND l.created_at >= :since
        GROUP BY YEAR(l.created_at), MONTH(l.created_at)
        ORDER BY yr, mo
        """, nativeQuery = true)
    List<Object[]> countLeadsGroupedByMonthForManager(
            @Param("managerId") Long managerId,
            @Param("since") LocalDateTime since);

    // ══════════════════════════════════════════════════════════════════════════
    // TEAM PERFORMANCE  (Admin view — all users)
    //
    // REVENUE FIX: Revenue is SUM of order_book entries linked to leads that
    //   this user CLOSED (closed_by_user_id = u.id), or where
    //   closed_by_user_id IS NULL and the lead is assigned_to = u.id.
    //   This ensures accounts staff entering orders are NOT credited.
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            u.id                                                     AS userId,
            u.name                                                   AS userName,
            u.role                                                   AS userRole,
            COUNT(DISTINCT l.id)                                     AS leadsHandled,
            COUNT(DISTINCT CASE
                WHEN LOWER(lw.status)='closed won' THEN lw.id END)  AS leadsWon,
            COUNT(DISTINCT CASE WHEN UPPER(l.telecaller_status)='INTERESTED'
                                THEN l.id END)                       AS interested,
            COUNT(DISTINCT p.id)                                     AS proposalsSent,
            COALESCE((
                SELECT SUM(ob2.total_amount)
                FROM order_book ob2
                JOIN leads lwon2 ON lwon2.id = ob2.lead_id AND lwon2.deleted_at IS NULL
                WHERE ob2.deleted_at IS NULL
                  AND LOWER(lwon2.status) = 'closed won'
                  AND (
                      lwon2.closed_by_user_id = u.id
                      OR (lwon2.closed_by_user_id IS NULL AND lwon2.assigned_to = u.id)
                  )
            ), 0)                                                    AS revenue,
            COUNT(DISTINCT CASE WHEN f.status='Completed'
                                THEN f.id END)                       AS followupsDone,
            COUNT(DISTINCT CASE WHEN f.status='Pending'
                                THEN f.id END)                       AS followupsPending
        FROM users u
        LEFT JOIN leads     l  ON l.assigned_to = u.id AND l.deleted_at IS NULL
        LEFT JOIN leads     lw ON lw.deleted_at IS NULL
                               AND LOWER(lw.status) = 'closed won'
                               AND (
                                   lw.closed_by_user_id = u.id
                                   OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id)
                               )
        LEFT JOIN proposals p  ON p.prepared_by = u.id AND p.deleted_at IS NULL
        LEFT JOIN followups f  ON f.assigned_to  = u.id
        WHERE u.is_active = 1
          AND u.role NOT IN ('SUPERADMIN','ADMIN')
        GROUP BY u.id, u.name, u.role
        ORDER BY leadsHandled DESC
        """, nativeQuery = true)
    List<Object[]> teamPerformanceStats();

    // ══════════════════════════════════════════════════════════════════════════
    // RECENT ORDERS  (with customer name via JOIN)
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT ob.id, ob.order_book_no, c.name AS customerName,
               ob.group_name, ob.sub_group_name,
               ob.total_amount, ob.status, ob.order_date
        FROM order_book ob
        LEFT JOIN customers c ON c.id = ob.customer_id
        WHERE ob.deleted_at IS NULL
        ORDER BY ob.created_at DESC
        LIMIT 8
        """, nativeQuery = true)
    List<Object[]> recentOrders();

    // ── Pending follow-ups (global, for admin) ────────────────────────────────

    @Query(value = """
        SELECT f.id, l.name AS leadName, f.followup_type,
               f.scheduled_at, f.priority, f.status, u.name AS assignedToName
        FROM followups f
        LEFT JOIN leads l   ON l.id = f.lead_id
        LEFT JOIN users u   ON u.id = f.assigned_to
        WHERE f.status = 'Pending'
        ORDER BY f.scheduled_at ASC
        LIMIT 8
        """, nativeQuery = true)
    List<Object[]> pendingFollowupsGlobal();

    // ── Per-user follow-up list ────────────────────────────────────────────────

    @Query(value = """
        SELECT f.id, l.name AS leadName, f.followup_type,
               f.scheduled_at, f.priority, f.status, u.name AS assignedToName
        FROM followups f
        LEFT JOIN leads l ON l.id = f.lead_id
        LEFT JOIN users u ON u.id = f.assigned_to
        WHERE f.assigned_to = :userId AND f.status = 'Pending'
        ORDER BY f.scheduled_at ASC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> pendingFollowupsForUser(@Param("userId") Long userId);

    // ══════════════════════════════════════════════════════════════════════════
    // SALES MANAGER — leads table (assigned_to = managerId)
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT id, lead_code, name, phone, status,
               telecaller_status, group_name, sub_group_name, source, created_at
        FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
        ORDER BY created_at DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> leadsForUser(@Param("userId") Long userId);

    // ── KPIs for Sales Manager ─────────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
        """, nativeQuery = true)
    long countLeadsForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND LOWER(status) = 'closed won'
          AND (
            closed_by_user_id = :userId
            OR (closed_by_user_id IS NULL AND assigned_to = :userId)
          )
        """, nativeQuery = true)
    long countClosedWonForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND LOWER(status) NOT IN ('closed won','closed lost')
        """, nativeQuery = true)
    long countActiveLeadsForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND LOWER(status) = 'contacted'
        """, nativeQuery = true)
    long countContactedForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND LOWER(status) = 'in discussion'
        """, nativeQuery = true)
    long countInDiscussionForUser(@Param("userId") Long userId);

    // ── Revenue for manager (leads they closed) ───────────────────────────────
    // FIX: Use closed_by_user_id, not order_book.created_by

    @Query(value = """
        SELECT COALESCE(SUM(ob.total_amount), 0)
        FROM order_book ob
        JOIN leads l ON l.id = ob.lead_id AND l.deleted_at IS NULL
        WHERE ob.deleted_at IS NULL
          AND LOWER(l.status) = 'closed won'
          AND (
            l.closed_by_user_id = :userId
            OR (l.closed_by_user_id IS NULL AND l.assigned_to = :userId)
          )
        """, nativeQuery = true)
    java.math.BigDecimal sumRevenueForUser(@Param("userId") Long userId);

    // ── Proposals for Sales Manager (via leads they own) ─────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM proposals p
        WHERE p.deleted_at IS NULL
          AND p.lead_id IN (
              SELECT id FROM leads WHERE deleted_at IS NULL AND assigned_to = :userId
          )
        """, nativeQuery = true)
    long countProposalsForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM proposals p
        WHERE p.deleted_at IS NULL AND LOWER(p.status) = 'accepted'
          AND p.lead_id IN (
              SELECT id FROM leads WHERE deleted_at IS NULL AND assigned_to = :userId
          )
        """, nativeQuery = true)
    long countAcceptedProposalsForUser(@Param("userId") Long userId);

    @Query(value = """
        SELECT p.id, p.proposal_no, l.name AS leadName,
               p.total_value, p.status, p.created_at
        FROM proposals p
        LEFT JOIN leads l ON l.id = p.lead_id
        WHERE p.deleted_at IS NULL
          AND p.lead_id IN (
              SELECT id FROM leads WHERE deleted_at IS NULL AND assigned_to = :userId
          )
        ORDER BY p.created_at DESC
        LIMIT 8
        """, nativeQuery = true)
    List<Object[]> proposalsForUser(@Param("userId") Long userId);

    // ── Team members under this manager (created_by = managerId) ──────────────
    //
    // REVENUE FIX: Revenue credited to the employee who closed the lead
    // (closed_by_user_id), not who created the order_book entry.

    @Query(value = """
        SELECT
            u.id, u.name, u.role,
            COUNT(DISTINCT l.id)                                            AS leadsHandled,
            COUNT(DISTINCT CASE
                WHEN LOWER(lw.status)='closed won' THEN lw.id END)         AS leadsWon,
            COUNT(DISTINCT CASE WHEN UPPER(l.telecaller_status)='INTERESTED'
                                THEN l.id END)                              AS interested,
            COUNT(DISTINCT p.id)                                            AS proposalsSent,
            COALESCE((
                SELECT SUM(ob2.total_amount)
                FROM order_book ob2
                JOIN leads lwon2 ON lwon2.id = ob2.lead_id AND lwon2.deleted_at IS NULL
                WHERE ob2.deleted_at IS NULL
                  AND LOWER(lwon2.status) = 'closed won'
                  AND (
                      lwon2.closed_by_user_id = u.id
                      OR (lwon2.closed_by_user_id IS NULL AND lwon2.assigned_to = u.id)
                  )
            ), 0)                                                           AS revenue,
            COUNT(DISTINCT CASE WHEN f.status='Completed' THEN f.id END)   AS followupsDone,
            COUNT(DISTINCT CASE WHEN f.status='Pending'   THEN f.id END)   AS followupsPending
        FROM users u
        LEFT JOIN leads     l  ON l.assigned_to = u.id AND l.deleted_at IS NULL
        LEFT JOIN leads     lw ON lw.deleted_at IS NULL
                               AND LOWER(lw.status) = 'closed won'
                               AND (
                                   lw.closed_by_user_id = u.id
                                   OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id)
                               )
        LEFT JOIN proposals p  ON p.prepared_by = u.id AND p.deleted_at IS NULL
        LEFT JOIN followups f  ON f.assigned_to  = u.id
        WHERE u.is_active = 1 AND u.created_by = :managerId
        GROUP BY u.id, u.name, u.role
        ORDER BY leadsHandled DESC
        """, nativeQuery = true)
    List<Object[]> teamPerformanceForManager(@Param("managerId") Long managerId);

    // ── Team leads for manager's monthly chart ────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND assigned_to IN (
              SELECT id FROM users WHERE is_active = 1 AND created_by = :managerId
          )
        """, nativeQuery = true)
    long countTotalLeadsForManagerTeam(@Param("managerId") Long managerId);

    // ══════════════════════════════════════════════════════════════════════════
    // BD EXECUTIVE — leads where bd_assigned_to = userId
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT id, lead_code, name, phone, status,
               telecaller_status, group_name, sub_group_name, source, created_at
        FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
        ORDER BY created_at DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> leadsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads WHERE deleted_at IS NULL AND bd_assigned_to = :userId
        """, nativeQuery = true)
    long countLeadsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND LOWER(status) = 'closed won'
          AND (
            closed_by_user_id = :userId
            OR (closed_by_user_id IS NULL AND bd_assigned_to = :userId)
          )
        """, nativeQuery = true)
    long countClosedWonForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND LOWER(status) NOT IN ('closed won','closed lost')
        """, nativeQuery = true)
    long countActiveLeadsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND LOWER(status) = 'in discussion'
        """, nativeQuery = true)
    long countInDiscussionForBD(@Param("userId") Long userId);

    // ── Proposals for BD (prepared_by = userId) ────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM proposals WHERE deleted_at IS NULL AND prepared_by = :userId
        """, nativeQuery = true)
    long countProposalsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM proposals
        WHERE deleted_at IS NULL AND prepared_by = :userId AND LOWER(status) = 'accepted'
        """, nativeQuery = true)
    long countAcceptedProposalsForBD(@Param("userId") Long userId);

    // FIX: Revenue for BD executive — from leads they closed (closed_by_user_id)
    @Query(value = """
        SELECT COALESCE(SUM(ob.total_amount), 0)
        FROM order_book ob
        JOIN leads l ON l.id = ob.lead_id AND l.deleted_at IS NULL
        WHERE ob.deleted_at IS NULL
          AND LOWER(l.status) = 'closed won'
          AND (
            l.closed_by_user_id = :userId
            OR (l.closed_by_user_id IS NULL AND l.bd_assigned_to = :userId)
          )
        """, nativeQuery = true)
    java.math.BigDecimal sumRevenueForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT p.id, p.proposal_no, l.name AS leadName,
               p.total_value, p.status, p.created_at
        FROM proposals p
        LEFT JOIN leads l ON l.id = p.lead_id
        WHERE p.deleted_at IS NULL AND p.prepared_by = :userId
        ORDER BY p.created_at DESC
        LIMIT 8
        """, nativeQuery = true)
    List<Object[]> proposalsForBD(@Param("userId") Long userId);

    // ══════════════════════════════════════════════════════════════════════════
    // TELECALLER — leads assigned to telecaller
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT l.id, l.lead_code, l.name, l.phone, l.source,
               l.group_name, l.sub_group_name,
               l.telecaller_status, l.tc_discussion_note,
               CASE WHEN l.bd_assigned_to IS NOT NULL THEN 1 ELSE 0 END AS handedOff,
               u.name AS bdName,
               l.created_at
        FROM leads l
        LEFT JOIN users u ON u.id = l.bd_assigned_to
        WHERE l.deleted_at IS NULL AND l.assigned_to = :userId
        ORDER BY
            CASE WHEN l.telecaller_status IS NULL THEN 0
                 WHEN UPPER(l.telecaller_status) = 'PENDING' THEN 0
                 ELSE 1 END ASC,
            l.created_at DESC
        LIMIT 20
        """, nativeQuery = true)
    List<Object[]> leadsForTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads WHERE deleted_at IS NULL AND assigned_to = :userId
        """, nativeQuery = true)
    long countTotalForTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND telecaller_status IS NOT NULL
          AND UPPER(telecaller_status) != 'PENDING'
        """, nativeQuery = true)
    long countCalledByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND UPPER(telecaller_status) = 'INTERESTED'
        """, nativeQuery = true)
    long countInterestedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND UPPER(telecaller_status) = 'NOT_INTERESTED'
        """, nativeQuery = true)
    long countNotInterestedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND UPPER(telecaller_status) = 'NOT_RESPONDED'
        """, nativeQuery = true)
    long countNotRespondedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND (telecaller_status IS NULL OR UPPER(telecaller_status) = 'PENDING')
        """, nativeQuery = true)
    long countPendingByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND bd_assigned_to IS NOT NULL
        """, nativeQuery = true)
    long countHandedOffByTelecaller(@Param("userId") Long userId);
}