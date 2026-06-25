package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.istlgroup.istl_group_crm_backend.repo.LeadAnalyticsRepo;

@Service
public class LeadAnalyticsService {

    @Autowired private LeadAnalyticsRepo repo;
    @Autowired private com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService roleHierarchyService;
    @Autowired private com.istlgroup.istl_group_crm_backend.repo.TeamRepository teamRepository;

    // Priority weights for the weighted conversion rate. Business-tunable: a High
    // lead converting is worth 3x a Low lead converting. Change here if priorities
    // should be valued differently. Keys are matched case-insensitively.
    private static final Map<String, Double> PRIORITY_WEIGHTS = Map.of(
        "high", 3.0, "medium", 2.0, "low", 1.0
    );

    /** Resolve a named range to [from, to). 'to' is exclusive (start of next day). */
    public Map<String, Object> resolveRange(String range) {
        LocalDate today = LocalDate.now();
        LocalDate fromD, toD;
        switch (range == null ? "" : range) {
            case "this_month":
                fromD = today.withDayOfMonth(1); toD = today.plusDays(1); break;
            case "last_month":
                YearMonth lm = YearMonth.from(today).minusMonths(1);
                fromD = lm.atDay(1); toD = lm.atEndOfMonth().plusDays(1); break;
            case "last_3_months":
                fromD = today.minusMonths(3); toD = today.plusDays(1); break;
            case "this_year":
                fromD = today.withDayOfYear(1); toD = today.plusDays(1); break;
            case "last_year":
                fromD = today.minusYears(1).withDayOfYear(1);
                toD = LocalDate.of(today.getYear(), 1, 1); break;
            case "last_12_months":
            default:
                fromD = today.minusMonths(11).withDayOfMonth(1); toD = today.plusDays(1); break;
        }
        Map<String, Object> r = new HashMap<>();
        r.put("from", fromD.atStartOfDay());
        r.put("to", toD.atStartOfDay());
        return r;
    }

