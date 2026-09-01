package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * All dashboard response DTOs in one file.
 * One endpoint per role returns the entire page payload in a single network call.
 * No N+1 queries — all KPIs come from COUNT/SUM aggregations in the DB.
 */
public class DashboardDTO {

    // ─────────────────────────────────────────────────────────────────────────
    // SUPER ADMIN / ADMIN
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminDashboard {
        private long        totalLeads;
        private long        leadsThisMonth;
        private long        closedWon;
        private long        activeLeads;
        private long        totalProposals;
        private long        totalOrders;
        private BigDecimal  orderBookValue;
        /** SUM(invoices.total_amount) — what has been billed, not what was booked. */
        private BigDecimal  totalBilledValue;
        private long        totalInvoices;
        private long        pendingFollowups;
        private long        overdueFollowups;
        private long        todayFollowups;
        private long        contacted;
        private long        inDiscussion;
        private long        proposalSent;
        private List<MonthStat>      monthlyLeads;
        private List<TeamMemberStat> teamPerformance;
        private List<RecentOrder>    recentOrders;
        private List<FollowupItem>   followups;
        // Added for the redesigned admin view. Everything above is untouched, so
        // an older client simply ignores these.
        private BigDecimal           totalRevenue;      // confirmed business (excl. Draft/Cancelled)
        private BigDecimal           pipelineValue;     // open proposals (not Approved/Rejected)
        private List<MonthMoney>     monthlyTrend;      // last 8 months, revenue vs pipeline
        private BusinessSnapshot     businessSnapshot;
        private KpiDeltas            deltas;
        // Cumulative funnel stages ("reached this stage or beyond") so the chart
        // can only descend. contacted / inDiscussion / proposalSent above are the
        // current-status counts and are left alone for the other dashboards.
        private long                 reachedContacted;
        private long                 reachedDiscussion;
        private long                 reachedProposal;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SALES MANAGER
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesManagerDashboard {
        private long        myLeads;
        private long        activeLeads;
        private long        closedWon;
        private long        myProposals;
        private long        acceptedProposals;
        private BigDecimal  revenue;
        private int         conversionRate;
        private long        pendingFollowups;
        private long        todayFollowups;
        private long        overdueFollowups;
        private long        contacted;
        private long        inDiscussion;
        // ── Funnel, CUMULATIVE ──
        // "reached this stage OR beyond", counted over the same lead set as
        // myLeads above, so each stage is a strict subset of the one before it
        // and the funnel can only descend. contacted / inDiscussion stay
        // current-status counts and still feed the KPI cards.
        private long        reachedContacted;
        private long        reachedDiscussion;
        private long        reachedProposal;
        /** People reporting to this manager, transitively. 0 = no reports. */
        private int         teamSize;
        private List<LeadRow>        leads;
        private List<ProposalRow>    proposals;
        private List<TeamMemberStat> teamMembers;
        private List<FollowupItem>   followups;
        private List<MonthStat>      monthlyLeads;   // team lead trend - last 6 months
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BD EXECUTIVE
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BdDashboard {
        private long        totalLeads;
        private long        activeLeads;
        private long        closedWon;
        private long        proposalsSent;
        private long        acceptedProposals;
        private BigDecimal  revenue;
        private int         conversionRate;
        private long        pendingFollowups;
        private long        todayFollowups;
        private long        overdueFollowups;
        private long        inDiscussion;
        // ── Funnel, CUMULATIVE ── see SalesManagerDashboard for the rationale.
        // reachedWon is the same figure as closedWon whenever the BD both owns
        // and closed the lead; it is kept separate so the funnel is guaranteed a
        // subset of the stage above it even when a lead was closed by someone else.
        private long        reachedContacted;
        private long        reachedDiscussion;
        private long        reachedProposal;
        private long        reachedWon;
        private List<LeadRow>      leads;
        private List<ProposalRow>  proposals;
        private List<FollowupItem> followups;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TELECALLER
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TelecallerDashboard {
        private long total;
        private long called;
        private long interested;
        private long notInterested;
        private long notResponded;
        private long pending;
        private long handedOff;
        private long todayFollowups;
        private long overdueFollowups;
        private List<TelecallerLeadRow> leads;
        private List<FollowupItem>      followups;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERIC — for any role not explicitly handled (PROCUREMENT_MANAGER, etc.)
    // Level-scoped: L1/L2 get all-company, L3 get team, L4+ get own
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GenericDashboard {
        private String      roleName;
        private int         levelOrder;       // 1-99

        // Own KPIs (always shown)
        private long        pendingFollowups;
        private long        overdueFollowups;
        private long        todayFollowups;
        private long        myTasks;
        private long        pendingTasks;
        private long        overdueTasks;

        // Lead stats (shown when role has lead access i.e. not purely procurement)
        private long        myLeads;
        private long        activeLeads;
        private long        closedWon;
        private long        myProposals;

        // ── Funnel, CUMULATIVE ── see SalesManagerDashboard for the rationale.
        private long        reachedContacted;
        private long        reachedDiscussion;
        private long        reachedProposal;

        // Team performance — populated for ANYONE with reports, not just L3.
        /** People reporting to this user, transitively. 0 = no reports. */
        private int         teamSize;
        private List<TeamMemberStat> teamMembers;

        // Table data
        private List<FollowupItem>   followups;
        private List<LeadRow>        leads;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthStat {
        private String label;
        private long   value;
    }

    /** One point on the admin Revenue Overview chart. Same "MMM yy" label as MonthStat. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthMoney {
        private String     label;
        private BigDecimal revenue;
        private BigDecimal pipeline;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BusinessSnapshot {
        private long totalProjects;
        private long activeProjects;
        private long completedProjects;
        private long totalCustomers;
        private long activeCustomers;
        private long totalVendors;
    }

    /** Month-over-month growth per KPI, in percentage points (12.4 means +12.4%). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class KpiDeltas {
        private double revenue;
        private double pipeline;
        private double activeLeads;
        private double orderBook;
        private double confirmedOrders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TeamMemberStat {
        private Long        userId;
        private String      name;
        private String      role;
        private long        leadsHandled;
        private long        leadsWon;
        private long        interested;
        private long        proposalsSent;
        private BigDecimal  revenue;
        private long        followupsDone;
        private long        followupsPending;
        private double      conversionRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecentOrder {
        private Long        id;
        private String      orderBookNo;
        private String      customerName;
        private String      groupName;
        private String      subGroupName;
        private BigDecimal  totalAmount;
        private String      status;
        private LocalDate   orderDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeadRow {
        private Long          id;
        private String        leadCode;
        private String        name;
        private String        phone;
        private String        status;
        private String        telecallerStatus;
        private String        groupName;
        private String        subGroupName;
        private String        source;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProposalRow {
        private Long          id;
        private String        proposalNo;
        private String        leadName;
        private BigDecimal    totalValue;
        private String        status;
        private LocalDateTime createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FollowupItem {
        private Long          id;
        private String        leadName;
        private String        followupType;
        private LocalDateTime scheduledAt;
        private String        priority;
        private String        status;
        private String        assignedToName;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TelecallerLeadRow {
        private Long          id;
        private String        leadCode;
        private String        name;
        private String        phone;
        private String        source;
        private String        groupName;
        private String        subGroupName;
        private String        telecallerStatus;
        private String        tcDiscussionNote;
        private boolean       handedOffToBD;
        private String        bdAssigneeName;
        private LocalDateTime createdAt;
    }
}