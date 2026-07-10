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
 *   All revenue queries now use sumRevenueForUser / sumRevenueForBD which
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

    private final DashboardRepo        repo;
    private final RoleHierarchyService roleHierarchyService;
    private final TeamRepository       teamRepository;

    // ══════════════════════════════════════════════════════════════════════════
    // ADMIN / SUPER ADMIN
    // ══════════════════════════════════════════════════════════════════════════

    public AdminDashboard buildAdminDashboard() {
        LocalDateTime now          = LocalDateTime.now();
        LocalDateTime startOfMonth = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime sixMonthsAgo = now.minusMonths(6);

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

        CompletableFuture<List<MonthStat>>      cfMonthly = cf(() -> buildMonthlyStats(sixMonthsAgo, null));
        CompletableFuture<List<TeamMemberStat>> cfTeam    = cf(() -> buildTeamStats(repo.teamPerformanceStats()));
        CompletableFuture<List<RecentOrder>>    cfRecent  = cf(() -> buildRecentOrders(repo.recentOrders()));
        CompletableFuture<List<FollowupItem>>   cfFU      = cf(() -> buildFollowupItems(repo.pendingFollowupsGlobal()));

        CompletableFuture.allOf(
            cfTotal, cfThisMonth, cfWon, cfActive, cfProposals, cfOrders,
            cfOBVal, cfPendingFU, cfOverdueFU, cfTodayFU,
            cfContacted, cfDiscussion, cfPropSent,
            cfMonthly, cfTeam, cfRecent, cfFU
        ).join();

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
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SALES MANAGER
    // Now includes:
    //   - Team monthly lead trend (last 6 months)
    //   - Revenue via closed_by_user_id (FIX)
    //   - Team total leads count
    // ══════════════════════════════════════════════════════════════════════════

    public SalesManagerDashboard buildSalesManagerDashboard(Long userId) {
        LocalDateTime now          = LocalDateTime.now();
        LocalDateTime sixMonthsAgo = now.minusMonths(6);

        CompletableFuture<Long> cfLeads      = cf(() -> repo.countTotalLeadsForManagerTeam(userId));
        CompletableFuture<Long> cfActive     = cf(() -> repo.countActiveLeadsForUser(userId));
        CompletableFuture<Long> cfWon        = cf(() -> repo.countClosedWonForUser(userId));
        CompletableFuture<Long> cfProps      = cf(() -> repo.countProposalsForUser(userId));
        CompletableFuture<Long> cfAccepted   = cf(() -> repo.countAcceptedProposalsForUser(userId));
        // REVENUE FIX: now uses closed_by_user_id based query
        CompletableFuture<BigDecimal> cfRev  = cf(() -> safeRevenue(repo.sumRevenueForUser(userId)));
        CompletableFuture<Long> cfPendingFU  = cf(() -> repo.countPendingFollowupsForUser(userId));
        CompletableFuture<Long> cfOverdueFU  = cf(() -> repo.countOverdueFollowupsForUser(userId, now));
        CompletableFuture<Long> cfTodayFU    = cf(() -> repo.countTodayFollowupsForUser(userId, now));
        CompletableFuture<Long> cfContacted  = cf(() -> repo.countContactedForUser(userId));
        CompletableFuture<Long> cfDiscussion = cf(() -> repo.countInDiscussionForUser(userId));

        CompletableFuture<List<LeadRow>>        cfLeadRows    = cf(() -> buildLeadRows(repo.leadsForUser(userId)));
        CompletableFuture<List<ProposalRow>>    cfPropRows    = cf(() -> buildProposalRows(repo.proposalsForUser(userId)));
        CompletableFuture<List<TeamMemberStat>> cfTeam        = cf(() -> buildTeamStats(repo.teamPerformanceForManager(userId)));
        CompletableFuture<List<FollowupItem>>   cfFU          = cf(() -> buildFollowupItems(repo.pendingFollowupsForUser(userId)));
        // Monthly trend for team
        CompletableFuture<List<MonthStat>>      cfMonthly     = cf(() -> buildMonthlyStatsForManager(userId, sixMonthsAgo));

        CompletableFuture.allOf(
            cfLeads, cfActive, cfWon, cfProps, cfAccepted, cfRev,
            cfPendingFU, cfOverdueFU, cfTodayFU, cfContacted, cfDiscussion,
            cfLeadRows, cfPropRows, cfTeam, cfFU, cfMonthly
        ).join();

        long total = get(cfLeads, 0L);
        long won   = get(cfWon,   0L);

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
            .leads(get(cfLeadRows, List.of()))
            .proposals(get(cfPropRows, List.of()))
            .teamMembers(get(cfTeam, List.of()))
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

        CompletableFuture.allOf(
            cfLeads, cfActive, cfWon, cfDiscussion, cfProps, cfAccepted, cfRev,
            cfPendingFU, cfOverdueFU, cfTodayFU,
            cfLeadRows, cfPropRows, cfFU
        ).join();

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
    // ══════════════════════════════════════════════════════════════════════════

    public GenericDashboard buildGenericDashboard(Long userId, String userRole) {
        LocalDateTime now = LocalDateTime.now();
        int level = roleHierarchyService.getLevelOrder(userRole);

        CompletableFuture<Long> cfPendingFU  = cf(() -> repo.countPendingFollowupsForUser(userId));
        CompletableFuture<Long> cfOverdueFU  = cf(() -> repo.countOverdueFollowupsForUser(userId, now));
        CompletableFuture<Long> cfTodayFU    = cf(() -> repo.countTodayFollowupsForUser(userId, now));
        CompletableFuture<List<FollowupItem>> cfFU = cf(() -> buildFollowupItems(repo.pendingFollowupsForUser(userId)));

        CompletableFuture<Long> cfLeads  = cf(() -> { try { return repo.countLeadsForUser(userId); } catch(Exception e) { return 0L; } });
        CompletableFuture<Long> cfActive = cf(() -> { try { return repo.countActiveLeadsForUser(userId); } catch(Exception e) { return 0L; } });
        CompletableFuture<Long> cfWon    = cf(() -> { try { return repo.countClosedWonForUser(userId); } catch(Exception e) { return 0L; } });
        CompletableFuture<Long> cfProps  = cf(() -> { try { return repo.countProposalsForUser(userId); } catch(Exception e) { return 0L; } });
        CompletableFuture<List<LeadRow>> cfLeadRows = cf(() -> { try { return buildLeadRows(repo.leadsForUser(userId)); } catch(Exception e) { return List.of(); } });

        CompletableFuture<List<TeamMemberStat>> cfTeam = (level == 3)
            ? cf(() -> buildTeamStats(repo.teamPerformanceForManager(userId)))
            : CompletableFuture.completedFuture(List.of());

        CompletableFuture.allOf(cfPendingFU, cfOverdueFU, cfTodayFU, cfFU,
            cfLeads, cfActive, cfWon, cfProps, cfLeadRows, cfTeam).join();

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
            .teamMembers(get(cfTeam, List.of()))
            .followups(get(cfFU, List.of()))
            .leads(get(cfLeadRows, List.of()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mapper helpers — Object[] row → typed DTO
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build monthly stats. If managerId is provided, fetches only that manager's
     * team leads; otherwise fetches company-wide leads.
     */
    private List<MonthStat> buildMonthlyStats(LocalDateTime since, Long managerId) {
        List<Object[]> rows = (managerId != null)
            ? repo.countLeadsGroupedByMonthForManager(managerId, since)
            : repo.countLeadsGroupedByMonth(since);
        return fillMonthlyGaps(rows);
    }

    private List<MonthStat> buildMonthlyStatsForManager(Long managerId, LocalDateTime since) {
        List<Object[]> rows = repo.countLeadsGroupedByMonthForManager(managerId, since);
        return fillMonthlyGaps(rows);
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

    private <T> CompletableFuture<T> cf(SupplierThrows<T> s) {
        return CompletableFuture.supplyAsync(() -> {
            try { return s.get(); }
            catch (Exception e) {
                log.warn("Dashboard query error: {}", e.getMessage());
                return null;
            }
        });
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