    public Map<String, Object> buildLeadAnalytics(String range) {
        Map<String, Object> rr = resolveRange(range);
        LocalDateTime from = (LocalDateTime) rr.get("from");
        LocalDateTime to   = (LocalDateTime) rr.get("to");

        long generated = repo.countGenerated(from, to);
        long won       = repo.countWon(from, to);
        long closed    = repo.countClosed(from, to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range == null ? "last_12_months" : range);
        out.put("from", from.toLocalDate().toString());
        out.put("to", to.toLocalDate().minusDays(1).toString());

        // Headline KPIs
        out.put("leadsGenerated", generated);
        out.put("leadsWon", won);
        // Conversion ratio: won over everything generated in the window.
        out.put("conversionRate", pct(won, generated));
        // Close rate: won over leads that actually reached a closed state.
        out.put("closeRate", pct(won, closed));

        // Status distribution
        out.put("byStatus", labelCount(repo.countByStatus(from, to)));
        // Source distribution
        out.put("bySource", labelCount(repo.countBySource(from, to)));
        // Geographic
        out.put("byState", labelCount(repo.countByState(from, to)));

        // Per-priority conversion + weighted conversion
        List<Object[]> prio = repo.priorityTotalsAndWon(from, to);
        List<Map<String, Object>> priorityRows = new ArrayList<>();
        double weightedNum = 0.0, weightedDen = 0.0;
        for (Object[] row : prio) {
            String label = String.valueOf(row[0]);
            long total = ((Number) row[1]).longValue();
            long w     = row[2] == null ? 0 : ((Number) row[2]).longValue();
            double rate = total == 0 ? 0.0 : (100.0 * w / total);
            double weight = PRIORITY_WEIGHTS.getOrDefault(label.toLowerCase(), 0.0);
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("priority", label);
            pr.put("total", total);
            pr.put("won", w);
            pr.put("rate", round1(rate));
            pr.put("weight", weight);
            priorityRows.add(pr);
            // Weight by the configured priority weight scaled by tier volume, so a
            // tier with more leads and a higher weight pulls the blended rate more.
            double effectiveWeight = weight * total;
            weightedNum += effectiveWeight * rate;
            weightedDen += effectiveWeight;
        }
        out.put("byPriority", priorityRows);
        out.put("weightedConversionRate", weightedDen == 0 ? 0.0 : round1(weightedNum / weightedDen));

        // Weighted conversion by GROUP (group_name): each group's conversion rate
        // weighted by the number of leads in that group. This answers "where does
        // our conversion really come from" across business lines (EPC, IoT, etc).
        List<Object[]> grp = repo.groupTotalsAndWon(from, to);
        List<Map<String, Object>> groupRows = new ArrayList<>();
        double gNum = 0.0, gDen = 0.0;
        for (Object[] row : grp) {
            String label = String.valueOf(row[0]);
            long total = ((Number) row[1]).longValue();
            long w     = row[2] == null ? 0 : ((Number) row[2]).longValue();
            double rate = total == 0 ? 0.0 : (100.0 * w / total);
            Map<String, Object> gr = new LinkedHashMap<>();
            gr.put("group", label);
            gr.put("total", total);
            gr.put("won", w);
            gr.put("rate", round1(rate));
            groupRows.add(gr);
            gNum += total * rate;
            gDen += total;
        }
        out.put("byGroup", groupRows);
        out.put("weightedConversionByGroup", gDen == 0 ? 0.0 : round1(gNum / gDen));

        // Per-employee handling
        List<Object[]> emp = repo.employeeHandling(from, to);
        List<Map<String, Object>> empRows = new ArrayList<>();
        for (Object[] row : emp) {
            long handled = ((Number) row[2]).longValue();
            long w       = row[3] == null ? 0 : ((Number) row[3]).longValue();
            Map<String, Object> er = new LinkedHashMap<>();
            er.put("name", String.valueOf(row[1]));
            er.put("handled", handled);
            er.put("won", w);
            er.put("rate", handled == 0 ? 0.0 : round1(100.0 * w / handled));
            empRows.add(er);
        }
        out.put("byEmployee", empRows);

        // Monthly time series, zero-filled across the window so the line has no gaps
        out.put("monthly", buildMonthly(repo.monthlySeries(from, to), from, to));

        // Time-to-convert (days) from lead_history
        List<Object[]> hrs = repo.hoursToConvertPerLead(from, to);
        List<Double> days = new ArrayList<>();
        for (Object[] row : hrs) {
            if (row[1] == null) continue;
            double d = ((Number) row[1]).doubleValue() / 24.0;
            if (d < 0) d = 0;          // guard against clock skew in history rows
            days.add(d);
        }
        out.put("convertedSampleSize", days.size());
        out.put("avgDaysToConvert", days.isEmpty() ? null : round1(avg(days)));
        out.put("medianDaysToConvert", days.isEmpty() ? null : round1(median(days)));

        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════
    //  TEAM LEAD PERFORMANCE — per-person breakdown, scoped exactly like the
    //  Leads-Enquiry page: L1/L2 see everyone, L3 see their team, L4+ see self.
    // ═════════════════════════════════════════════════════════════════════════
    public Map<String, Object> buildTeamLeadPerformance(Long userId, String userRole) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        List<Long> allowedIds;
        if (level <= 2) {
            allowedIds = repo.findAllActiveUserIds();
        } else if (level == 3) {
            List<Long> members = teamRepository.findTeamMemberIdsByUserId(userId);
            allowedIds = (members == null || members.isEmpty())
                ? Collections.singletonList(userId) : members;
        } else {
            allowedIds = Collections.singletonList(userId);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (!allowedIds.isEmpty()) {
            for (Object[] r : repo.teamLeadBreakdown(allowedIds)) {
                long assigned = num(r[4]);
                long won      = num(r[6]);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", num(r[0]));
                m.put("name", String.valueOf(r[1]));
                m.put("role", r[2] == null ? "" : String.valueOf(r[2]));
                m.put("created", num(r[3]));
                m.put("assigned", assigned);
                m.put("owned", num(r[5]));
                m.put("won", won);
                m.put("conversionRate", assigned == 0 ? 0.0 : round1(100.0 * won / assigned));
                rows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", level <= 2 ? "all" : (level == 3 ? "team" : "self"));
        out.put("levelOrder", level);
        out.put("members", rows);
        return out;
    }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    private static List<Map<String, Object>> labelCount(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", String.valueOf(r[0]));
            m.put("count", ((Number) r[1]).longValue());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> buildMonthly(List<Object[]> rows, LocalDateTime from, LocalDateTime to) {
        Map<String, long[]> got = new HashMap<>();
        for (Object[] r : rows) {
            got.put(String.valueOf(r[0]), new long[]{
                ((Number) r[1]).longValue(),
                r[2] == null ? 0 : ((Number) r[2]).longValue()
            });
        }
        List<Map<String, Object>> series = new ArrayList<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM");
        YearMonth cur = YearMonth.from(from);
        YearMonth end = YearMonth.from(to.minusDays(1));
        while (!cur.isAfter(end)) {
            String key = cur.format(f);
            long[] v = got.getOrDefault(key, new long[]{0, 0});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", key);
            m.put("generated", v[0]);
            m.put("won", v[1]);
            series.add(m);
            cur = cur.plusMonths(1);
        }
        return series;
    }

    private static double pct(long num, long den) { return den == 0 ? 0.0 : round1(100.0 * num / den); }
    private static double avg(List<Double> xs) { double s = 0; for (double x : xs) s += x; return s / xs.size(); }
    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs); Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
    private static double round1(double d) { return Math.round(d * 10.0) / 10.0; }
}