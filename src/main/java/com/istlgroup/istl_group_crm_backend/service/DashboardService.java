package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.repo.DashboardRepo;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DashboardDTO.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * DashboardService
 *
 * REVENUE FIX (2026-04-23):
 *   All revenue queries now use sumRevenueForTeam / sumRevenueForBD which
 *   attribute revenue to the employee who CLOSED the lead
 *   (leads.closed_by_user_id), not to the person who created the order_book
 *   entry. This ensures accounts staff are never credited for closed deals.
 *
 * All KPIs are COUNT/SUM aggregations — never full list loads.
 * Table rows are fetched with LIMIT 8-20 so they are always fast.
 *
 * Speed strategy:
 *  - Admin: all COUNT queries run in parallel via CompletableFuture.
 *    Total time ≈ slowest single query (~50-100ms on indexed tables).
 *  - Role dashboards: same parallel approach.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardService {

    /** Fan-out pool for the query bursts below — NOT ForkJoinPool.commonPool.
     *  See DashboardExecutorConfig for why that default was the wrong one. */
    // Field name == bean name (DashboardExecutorConfig.BEAN) on purpose: Spring's
    // WebSocket config publishes three more Executor beans, so by-type injection is
    // ambiguous and it falls back to matching the parameter name. lombok.config
    // additionally copies @Qualifier onto the generated constructor; the name match
    // is the belt to that braces.
    @org.springframework.beans.factory.annotation.Qualifier(
        com.istlgroup.istl_group_crm_backend.config.DashboardExecutorConfig.BEAN)
    private final java.util.concurrent.Executor dashboardExecutor;

    private final DashboardRepo        repo;
    private final RoleHierarchyService roleHierarchyService;
    private final TeamRepository       teamRepository;
    /** Resolves "me plus everyone who reports to me" off users.manager_id. */
    private final UserScopeService     userScopeService;

    // ══════════════════════════════════════════════════════════════════════════
    // ADMIN / SUPER ADMIN
    // ══════════════════════════════════════════════════════════════════════════

    public AdminDashboard buildAdminDashboard() {
        LocalDateTime now          = LocalDateTime.now();
        LocalDateTime startOfMonth = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime sixMonthsAgo = now.minusMonths(6);
        // Growth deltas compare this calendar month against the whole of last one.
        LocalDateTime startOfLastMonth = startOfMonth.minusMonths(1);
        // 8 points on the Revenue Overview chart: this month plus the previous 7.
        LocalDateTime eightMonthsAgo   = startOfMonth.minusMonths(7);

        CompletableFuture<Long> cfTotal        = cf(() -> repo.countTotalLeads());
        CompletableFuture<Long> cfThisMonth    = cf(() -> repo.countLeadsThisMonth(startOfMonth));
        CompletableFuture<Long> cfWon          = cf(() -> repo.countClosedWon());
        CompletableFuture<Long> cfActive       = cf(() -> repo.countActiveLeads());
        CompletableFuture<Long> cfProposals    = cf(() -> repo.countTotalProposals());
        CompletableFuture<Long> cfOrders       = cf(() -> repo.countTotalOrders());
        CompletableFuture<BigDecimal> cfOBVal  = cf(() -> repo.sumOrderBookValue());
        CompletableFuture<Long> cfPendingFU    = cf(() -> repo.countPendingFollowupsGlobal());
        CompletableFuture<Long> cfOverdueFU    = cf(() -> repo.countOverdueFollowupsGlobal(now));
        CompletableFuture<Long> cfTodayFU      = cf(() -> repo.countTodayFollowupsGlobal(now));
        CompletableFuture<Long> cfContacted    = cf(() -> repo.countContacted());
        CompletableFuture<Long> cfDiscussion   = cf(() -> repo.countInDiscussion());
        CompletableFuture<Long> cfPropSent     = cf(() -> repo.countProposalSent());

        CompletableFuture<List<MonthStat>>      cfMonthly = cf(() -> buildMonthlyStats(sixMonthsAgo));
        CompletableFuture<List<TeamMemberStat>> cfTeam    = cf(() -> buildTeamStats(repo.teamPerformanceStats()));
        CompletableFuture<List<RecentOrder>>    cfRecent  = cf(() -> buildRecentOrders(repo.recentOrders()));
        CompletableFuture<List<FollowupItem>>   cfFU      = cf(() -> buildFollowupItems(repo.pendingFollowupsGlobal()));

        // ── Redesigned admin view ──
        // Grouped into five futures rather than one per query on purpose: this
        // method already runs 17 concurrent futures, each holding a JDBC
        // connection, and HikariCP's pool here is the default 10. The three
        // grouped blocks below run their queries sequentially inside a single
        // future and isolate failure per metric internally (see safeMoney /
        // safeCount), so one broken query still degrades to 0 on its own.
        CompletableFuture<BigDecimal>       cfRevenue  = cf(() -> repo.sumConfirmedRevenue());
        // Billed value and its invoice count share one future — two cheap
        // aggregates over the same table, see the connection-pool note above.
        CompletableFuture<Object[]>         cfBilled   = cf(() -> new Object[] {
            safeMoney(() -> repo.sumInvoicedValue()),
            safeCount(() -> repo.countInvoices()),
        });
        CompletableFuture<BigDecimal>       cfPipeline = cf(() -> repo.sumOpenPipelineValue());
        CompletableFuture<List<MonthMoney>> cfTrend    = cf(() -> buildMonthlyTrend(eightMonthsAgo));
        CompletableFuture<BusinessSnapshot> cfSnapshot = cf(this::buildBusinessSnapshot);
        CompletableFuture<KpiDeltas>        cfDeltas   = cf(() -> buildKpiDeltas(startOfLastMonth, startOfMonth, now));
        // Three cheap COUNTs share one future — see the connection-pool note above.
        CompletableFuture<long[]>           cfFunnel   = cf(() -> new long[] {
            safeCount(() -> repo.countReachedContacted()),
            safeCount(() -> repo.countReachedDiscussion()),
            safeCount(() -> repo.countReachedProposal()),
        });

        CompletableFuture.allOf(
            cfTotal, cfThisMonth, cfWon, cfActive, cfProposals, cfOrders,
            cfOBVal, cfPendingFU, cfOverdueFU, cfTodayFU,
            cfContacted, cfDiscussion, cfPropSent,
            cfMonthly, cfTeam, cfRecent, cfFU,
            cfRevenue, cfPipeline, cfTrend, cfSnapshot, cfDeltas, cfFunnel, cfBilled
        ).join();

        Object[] billed = get(cfBilled, new Object[] { BigDecimal.ZERO, 0L });

        long[] funnel = get(cfFunnel, new long[] { 0L, 0L, 0L });

        long total = get(cfTotal, 0L);
        long won   = get(cfWon,   0L);

        return AdminDashboard.builder()
            .totalLeads(total)
            .leadsThisMonth(get(cfThisMonth, 0L))
            .closedWon(won)
            .activeLeads(get(cfActive, 0L))
            .totalProposals(get(cfProposals, 0L))
            .totalOrders(get(cfOrders, 0L))
            .orderBookValue(get(cfOBVal, BigDecimal.ZERO))
            .totalBilledValue((BigDecimal) billed[0])
            .totalInvoices((Long) billed[1])
            .pendingFollowups(get(cfPendingFU, 0L))
            .overdueFollowups(get(cfOverdueFU, 0L))
            .todayFollowups(get(cfTodayFU, 0L))
            .contacted(get(cfContacted, 0L))
            .inDiscussion(get(cfDiscussion, 0L))
            .proposalSent(get(cfPropSent, 0L))
            .monthlyLeads(get(cfMonthly, List.of()))
            .teamPerformance(get(cfTeam, List.of()))
            .recentOrders(get(cfRecent, List.of()))
            .followups(get(cfFU, List.of()))
            .totalRevenue(get(cfRevenue, BigDecimal.ZERO))
            .pipelineValue(get(cfPipeline, BigDecimal.ZERO))
            .monthlyTrend(get(cfTrend, List.of()))
            .businessSnapshot(get(cfSnapshot, emptySnapshot()))
            .deltas(get(cfDeltas, emptyDeltas()))
            .reachedContacted(funnel[0])
            .reachedDiscussion(funnel[1])
            .reachedProposal(funnel[2])
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SALES MANAGER
    //
    // TEAM SCOPE FIX: every figure on this dashboard is now the manager's
    // REPORTING SUBTREE — themselves plus everyone whose users.manager_id chains
    // up to them, transitively — resolved by UserScopeService, the same service
    // that already answers "which users may this user act on?" everywhere else.
    //
    // It used to be a mix of two different scopes, which is why the whole page
    // read zero for a real manager:
    //   - "Team Leads" counted leads assigned to users with created_by =
    //     managerId. created_by is who typed the account into the admin screen,
    //     almost always user #1 — so the team was empty and the count was 0.
    //   - Every other KPI ignored the team completely and counted only leads
    //     assigned_to the manager personally, which for a manager who assigns
    //     their work out is also 0.
    //
    // Funnel stages are cumulative ("reached this stage or beyond") over exactly
    // the same lead set as the total, so the shape can only descend.
    // ══════════════════════════════════════════════════════════════════════════

    public SalesManagerDashboard buildSalesManagerDashboard(Long userId) {
        LocalDateTime now          = LocalDateTime.now();
        LocalDateTime sixMonthsAgo = now.minusMonths(6);

        Set<Long> team     = teamScope(userId);                 // manager + all reports
        Set<Long> reports  = reportsOnly(userId, team);         // subtree minus the manager
        Set<Long> tableIds = reports.isEmpty() ? Set.of(userId) : reports;

        CompletableFuture<Long> cfLeads      = cf(() -> repo.countLeadsForTeam(team));
        CompletableFuture<Long> cfActive     = cf(() -> repo.countActiveLeadsForTeam(team));
        CompletableFuture<Long> cfWon        = cf(() -> repo.countClosedWonForTeam(team));
        CompletableFuture<Long> cfProps      = cf(() -> repo.countProposalsForTeam(team));
        CompletableFuture<Long> cfAccepted   = cf(() -> repo.countAcceptedProposalsForTeam(team));
        CompletableFuture<BigDecimal> cfRev  = cf(() -> safeRevenue(repo.sumRevenueForTeam(team)));
        CompletableFuture<Long> cfPendingFU  = cf(() -> repo.countPendingFollowupsForTeam(team));
        CompletableFuture<Long> cfOverdueFU  = cf(() -> repo.countOverdueFollowupsForTeam(team, now));
        CompletableFuture<Long> cfTodayFU    = cf(() -> repo.countTodayFollowupsForTeam(team, now));
        CompletableFuture<Long> cfContacted  = cf(() -> repo.countContactedForTeam(team));
        CompletableFuture<Long> cfDiscussion = cf(() -> repo.countInDiscussionForTeam(team));

        CompletableFuture<List<LeadRow>>        cfLeadRows = cf(() -> buildLeadRows(repo.leadsForTeam(team)));
        CompletableFuture<List<ProposalRow>>    cfPropRows = cf(() -> buildProposalRows(repo.proposalsForTeam(team)));
        // The table lists the people under the manager, so the manager themselves
        // is dropped — but only when they actually have reports. A "manager" with
        // nobody below them still sees their own row rather than an empty card.
        CompletableFuture<List<TeamMemberStat>> cfTeamRows =
            cf(() -> buildTeamStats(repo.teamPerformanceForUsers(tableIds)));
        CompletableFuture<List<FollowupItem>>   cfFU       = cf(() -> buildFollowupItems(repo.pendingFollowupsForTeam(team)));
        CompletableFuture<List<MonthStat>>      cfMonthly  = cf(() -> fillMonthlyGaps(repo.countLeadsGroupedByMonthForTeam(team, sixMonthsAgo)));
        // Three cheap COUNTs share one future — see the connection-pool note on
        // the admin builder; this method already runs 14 concurrent queries.
        CompletableFuture<long[]> cfFunnel = cf(() -> new long[] {
            safeCount(() -> repo.countReachedContactedForTeam(team)),
            safeCount(() -> repo.countReachedDiscussionForTeam(team)),
            safeCount(() -> repo.countReachedProposalForTeam(team)),
        });

        CompletableFuture.allOf(
            cfLeads, cfActive, cfWon, cfProps, cfAccepted, cfRev,
            cfPendingFU, cfOverdueFU, cfTodayFU, cfContacted, cfDiscussion,
            cfLeadRows, cfPropRows, cfTeamRows, cfFU, cfMonthly, cfFunnel
        ).join();

        long[] funnel = get(cfFunnel, new long[] { 0L, 0L, 0L });
        long   total  = get(cfLeads, 0L);
        long   won    = get(cfWon,   0L);

        return SalesManagerDashboard.builder()
            .myLeads(total)
            .activeLeads(get(cfActive, 0L))
            .closedWon(won)
            .myProposals(get(cfProps, 0L))
            .acceptedProposals(get(cfAccepted, 0L))
            .revenue(get(cfRev, BigDecimal.ZERO))
            .conversionRate(convRate(won, total))
            .pendingFollowups(get(cfPendingFU, 0L))
            .todayFollowups(get(cfTodayFU, 0L))
            .overdueFollowups(get(cfOverdueFU, 0L))
            .contacted(get(cfContacted, 0L))
            .inDiscussion(get(cfDiscussion, 0L))
            .reachedContacted(funnel[0])
            .reachedDiscussion(funnel[1])
            .reachedProposal(funnel[2])
            .teamSize(reports.size())
            .leads(get(cfLeadRows, List.of()))
            .proposals(get(cfPropRows, List.of()))
            .teamMembers(get(cfTeamRows, List.of()))
            .followups(get(cfFU, List.of()))
            .monthlyLeads(get(cfMonthly, List.of()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BD EXECUTIVE
    // REVENUE FIX: uses sumRevenueForBD (closed_by_user_id based)
    // ══════════════════════════════════════════════════════════════════════════

    public BdDashboard buildBdDashboard(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        CompletableFuture<Long> cfLeads      = cf(() -> repo.countLeadsForBD(userId));
        CompletableFuture<Long> cfActive     = cf(() -> repo.countActiveLeadsForBD(userId));
        CompletableFuture<Long> cfWon        = cf(() -> repo.countClosedWonForBD(userId));
        CompletableFuture<Long> cfDiscussion = cf(() -> repo.countInDiscussionForBD(userId));
        CompletableFuture<Long> cfProps      = cf(() -> repo.countProposalsForBD(userId));
        CompletableFuture<Long> cfAccepted   = cf(() -> repo.countAcceptedProposalsForBD(userId));
        // REVENUE FIX: now uses closed_by_user_id based query
        CompletableFuture<BigDecimal> cfRev  = cf(() -> safeRevenue(repo.sumRevenueForBD(userId)));
        CompletableFuture<Long> cfPendingFU  = cf(() -> repo.countPendingFollowupsForUser(userId));
        CompletableFuture<Long> cfOverdueFU  = cf(() -> repo.countOverdueFollowupsForUser(userId, now));
        CompletableFuture<Long> cfTodayFU    = cf(() -> repo.countTodayFollowupsForUser(userId, now));

        CompletableFuture<List<LeadRow>>      cfLeadRows = cf(() -> buildLeadRows(repo.leadsForBD(userId)));
        CompletableFuture<List<ProposalRow>>  cfPropRows = cf(() -> buildProposalRows(repo.proposalsForBD(userId)));
        CompletableFuture<List<FollowupItem>> cfFU       = cf(() -> buildFollowupItems(repo.pendingFollowupsForUser(userId)));
        // Cumulative funnel stages over the BD's own leads. The KPI cards above
        // keep their current-status counts; the funnel needs "reached this stage
        // or beyond" or it inverts — In Discussion is a status the lead UI no
        // longer sets, so it reads 0 above a Proposals stage in the tens.
        CompletableFuture<long[]> cfFunnel = cf(() -> new long[] {
            safeCount(() -> repo.countReachedContactedForBD(userId)),
            safeCount(() -> repo.countReachedDiscussionForBD(userId)),
            safeCount(() -> repo.countReachedProposalForBD(userId)),
            safeCount(() -> repo.countReachedWonForBD(userId)),
        });

        CompletableFuture.allOf(
            cfLeads, cfActive, cfWon, cfDiscussion, cfProps, cfAccepted, cfRev,
            cfPendingFU, cfOverdueFU, cfTodayFU,
            cfLeadRows, cfPropRows, cfFU, cfFunnel
        ).join();

        long[] funnel = get(cfFunnel, new long[] { 0L, 0L, 0L, 0L });

        long total = get(cfLeads, 0L);
        long won   = get(cfWon,   0L);

        return BdDashboard.builder()
            .totalLeads(total)
            .activeLeads(get(cfActive, 0L))
            .closedWon(won)
            .inDiscussion(get(cfDiscussion, 0L))
            .proposalsSent(get(cfProps, 0L))
            .acceptedProposals(get(cfAccepted, 0L))
            .revenue(get(cfRev, BigDecimal.ZERO))
            .conversionRate(convRate(won, total))
            .pendingFollowups(get(cfPendingFU, 0L))
            .todayFollowups(get(cfTodayFU, 0L))
            .overdueFollowups(get(cfOverdueFU, 0L))
            .reachedContacted(funnel[0])
            .reachedDiscussion(funnel[1])
            .reachedProposal(funnel[2])
            .reachedWon(funnel[3])
            .leads(get(cfLeadRows, List.of()))
            .proposals(get(cfPropRows, List.of()))
            .followups(get(cfFU, List.of()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TELECALLER
    // ══════════════════════════════════════════════════════════════════════════

    public TelecallerDashboard buildTelecallerDashboard(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        CompletableFuture<Long> cfTotal       = cf(() -> repo.countTotalForTelecaller(userId));
        CompletableFuture<Long> cfCalled      = cf(() -> repo.countCalledByTelecaller(userId));
        CompletableFuture<Long> cfInterested  = cf(() -> repo.countInterestedByTelecaller(userId));
        CompletableFuture<Long> cfNotInt      = cf(() -> repo.countNotInterestedByTelecaller(userId));
        CompletableFuture<Long> cfNotResp     = cf(() -> repo.countNotRespondedByTelecaller(userId));
        CompletableFuture<Long> cfPending     = cf(() -> repo.countPendingByTelecaller(userId));
        CompletableFuture<Long> cfHandedOff   = cf(() -> repo.countHandedOffByTelecaller(userId));
        CompletableFuture<Long> cfTodayFU     = cf(() -> repo.countTodayFollowupsForUser(userId, now));
        CompletableFuture<Long> cfOverdueFU   = cf(() -> repo.countOverdueFollowupsForUser(userId, now));

        CompletableFuture<List<TelecallerLeadRow>> cfLeads = cf(() -> buildTCLeadRows(repo.leadsForTelecaller(userId)));
        CompletableFuture<List<FollowupItem>>      cfFU    = cf(() -> buildFollowupItems(repo.pendingFollowupsForUser(userId)));

        CompletableFuture.allOf(
            cfTotal, cfCalled, cfInterested, cfNotInt, cfNotResp,
            cfPending, cfHandedOff, cfTodayFU, cfOverdueFU,
            cfLeads, cfFU
        ).join();

        return TelecallerDashboard.builder()
            .total(get(cfTotal, 0L))
            .called(get(cfCalled, 0L))
            .interested(get(cfInterested, 0L))
            .notInterested(get(cfNotInt, 0L))
            .notResponded(get(cfNotResp, 0L))
            .pending(get(cfPending, 0L))
            .handedOff(get(cfHandedOff, 0L))
            .todayFollowups(get(cfTodayFU, 0L))
            .overdueFollowups(get(cfOverdueFU, 0L))
            .leads(get(cfLeads, List.of()))
            .followups(get(cfFU, List.of()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GENERIC — any role not explicitly handled (PROCUREMENT_MANAGER, etc.)
    //
    // TEAM SCOPE FIX: this view is now scoped to the caller's reporting subtree
    // exactly like the manager view, and the team table appears for ANYONE who
    // has reports — not only for roles that happen to sit at hierarchy level 3.
    // A PROCUREMENT_MANAGER or ACCOUNTS_MANAGER with three people under them was
    // previously shown an empty page because the level check excluded them and
    // the team membership test (users.created_by) matched nobody anyway.
    //
    // FUNNEL FIX: myLeads and closedWon are now counted over ONE lead set. They
    // used to be counted over two: myLeads was "assigned_to = me" while
    // closedWon was "closed_by_user_id = me", so a user who had closed seven
    // leads that were no longer assigned to them showed 4 leads / 7 won / 175%
    // conversion, and the funnel bulged outwards at the bottom.
    // ══════════════════════════════════════════════════════════════════════════

    public GenericDashboard buildGenericDashboard(Long userId, String userRole) {
        LocalDateTime now = LocalDateTime.now();
        int level = roleHierarchyService.getLevelOrder(userRole);

        Set<Long> team     = teamScope(userId);
        Set<Long> reports  = reportsOnly(userId, team);        // subtree minus the viewer
        Set<Long> tableIds = reports.isEmpty() ? Set.of(userId) : reports;

        CompletableFuture<Long> cfPendingFU  = cf(() -> repo.countPendingFollowupsForTeam(team));
        CompletableFuture<Long> cfOverdueFU  = cf(() -> repo.countOverdueFollowupsForTeam(team, now));
        CompletableFuture<Long> cfTodayFU    = cf(() -> repo.countTodayFollowupsForTeam(team, now));
        CompletableFuture<List<FollowupItem>> cfFU = cf(() -> buildFollowupItems(repo.pendingFollowupsForTeam(team)));

        CompletableFuture<Long> cfLeads  = cf(() -> safeCount(() -> repo.countLeadsForTeam(team)));
        CompletableFuture<Long> cfActive = cf(() -> safeCount(() -> repo.countActiveLeadsForTeam(team)));
        CompletableFuture<Long> cfWon    = cf(() -> safeCount(() -> repo.countClosedWonForTeam(team)));
        CompletableFuture<Long> cfProps  = cf(() -> safeCount(() -> repo.countProposalsForTeam(team)));
        CompletableFuture<List<LeadRow>> cfLeadRows =
            cf(() -> { try { return buildLeadRows(repo.leadsForTeam(team)); } catch (Exception e) { return List.of(); } });

        // The card lists the people under the viewer. A user with no reports
        // gets their own row rather than an empty state.
        CompletableFuture<List<TeamMemberStat>> cfTeam =
            cf(() -> buildTeamStats(repo.teamPerformanceForUsers(tableIds)));

        // Cumulative stages over the same lead set as the total — see the
        // funnel note on buildSalesManagerDashboard.
        CompletableFuture<long[]> cfFunnel = cf(() -> new long[] {
            safeCount(() -> repo.countReachedContactedForTeam(team)),
            safeCount(() -> repo.countReachedDiscussionForTeam(team)),
            safeCount(() -> repo.countReachedProposalForTeam(team)),
        });

        CompletableFuture.allOf(cfPendingFU, cfOverdueFU, cfTodayFU, cfFU,
            cfLeads, cfActive, cfWon, cfProps, cfLeadRows, cfTeam, cfFunnel).join();

        long[] funnel = get(cfFunnel, new long[] { 0L, 0L, 0L });

        return GenericDashboard.builder()
            .roleName(userRole)
            .levelOrder(level)
            .pendingFollowups(get(cfPendingFU, 0L))
            .overdueFollowups(get(cfOverdueFU, 0L))
            .todayFollowups(get(cfTodayFU, 0L))
            .myTasks(0L)          // tasks fetched separately by frontend via /tasks
            .pendingTasks(0L)
            .overdueTasks(0L)
            .myLeads(get(cfLeads, 0L))
            .activeLeads(get(cfActive, 0L))
            .closedWon(get(cfWon, 0L))
            .myProposals(get(cfProps, 0L))
            .reachedContacted(funnel[0])
            .reachedDiscussion(funnel[1])
            .reachedProposal(funnel[2])
            .teamSize(reports.size())
            .teamMembers(get(cfTeam, List.of()))
            .followups(get(cfFU, List.of()))
            .leads(get(cfLeadRows, List.of()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reporting scope
    //
    // One definition of "my team", shared by the manager and generic views and
    // delegated to UserScopeService so the dashboards agree with tasks, project
    // expenses and every assignment dropdown. That service walks
    // users.manager_id — the column the org chart is actually maintained in —
    // and is cycle-safe, so a mis-entered manager_id loop cannot hang a request.
    //
    // Falls back to {userId} rather than an empty set: an empty set would make
    // every `IN (:userIds)` query invalid SQL, and "just me" is the right answer
    // for a user with no reports anyway.
    // ══════════════════════════════════════════════════════════════════════════

    private Set<Long> teamScope(Long userId) {
        if (userId == null) return Set.of();
        try {
            Set<Long> ids = userScopeService.getActionableUserIds(userId);
            if (ids != null && !ids.isEmpty()) return ids;
        } catch (Exception e) {
            log.warn("Team scope lookup failed for userId={}: {}", userId, e.getMessage());
        }
        return Set.of(userId);
    }

    /** The subtree without the viewer — empty when they have no reports. */
    private Set<Long> reportsOnly(Long userId, Set<Long> team) {
        Set<Long> reports = new LinkedHashSet<>(team);
        reports.remove(userId);
        return reports;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mapper helpers — Object[] row → typed DTO
    // ══════════════════════════════════════════════════════════════════════════

    /** Company-wide monthly lead counts. Team-scoped trends go through
     *  countLeadsGroupedByMonthForTeam instead — see the team-scope section. */
    private List<MonthStat> buildMonthlyStats(LocalDateTime since) {
        return fillMonthlyGaps(repo.countLeadsGroupedByMonth(since));
    }

    private List<MonthStat> fillMonthlyGaps(List<Object[]> rows) {
        Map<String, Long> db = new LinkedHashMap<>();
        for (Object[] r : rows) {
            int yr  = toInt(r[0]);
            int mo  = toInt(r[1]);
            long cnt = toLong(r[2]);
            String key = Month.of(mo).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                         + " " + String.valueOf(yr).substring(2);
            db.put(key, cnt);
        }
        List<MonthStat> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now().minusMonths(5).withDayOfMonth(1);
        for (int i = 0; i < 6; i++) {
            String key = cursor.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                         + " " + String.valueOf(cursor.getYear()).substring(2);
            result.add(MonthStat.builder().label(key).value(db.getOrDefault(key, 0L)).build());
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ADMIN — money trend, business snapshot, growth deltas
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Merges the revenue and pipeline month buckets into one series and pads the
     * gaps, so the chart always gets exactly 8 evenly spaced points even for a
     * month with no orders at all. Label format matches fillMonthlyGaps ("MMM yy").
     */
    private List<MonthMoney> buildMonthlyTrend(LocalDateTime since) {
        Map<String, BigDecimal> rev  = sumsByMonth(safeRows(() -> repo.monthlyRevenue(since)));
        Map<String, BigDecimal> pipe = sumsByMonth(safeRows(() -> repo.monthlyPipeline(since)));

        List<MonthMoney> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now().minusMonths(7).withDayOfMonth(1);
        for (int i = 0; i < 8; i++) {
            String key = monthKey(cursor.getYear(), cursor.getMonthValue());
            result.add(MonthMoney.builder()
                .label(key)
                .revenue(rev.getOrDefault(key, BigDecimal.ZERO))
                .pipeline(pipe.getOrDefault(key, BigDecimal.ZERO))
                .build());
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    /** [year, month, sum] rows → { "Aug 26": sum }. */
    private Map<String, BigDecimal> sumsByMonth(List<Object[]> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.put(monthKey(toInt(r[0]), toInt(r[1])), toBD(r[2]));
        }
        return out;
    }

    private String monthKey(int year, int month) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
               + " " + String.valueOf(year).substring(2);
    }

    /**
     * Six independent counts across five tables. Each is wrapped individually so
     * that, say, a missing projects table cannot zero out the customer figures.
     */
    private BusinessSnapshot buildBusinessSnapshot() {
        return BusinessSnapshot.builder()
            .totalProjects(safeCount(() -> repo.countTotalProjects()))
            .activeProjects(safeCount(() -> repo.countActiveProjects()))
            .completedProjects(safeCount(() -> repo.countCompletedProjects()))
            .totalCustomers(safeCount(() -> repo.countTotalCustomers()))
            .activeCustomers(safeCount(() -> repo.countActiveCustomers()))
            .totalVendors(safeCount(() -> repo.countTotalVendors()))
            .build();
    }

    /**
     * Month-over-month growth for the five headline KPIs.
     *
     * Revenue, order book and confirmed orders are true period flows — the value
     * booked in each window.
     *
     * Pipeline and active leads are stocks, not flows: a stock cannot be
     * re-queried "as it stood on the 1st" without history the schema does not
     * keep. Both are therefore approximated by what was CREATED in each window,
     * which answers "are we adding pipeline faster than last month?" rather than
     * "did the pipeline grow?". Documented here because the two questions
     * diverge whenever a lot of old proposals get closed out.
     */
    private KpiDeltas buildKpiDeltas(LocalDateTime lastStart, LocalDateTime thisStart, LocalDateTime now) {
        return KpiDeltas.builder()
            .revenue(pctDelta(
                safeMoney(() -> repo.sumConfirmedRevenueBetween(thisStart, now)),
                safeMoney(() -> repo.sumConfirmedRevenueBetween(lastStart, thisStart))))
            .pipeline(pctDelta(
                safeMoney(() -> repo.sumOpenPipelineCreatedBetween(thisStart, now)),
                safeMoney(() -> repo.sumOpenPipelineCreatedBetween(lastStart, thisStart))))
            .activeLeads(pctDelta(
                safeCount(() -> repo.countActiveLeadsCreatedBetween(thisStart, now)),
                safeCount(() -> repo.countActiveLeadsCreatedBetween(lastStart, thisStart))))
            .orderBook(pctDelta(
                safeMoney(() -> repo.sumOrderBookValueBetween(thisStart, now)),
                safeMoney(() -> repo.sumOrderBookValueBetween(lastStart, thisStart))))
            .confirmedOrders(pctDelta(
                safeCount(() -> repo.countOrdersCreatedBetween(thisStart, now)),
                safeCount(() -> repo.countOrdersCreatedBetween(lastStart, thisStart))))
            .build();
    }

    /** Percentage change, 1 decimal. A zero baseline yields 0.0 — never Infinity. */
    private double pctDelta(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) return 0.0;
        double cur  = current != null ? current.doubleValue() : 0.0;
        double prev = previous.doubleValue();
        return Math.round((cur - prev) / prev * 100.0 * 10.0) / 10.0;
    }

    private double pctDelta(long current, long previous) {
        if (previous == 0) return 0.0;
        return Math.round((current - previous) * 100.0 / previous * 10.0) / 10.0;
    }

    // Per-metric isolation inside a grouped future — same intent as cf(), but it
    // degrades one number instead of the whole block.
    private long safeCount(SupplierThrows<Long> q) {
        try { Long v = q.get(); return v != null ? v : 0L; }
        catch (Exception e) { log.warn("Dashboard count failed: {}", e.getMessage()); return 0L; }
    }

    private BigDecimal safeMoney(SupplierThrows<BigDecimal> q) {
        try { BigDecimal v = q.get(); return v != null ? v : BigDecimal.ZERO; }
        catch (Exception e) { log.warn("Dashboard sum failed: {}", e.getMessage()); return BigDecimal.ZERO; }
    }

    private List<Object[]> safeRows(SupplierThrows<List<Object[]>> q) {
        try { List<Object[]> v = q.get(); return v != null ? v : List.of(); }
        catch (Exception e) { log.warn("Dashboard rows failed: {}", e.getMessage()); return List.of(); }
    }

    // Defaults so the payload always carries the objects, never a null the
    // frontend would have to special-case.
    private BusinessSnapshot emptySnapshot() {
        return BusinessSnapshot.builder().build();
    }

    private KpiDeltas emptyDeltas() {
        return KpiDeltas.builder().build();
    }

    private List<TeamMemberStat> buildTeamStats(List<Object[]> rows) {
        return rows.stream().map(r -> {
            long leadsHandled = toLong(r[3]);
            long leadsWon     = toLong(r[4]);
            return TeamMemberStat.builder()
                .userId(toLongObj(r[0]))
                .name(str(r[1]))
                .role(str(r[2]))
                .leadsHandled(leadsHandled)
                .leadsWon(leadsWon)
                .interested(toLong(r[5]))
                .proposalsSent(toLong(r[6]))
                .revenue(toBD(r[7]))
                .followupsDone(toLong(r[8]))
                .followupsPending(toLong(r[9]))
                .conversionRate(convRate1(leadsWon, leadsHandled))
                .build();
        }).collect(Collectors.toList());
    }

    private List<RecentOrder> buildRecentOrders(List<Object[]> rows) {
        return rows.stream().map(r -> RecentOrder.builder()
            .id(toLongObj(r[0]))
            .orderBookNo(str(r[1]))
            .customerName(str(r[2]))
            .groupName(str(r[3]))
            .subGroupName(str(r[4]))
            .totalAmount(toBD(r[5]))
            .status(str(r[6]))
            .orderDate(toLocalDate(r[7]))
            .build()
        ).collect(Collectors.toList());
    }

    private List<LeadRow> buildLeadRows(List<Object[]> rows) {
        return rows.stream().map(r -> LeadRow.builder()
            .id(toLongObj(r[0]))
            .leadCode(str(r[1]))
            .name(str(r[2]))
            .phone(str(r[3]))
            .status(str(r[4]))
            .telecallerStatus(str(r[5]))
            .groupName(str(r[6]))
            .subGroupName(str(r[7]))
            .source(str(r[8]))
            .createdAt(toLDT(r[9]))
            .build()
        ).collect(Collectors.toList());
    }

    private List<ProposalRow> buildProposalRows(List<Object[]> rows) {
        return rows.stream().map(r -> ProposalRow.builder()
            .id(toLongObj(r[0]))
            .proposalNo(str(r[1]))
            .leadName(str(r[2]))
            .totalValue(toBD(r[3]))
            .status(str(r[4]))
            .createdAt(toLDT(r[5]))
            .build()
        ).collect(Collectors.toList());
    }

    private List<FollowupItem> buildFollowupItems(List<Object[]> rows) {
        return rows.stream().map(r -> FollowupItem.builder()
            .id(toLongObj(r[0]))
            .leadName(str(r[1]))
            .followupType(str(r[2]))
            .scheduledAt(toLDT(r[3]))
            .priority(str(r[4]))
            .status(str(r[5]))
            .assignedToName(str(r[6]))
            .build()
        ).collect(Collectors.toList());
    }

    private List<TelecallerLeadRow> buildTCLeadRows(List<Object[]> rows) {
        return rows.stream().map(r -> TelecallerLeadRow.builder()
            .id(toLongObj(r[0]))
            .leadCode(str(r[1]))
            .name(str(r[2]))
            .phone(str(r[3]))
            .source(str(r[4]))
            .groupName(str(r[5]))
            .subGroupName(str(r[6]))
            .telecallerStatus(str(r[7]))
            .tcDiscussionNote(str(r[8]))
            .handedOffToBD(toInt(r[9]) == 1)
            .bdAssigneeName(str(r[10]))
            .createdAt(toLDT(r[11]))
            .build()
        ).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utility helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Run one dashboard query off the request thread, degrading to null (and a
     * warning) rather than failing the whole payload.
     *
     * <p>The executor is explicit on purpose: the no-executor overload of
     * supplyAsync uses ForkJoinPool.commonPool, which is sized for CPU work and
     * shared with the rest of the JVM. These tasks block on a remote database,
     * so they need a pool sized to the connection pool instead — otherwise the
     * 22 queries behind /dashboard/admin serialise on a machine with few cores
     * and each pays its own network round-trip end to end.
     */
    private <T> CompletableFuture<T> cf(SupplierThrows<T> s) {
        return CompletableFuture.supplyAsync(() -> {
            try { return s.get(); }
            catch (Exception e) {
                log.warn("Dashboard query error: {}", e.getMessage());
                return null;
            }
        }, dashboardExecutor);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(CompletableFuture<T> cf, T fallback) {
        try { T v = cf.join(); return v != null ? v : fallback; }
        catch (Exception e) { return fallback; }
    }

    private int convRate(long won, long total) {
        if (total == 0) return 0;
        return (int) Math.round((won * 100.0) / total);
    }

    // 1-decimal conversion % for the Team Performance block, matching the
    // Team Lead Performance page's round1(100 * won / handled).
    private double convRate1(long won, long total) {
        if (total == 0) return 0.0;
        return Math.round((won * 100.0) / total * 10.0) / 10.0;
    }

    private BigDecimal safeRevenue(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private String     str(Object o)       { return o != null ? o.toString() : null; }
    private Long       toLongObj(Object o) { return o != null ? ((Number) o).longValue() : null; }
    private long       toLong(Object o)    { return o != null ? ((Number) o).longValue() : 0L; }
    private int        toInt(Object o)     { return o != null ? ((Number) o).intValue() : 0; }
    private BigDecimal toBD(Object o)      { return o instanceof BigDecimal bd ? bd : o != null ? new BigDecimal(o.toString()) : BigDecimal.ZERO; }

    private LocalDateTime toLDT(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sd) return sd.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }

    @FunctionalInterface
    private interface SupplierThrows<T> { T get() throws Exception; }
}