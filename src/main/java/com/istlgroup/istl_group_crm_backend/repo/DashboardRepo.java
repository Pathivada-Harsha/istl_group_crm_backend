package com.istlgroup.istl_group_crm_backend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;

import java.time.LocalDateTime;
import java.util.Collection;
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
        WHERE deleted_at IS NULL AND status = 'closed won'
        """, nativeQuery = true)
    long countClosedWon();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND status NOT IN ('closed won','closed lost')
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
        WHERE deleted_at IS NULL AND status = 'contacted'
        """, nativeQuery = true)
    long countContacted();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND status = 'in discussion'
        """, nativeQuery = true)
    long countInDiscussion();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND status = 'proposal sent'
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

    // Monthly leads for a team: countLeadsGroupedByMonthForTeam, in the TEAM
    // SCOPE section below. The created_by-based version this replaced is gone.

    // ══════════════════════════════════════════════════════════════════════════
    // TEAM PERFORMANCE
    //
    // Eight independent scalar subqueries, NOT four LEFT JOINs.
    //
    // The join form multiplied: one row per (lead × won-lead × proposal × follow-up)
    // for every user, collapsed afterwards with COUNT(DISTINCT ...). The counts came
    // out right, but the row count the server had to materialise was the PRODUCT of
    // the four, so it grew geometrically with activity. A user with 156 leads,
    // 23 proposals and 40 follow-ups alone expands to ~143,000 rows; on the dev
    // database — 66 leads in total — this one query already measured 81 ms while
    // every other dashboard query measured 0.2-0.6 ms.
    //
    // Each subquery below is an indexed lookup keyed on u.id, so the cost is linear
    // in users and there is no cross product at all. Same columns, same order, same
    // numbers — buildTeamStats() is untouched.
    //
    // REVENUE: credited to whoever CLOSED the lead (leads.closed_by_user_id), falling
    // back to assigned_to, so accounts staff entering orders are never credited.
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            u.id                                                     AS userId,
            u.name                                                   AS userName,
            u.role                                                   AS userRole,
            (SELECT COUNT(*) FROM leads lh
                WHERE lh.deleted_at IS NULL
                  AND (lh.assigned_to      = u.id
                    OR lh.bd_assigned_to   = u.id
                    OR lh.closed_by_user_id = u.id))                 AS leadsHandled,
            (SELECT COUNT(*) FROM leads lw
                WHERE lw.deleted_at IS NULL
                  AND lw.status = 'closed won'
                  AND (lw.closed_by_user_id = u.id
                    OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id))) AS leadsWon,
            (SELECT COUNT(*) FROM leads li
                WHERE li.deleted_at IS NULL
                  AND li.telecaller_status = 'INTERESTED'
                  AND li.assigned_to = u.id)                         AS interested,
            (SELECT COUNT(*) FROM proposals pr
                WHERE pr.deleted_at IS NULL
                  AND pr.prepared_by = u.id)                         AS proposalsSent,
            COALESCE((
                SELECT SUM(ob2.total_amount)
                FROM order_book ob2
                JOIN leads lwon2 ON lwon2.id = ob2.lead_id AND lwon2.deleted_at IS NULL
                WHERE ob2.deleted_at IS NULL
                  AND lwon2.status = 'closed won'
                  AND (
                      lwon2.closed_by_user_id = u.id
                      OR (lwon2.closed_by_user_id IS NULL AND lwon2.assigned_to = u.id)
                  )
            ), 0)                                                    AS revenue,
            (SELECT COUNT(*) FROM followups fd
                WHERE fd.assigned_to = u.id AND fd.status = 'Completed') AS followupsDone,
            (SELECT COUNT(*) FROM followups fp
                WHERE fp.assigned_to = u.id AND fp.status = 'Pending')   AS followupsPending
        FROM users u
        WHERE u.is_active = 1
          AND u.role NOT IN ('SUPERADMIN','ADMIN')
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
        LIMIT 50
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
    // SALES MANAGER
    //
    // The per-manager queries that lived here are gone. They answered two
    // questions that neither matched what the dashboard shows:
    //
    //   *ForUser        — leads with assigned_to = the manager personally, which
    //                     is close to nothing for a manager who assigns work out.
    //   *ForManagerTeam — leads belonging to users with created_by = managerId.
    //                     created_by records who typed the account into the admin
    //                     screen (user #1 for nearly every row), not who it
    //                     reports to, so the team was empty and the KPIs read 0.
    //
    // Both are replaced by the *ForTeam queries in the TEAM SCOPE section below,
    // which take the reporting subtree resolved from users.manager_id.
    // ══════════════════════════════════════════════════════════════════════════

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
          AND status = 'closed won'
          AND (
            closed_by_user_id = :userId
            OR (closed_by_user_id IS NULL AND bd_assigned_to = :userId)
          )
        """, nativeQuery = true)
    long countClosedWonForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND status NOT IN ('closed won','closed lost')
        """, nativeQuery = true)
    long countActiveLeadsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND status = 'in discussion'
        """, nativeQuery = true)
    long countInDiscussionForBD(@Param("userId") Long userId);

    // ── Proposals for BD (prepared_by = userId) ────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM proposals WHERE deleted_at IS NULL AND prepared_by = :userId
        """, nativeQuery = true)
    long countProposalsForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM proposals
        WHERE deleted_at IS NULL AND prepared_by = :userId AND status = 'accepted'
        """, nativeQuery = true)
    long countAcceptedProposalsForBD(@Param("userId") Long userId);

    // FIX: Revenue for BD executive — from leads they closed (closed_by_user_id)
    @Query(value = """
        SELECT COALESCE(SUM(ob.total_amount), 0)
        FROM order_book ob
        JOIN leads l ON l.id = ob.lead_id AND l.deleted_at IS NULL
        WHERE ob.deleted_at IS NULL
          AND l.status = 'closed won'
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
                 WHEN l.telecaller_status = 'PENDING' THEN 0
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
          AND telecaller_status != 'PENDING'
        """, nativeQuery = true)
    long countCalledByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND telecaller_status = 'INTERESTED'
        """, nativeQuery = true)
    long countInterestedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND telecaller_status = 'NOT_INTERESTED'
        """, nativeQuery = true)
    long countNotInterestedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND telecaller_status = 'NOT_RESPONDED'
        """, nativeQuery = true)
    long countNotRespondedByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND (telecaller_status IS NULL OR telecaller_status = 'PENDING')
        """, nativeQuery = true)
    long countPendingByTelecaller(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND assigned_to = :userId
          AND bd_assigned_to IS NOT NULL
        """, nativeQuery = true)
    long countHandedOffByTelecaller(@Param("userId") Long userId);

    // ══════════════════════════════════════════════════════════════════════════
    // ADMIN — MONEY, BUSINESS SNAPSHOT, GROWTH DELTAS
    //
    // Date keys, chosen deliberately and used consistently by both the trend
    // and the deltas below:
    //   - revenue  is keyed on order_book.order_date  (a DATE column, so the
    //              bound parameter is wrapped in DATE())
    //   - pipeline is keyed on proposals.created_at   (proposals carry no other date)
    //
    // Soft delete differs per table and is NOT uniform:
    //   leads / order_book / proposals / customers / vendors → deleted_at IS NULL
    //   projects                                             → is_active = 1
    //                                                          (that table has no deleted_at)
    // ══════════════════════════════════════════════════════════════════════════

    // ── Headline money ────────────────────────────────────────────────────────

    /**
     * Total Revenue = confirmed business.
     *
     * Draft and Cancelled are excluded, which is what separates this from
     * sumOrderBookValue() above — that one intentionally sums EVERY non-deleted
     * row. The two KPI cards therefore show different numbers by design, and the
     * gap between them is the value sitting in draft or cancelled orders.
     *
     * order_book.status is a plain VARCHAR with no server-side enum; the live
     * vocabulary is Draft / Confirmed / In Production / Ready for Dispatch /
     * Dispatched / Completed / Cancelled.
     */
    @Query(value = """
        SELECT COALESCE(SUM(total_amount), 0) FROM order_book
        WHERE deleted_at IS NULL
          AND status NOT IN ('draft', 'cancelled')
        """, nativeQuery = true)
    java.math.BigDecimal sumConfirmedRevenue();

    /**
     * Pipeline Value = open proposals, i.e. money quoted but not yet resolved.
     *
     * proposals.status is one of Draft / Sent / Approved / Rejected / On Hold.
     * "Open" is expressed as NOT (approved|rejected) rather than as an explicit
     * IN-list so that a new intermediate status counts as open by default,
     * which is the safer failure mode for a pipeline figure.
     */
    @Query(value = """
        SELECT COALESCE(SUM(total_value), 0) FROM proposals
        WHERE deleted_at IS NULL
          AND COALESCE(status, 'draft') NOT IN ('approved', 'rejected')
        """, nativeQuery = true)
    java.math.BigDecimal sumOpenPipelineValue();

    // ── Monthly money trend ───────────────────────────────────────────────────
    // Both return rows: [year, month, sum]

    @Query(value = """
        SELECT YEAR(order_date)         AS yr,
               MONTH(order_date)        AS mo,
               COALESCE(SUM(total_amount), 0) AS amt
        FROM order_book
        WHERE deleted_at IS NULL
          AND status NOT IN ('draft', 'cancelled')
          AND order_date >= DATE(:since)
        GROUP BY YEAR(order_date), MONTH(order_date)
        ORDER BY yr, mo
        """, nativeQuery = true)
    List<Object[]> monthlyRevenue(@Param("since") LocalDateTime since);

    @Query(value = """
        SELECT YEAR(created_at)        AS yr,
               MONTH(created_at)       AS mo,
               COALESCE(SUM(total_value), 0) AS amt
        FROM proposals
        WHERE deleted_at IS NULL
          AND COALESCE(status, 'draft') NOT IN ('approved', 'rejected')
          AND created_at >= :since
        GROUP BY YEAR(created_at), MONTH(created_at)
        ORDER BY yr, mo
        """, nativeQuery = true)
    List<Object[]> monthlyPipeline(@Param("since") LocalDateTime since);

    // ── Business snapshot ─────────────────────────────────────────────────────

    @Query(value = "SELECT COUNT(*) FROM projects WHERE is_active = 1", nativeQuery = true)
    long countTotalProjects();

    /**
     * "Active" = live work, i.e. everything that is neither finished nor abandoned.
     * projects.status is an @Enumerated(EnumType.STRING) so the column holds the
     * exact UPPER_SNAKE enum token.
     */
    @Query(value = """
        SELECT COUNT(*) FROM projects
        WHERE is_active = 1
          AND status IN ('NOT_STARTED', 'PLANNING', 'IN_PROGRESS', 'ON_HOLD')
        """, nativeQuery = true)
    long countActiveProjects();

    @Query(value = """
        SELECT COUNT(*) FROM projects
        WHERE is_active = 1 AND status = 'COMPLETED'
        """, nativeQuery = true)
    long countCompletedProjects();

    @Query(value = "SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL", nativeQuery = true)
    long countTotalCustomers();

    /** customers.status is Active / Inactive / Lead / Prospect. The column collation is
     *  utf8mb4_unicode_ci, so the comparison is already case-insensitive without LOWER(). */
    @Query(value = """
        SELECT COUNT(*) FROM customers
        WHERE deleted_at IS NULL AND status = 'active'
        """, nativeQuery = true)
    long countActiveCustomers();

    /**
     * No status filter on purpose: vendors.status has no default, no constants
     * class and no defined value set anywhere in the codebase, so any guess at an
     * "active vendor" predicate would silently under-count.
     */
    @Query(value = "SELECT COUNT(*) FROM vendors WHERE deleted_at IS NULL", nativeQuery = true)
    long countTotalVendors();

    // ── Billing ───────────────────────────────────────────────────────────────
    // What has actually been INVOICED, which is a different question from the
    // order book: an order is booked once and then billed in stages, so this
    // trails order_book and is the figure finance recognises.
    //
    // 'draft' and 'cancelled' are excluded on the same grounds as the order-book
    // sums — an invoice that was never issued is not billed value. The column
    // collation is case-insensitive, so the lowercase literals match 'Draft' too.

    @Query(value = """
        SELECT COALESCE(SUM(total_amount), 0) FROM invoices
        WHERE deleted_at IS NULL
          AND COALESCE(status, '') NOT IN ('draft', 'cancelled')
        """, nativeQuery = true)
    java.math.BigDecimal sumInvoicedValue();

    @Query(value = """
        SELECT COUNT(*) FROM invoices
        WHERE deleted_at IS NULL
          AND COALESCE(status, '') NOT IN ('draft', 'cancelled')
        """, nativeQuery = true)
    long countInvoices();

    // ── Period windows, for the month-over-month growth deltas ────────────────
    // All are half-open [from, to) so the two windows can never double-count a row.

    @Query(value = """
        SELECT COALESCE(SUM(total_amount), 0) FROM order_book
        WHERE deleted_at IS NULL
          AND status NOT IN ('draft', 'cancelled')
          AND order_date >= DATE(:from) AND order_date < DATE(:to)
        """, nativeQuery = true)
    java.math.BigDecimal sumConfirmedRevenueBetween(@Param("from") LocalDateTime from,
                                                    @Param("to")   LocalDateTime to);

    @Query(value = """
        SELECT COALESCE(SUM(total_amount), 0) FROM order_book
        WHERE deleted_at IS NULL
          AND order_date >= DATE(:from) AND order_date < DATE(:to)
        """, nativeQuery = true)
    java.math.BigDecimal sumOrderBookValueBetween(@Param("from") LocalDateTime from,
                                                  @Param("to")   LocalDateTime to);

    @Query(value = """
        SELECT COALESCE(SUM(total_value), 0) FROM proposals
        WHERE deleted_at IS NULL
          AND COALESCE(status, 'draft') NOT IN ('approved', 'rejected')
          AND created_at >= :from AND created_at < :to
        """, nativeQuery = true)
    java.math.BigDecimal sumOpenPipelineCreatedBetween(@Param("from") LocalDateTime from,
                                                       @Param("to")   LocalDateTime to);

    /** New leads that are still open. See DashboardService for why this approximates the KPI. */
    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND status NOT IN ('closed won', 'closed lost')
          AND created_at >= :from AND created_at < :to
        """, nativeQuery = true)
    long countActiveLeadsCreatedBetween(@Param("from") LocalDateTime from,
                                        @Param("to")   LocalDateTime to);

    @Query(value = """
        SELECT COUNT(*) FROM order_book
        WHERE deleted_at IS NULL
          AND created_at >= :from AND created_at < :to
        """, nativeQuery = true)
    long countOrdersCreatedBetween(@Param("from") LocalDateTime from,
                                   @Param("to")   LocalDateTime to);

    // ── Lead funnel, CUMULATIVE ───────────────────────────────────────────────
    //
    // countContacted() / countInDiscussion() / countProposalSent() above each
    // count leads *currently sitting in* that one status. That is not a funnel:
    // a lead that reached Closed Won left "Contacted" behind, so the middle of
    // the chart collapses while the end stays high. Worse, 'Contacted' and
    // 'In Discussion' are legacy statuses the lead UI no longer offers, so they
    // trend to zero on new data.
    //
    // These three count "reached this stage OR BEYOND". Each set is a strict
    // subset of the one before it, so the funnel can only ever descend.
    // The originals are untouched — the Manager and BD dashboards still use them.

    /** Anything that is no longer a brand-new, untouched lead. */
    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND COALESCE(status, 'new') <> 'new'
        """, nativeQuery = true)
    long countReachedContacted();

    /** Positively engaged: excludes New, Not Interested, Not Responded, Closed Lost. */
    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND status IN (
              'interested', 'in discussion', 'prospect', 'keep in view',
              'proposal sent', 'closed won'
          )
        """, nativeQuery = true)
    long countReachedDiscussion();

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL
          AND status IN ('proposal sent', 'closed won')
        """, nativeQuery = true)
    long countReachedProposal();

    // ══════════════════════════════════════════════════════════════════════════
    // TEAM SCOPE  —  a user PLUS everyone who reports to them, transitively
    //
    // Every query below takes the id set that UserScopeService resolved by
    // walking users.manager_id, and is the replacement for the older
    // "users.created_by = :managerId" team definition. created_by records who
    // typed the account into the admin screen — in practice user #1 for almost
    // every row — so a manager's team came back empty and every team KPI read 0.
    // manager_id is the column the rest of the app already treats as the
    // reporting line (TaskRepository, ProjectExpenseRepository, UserScopeService).
    //
    // One OWNERSHIP RULE is used by all of them:
    //
    //     assigned_to IN ids OR bd_assigned_to IN ids OR closed_by_user_id IN ids
    //
    // applied identically to the total and to every stage below it. That is what
    // keeps the funnel honest: a lead someone closed but no longer holds lands in
    // BOTH the total and the won count, instead of only the won count — which is
    // how "4 leads, 7 closed won, 175% conversion" was being produced.
    //
    // The id set is never empty (a user with no reports still resolves to
    // themselves), so IN (:userIds) cannot degenerate into invalid SQL.
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countLeadsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND COALESCE(l.status, 'new') NOT IN ('closed won','closed lost')
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countActiveLeadsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.status = 'closed won'
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countClosedWonForTeam(@Param("userIds") Collection<Long> userIds);

    /** Leads sitting in 'in discussion' right now — feeds the KPI card, not the funnel. */
    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.status = 'in discussion'
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countInDiscussionForTeam(@Param("userIds") Collection<Long> userIds);

    /** Leads sitting in 'contacted' right now — feeds the KPI card, not the funnel. */
    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.status = 'contacted'
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countContactedForTeam(@Param("userIds") Collection<Long> userIds);

    // ── Team funnel, CUMULATIVE ("reached this stage OR BEYOND") ──────────────
    // Same three stage definitions the admin funnel uses, narrowed to the team's
    // leads. Each set is a strict subset of the one above it, so the shape can
    // only ever descend. Counting leads *currently sitting in* a status cannot
    // do that: 'Contacted' and 'In Discussion' are legacy statuses the lead UI
    // no longer offers, so they read 0 on new data while 'Proposal Sent' below
    // them reads high — which is what turned the funnel into an hourglass.

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND COALESCE(l.status, 'new') <> 'new'
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countReachedContactedForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.status IN (
              'interested', 'in discussion', 'prospect', 'keep in view',
              'tender floated', 'proposal sent', 'closed won'
          )
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countReachedDiscussionForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.status IN ('proposal sent', 'closed won')
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    long countReachedProposalForTeam(@Param("userIds") Collection<Long> userIds);

    // ── Team proposals / revenue ─────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM proposals p
        WHERE p.deleted_at IS NULL
          AND (p.prepared_by IN (:userIds)
            OR p.lead_id IN (
                 SELECT l.id FROM leads l
                 WHERE l.deleted_at IS NULL
                   AND (l.assigned_to IN (:userIds) OR l.bd_assigned_to IN (:userIds))
               ))
        """, nativeQuery = true)
    long countProposalsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM proposals p
        WHERE p.deleted_at IS NULL AND p.status = 'accepted'
          AND (p.prepared_by IN (:userIds)
            OR p.lead_id IN (
                 SELECT l.id FROM leads l
                 WHERE l.deleted_at IS NULL
                   AND (l.assigned_to IN (:userIds) OR l.bd_assigned_to IN (:userIds))
               ))
        """, nativeQuery = true)
    long countAcceptedProposalsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COALESCE(SUM(ob.total_amount), 0)
        FROM order_book ob
        JOIN leads l ON l.id = ob.lead_id AND l.deleted_at IS NULL
        WHERE ob.deleted_at IS NULL
          AND l.status = 'closed won'
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        """, nativeQuery = true)
    java.math.BigDecimal sumRevenueForTeam(@Param("userIds") Collection<Long> userIds);

    // ── Team follow-ups ──────────────────────────────────────────────────────

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending' AND assigned_to IN (:userIds)
        """, nativeQuery = true)
    long countPendingFollowupsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending' AND assigned_to IN (:userIds)
          AND scheduled_at < :now
        """, nativeQuery = true)
    long countOverdueFollowupsForTeam(@Param("userIds") Collection<Long> userIds,
                                      @Param("now") LocalDateTime now);

    @Query(value = """
        SELECT COUNT(*) FROM followups
        WHERE status = 'Pending' AND assigned_to IN (:userIds)
          AND DATE(scheduled_at) = DATE(:today)
        """, nativeQuery = true)
    long countTodayFollowupsForTeam(@Param("userIds") Collection<Long> userIds,
                                    @Param("today") LocalDateTime today);

    @Query(value = """
        SELECT f.id, l.name AS leadName, f.followup_type,
               f.scheduled_at, f.priority, f.status, u.name AS assignedToName
        FROM followups f
        LEFT JOIN leads l ON l.id = f.lead_id
        LEFT JOIN users u ON u.id = f.assigned_to
        WHERE f.status = 'Pending' AND f.assigned_to IN (:userIds)
        ORDER BY f.scheduled_at ASC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> pendingFollowupsForTeam(@Param("userIds") Collection<Long> userIds);

    // ── Team tables ──────────────────────────────────────────────────────────

    @Query(value = """
        SELECT l.id, l.lead_code, l.name, l.phone, l.status,
               l.telecaller_status, l.group_name, l.sub_group_name, l.source, l.created_at
        FROM leads l
        WHERE l.deleted_at IS NULL
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        ORDER BY l.created_at DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> leadsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT p.id, p.proposal_no, l.name AS leadName,
               p.total_value, p.status, p.created_at
        FROM proposals p
        LEFT JOIN leads l ON l.id = p.lead_id
        WHERE p.deleted_at IS NULL
          AND (p.prepared_by IN (:userIds)
            OR p.lead_id IN (
                 SELECT l2.id FROM leads l2
                 WHERE l2.deleted_at IS NULL
                   AND (l2.assigned_to IN (:userIds) OR l2.bd_assigned_to IN (:userIds))
               ))
        ORDER BY p.created_at DESC
        LIMIT 8
        """, nativeQuery = true)
    List<Object[]> proposalsForTeam(@Param("userIds") Collection<Long> userIds);

    @Query(value = """
        SELECT YEAR(l.created_at)  AS yr,
               MONTH(l.created_at) AS mo,
               COUNT(*)            AS cnt
        FROM leads l
        WHERE l.deleted_at IS NULL
          AND l.created_at >= :since
          AND (l.assigned_to IN (:userIds)
            OR l.bd_assigned_to IN (:userIds)
            OR l.closed_by_user_id IN (:userIds))
        GROUP BY YEAR(l.created_at), MONTH(l.created_at)
        ORDER BY yr, mo
        """, nativeQuery = true)
    List<Object[]> countLeadsGroupedByMonthForTeam(@Param("userIds") Collection<Long> userIds,
                                                   @Param("since")   LocalDateTime since);

    /**
     * Per-member performance for an explicit id set — the reporting subtree
     * minus the viewer themselves, resolved by UserScopeService.
     *
     * Same column contract and same revenue attribution as the removed
     * teamPerformanceForManager(Long); only the membership test changed, from
     * u.created_by = managerId to an explicit id list.
     */
    @Query(value = """
        SELECT
            u.id                                                     AS userId,
            u.name                                                   AS userName,
            u.role                                                   AS userRole,
            (SELECT COUNT(*) FROM leads lh
                WHERE lh.deleted_at IS NULL
                  AND (lh.assigned_to      = u.id
                    OR lh.bd_assigned_to   = u.id
                    OR lh.closed_by_user_id = u.id))                 AS leadsHandled,
            (SELECT COUNT(*) FROM leads lw
                WHERE lw.deleted_at IS NULL
                  AND lw.status = 'closed won'
                  AND (lw.closed_by_user_id = u.id
                    OR (lw.closed_by_user_id IS NULL AND lw.assigned_to = u.id))) AS leadsWon,
            (SELECT COUNT(*) FROM leads li
                WHERE li.deleted_at IS NULL
                  AND li.telecaller_status = 'INTERESTED'
                  AND li.assigned_to = u.id)                         AS interested,
            (SELECT COUNT(*) FROM proposals pr
                WHERE pr.deleted_at IS NULL
                  AND pr.prepared_by = u.id)                         AS proposalsSent,
            COALESCE((
                SELECT SUM(ob2.total_amount)
                FROM order_book ob2
                JOIN leads lwon2 ON lwon2.id = ob2.lead_id AND lwon2.deleted_at IS NULL
                WHERE ob2.deleted_at IS NULL
                  AND lwon2.status = 'closed won'
                  AND (
                      lwon2.closed_by_user_id = u.id
                      OR (lwon2.closed_by_user_id IS NULL AND lwon2.assigned_to = u.id)
                  )
            ), 0)                                                    AS revenue,
            (SELECT COUNT(*) FROM followups fd
                WHERE fd.assigned_to = u.id AND fd.status = 'Completed') AS followupsDone,
            (SELECT COUNT(*) FROM followups fp
                WHERE fp.assigned_to = u.id AND fp.status = 'Pending')   AS followupsPending
        FROM users u
        WHERE u.is_active = 1
          AND u.id IN (:userIds)
        ORDER BY leadsHandled DESC
        """, nativeQuery = true)
    List<Object[]> teamPerformanceForUsers(@Param("userIds") Collection<Long> userIds);

    // ══════════════════════════════════════════════════════════════════════════
    // BD FUNNEL, CUMULATIVE — the same three stage definitions, over the leads
    // this BD owns (bd_assigned_to). Replaces the single current-status
    // countInDiscussionForBD reading in the funnel, which sat at 0 above a
    // Proposals stage in the tens and inverted the shape.
    // ══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND COALESCE(status, 'new') <> 'new'
        """, nativeQuery = true)
    long countReachedContactedForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND status IN (
              'interested', 'in discussion', 'prospect', 'keep in view',
              'tender floated', 'proposal sent', 'closed won'
          )
        """, nativeQuery = true)
    long countReachedDiscussionForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND status IN ('proposal sent', 'closed won')
        """, nativeQuery = true)
    long countReachedProposalForBD(@Param("userId") Long userId);

    @Query(value = """
        SELECT COUNT(*) FROM leads
        WHERE deleted_at IS NULL AND bd_assigned_to = :userId
          AND status = 'closed won'
        """, nativeQuery = true)
    long countReachedWonForBD(@Param("userId") Long userId);
}
