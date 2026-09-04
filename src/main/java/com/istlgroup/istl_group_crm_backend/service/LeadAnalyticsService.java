package com.istlgroup.istl_group_crm_backend.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.istlgroup.istl_group_crm_backend.repo.LeadAnalyticsRepo;

@Service
public class LeadAnalyticsService {

    @Autowired private LeadAnalyticsRepo repo;
    @Autowired private com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService roleHierarchyService;
    @Autowired private UserScopeService userScopeService;   // canonical reporting-subtree scoping (C1)

    // Priority weights for the weighted conversion rate. Business-tunable: a High
    // lead converting is worth 3x a Low lead converting. Change here if priorities
    // should be valued differently. Keys are matched case-insensitively.
    private static final Map<String, Double> PRIORITY_WEIGHTS = Map.of(
        "high", 3.0, "medium", 2.0, "low", 1.0
    );

    // Business time zone — "now" and all date-window math resolve here so range
    // edges/buckets don't drift with the JVM default zone (India-based business).
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** Resolve a named range to [from, to). 'to' is exclusive (start of next day).
     *  For range == "custom", customFrom/customTo (ISO yyyy-MM-dd) define the window;
     *  invalid/missing custom bounds fall back to last_12_months. */
    public Map<String, Object> resolveRange(String range, String customFrom, String customTo) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate fromD, toD;
        switch (range == null ? "" : range) {
            case "this_week":
                // Monday-anchored (business week), through today inclusive.
                fromD = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                toD = today.plusDays(1); break;
            case "last_week": {
                // The whole Mon–Sun block before the current one; 'to' is that
                // week's Sunday + 1 day, i.e. this week's Monday (exclusive).
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                fromD = thisMonday.minusWeeks(1); toD = thisMonday; break;
            }
            case "this_month":
                fromD = today.withDayOfMonth(1); toD = today.plusDays(1); break;
            case "last_month":
                YearMonth lm = YearMonth.from(today).minusMonths(1);
                fromD = lm.atDay(1); toD = lm.atEndOfMonth().plusDays(1); break;
            case "last_3_months":
                // Current month + 2 prior, month-aligned. Bucketed WEEKLY (~13 buckets)
                // by granularityFor — the window stays month-aligned so the weekly
                // buckets group cleanly under whole months on the chart's x-axis.
                fromD = today.withDayOfMonth(1).minusMonths(2); toD = today.plusDays(1); break;
            case "this_year":
                fromD = today.withDayOfYear(1); toD = today.plusDays(1); break;
            case "last_year":
                fromD = today.minusYears(1).withDayOfYear(1);
                toD = LocalDate.of(today.getYear(), 1, 1); break;
            case "custom": {
                LocalDate cf = parseDate(customFrom), ct = parseDate(customTo);
                if (cf != null && ct != null && !cf.isAfter(ct)) {
                    fromD = cf; toD = ct.plusDays(1);
                } else {
                    fromD = today.minusMonths(11).withDayOfMonth(1); toD = today.plusDays(1);
                }
                break;
            }
            case "last_12_months":
            default:
                fromD = today.minusMonths(11).withDayOfMonth(1); toD = today.plusDays(1); break;
        }
        Map<String, Object> r = new HashMap<>();
        r.put("from", fromD.atStartOfDay());
        r.put("to", toD.atStartOfDay());
        return r;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    /**
     * Bucket width for the time series, driven by the range (custom by span).
     *
     * Governing principle: never let the chart exceed ~30 buckets per series —
     * the tiers below enforce that for every possible window.
     */
    private String granularityFor(String range, LocalDateTime from, LocalDateTime to) {
        String r = range == null ? "last_12_months" : range;
        if (r.equals("this_week") || r.equals("last_week")) return "daily";   // 7 buckets
        if (r.equals("this_month") || r.equals("last_month")) return "daily";
        if (r.equals("last_3_months")) return "weekly";      // ~13 weekly buckets
        if (r.equals("custom")) {
            long days = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()); // to exclusive → span
            if (days <= 31)  return "daily";
            if (days <= 92)  return "weekly";
            if (days <= 730) return "monthly";
            return "quarterly";
        }
        return "monthly"; // this_year, last_year, last_12_months
    }

    /**
     * Lead analytics for ONE viewer. Every figure below is restricted to the
     * viewer's reporting subtree (UserScopeService) using the same ownership rule
     * the Dashboard uses — assigned_to / bd_assigned_to / closed_by_user_id — so
     * a telecaller sees their own numbers and a manager sees their team's.
     * A top-level viewer (SUPERADMIN / ADMIN / ACCOUNTS_CFO) is unrestricted and
     * keeps seeing company-wide totals, unassigned leads included.
     */
    public Map<String, Object> buildLeadAnalytics(String range, String customFrom, String customTo, Long userId) {
        boolean topLevel = userScopeService.isTopLevel(userId);
        // Never let the id list go empty: 'IN ()' is invalid SQL. -1 is a real,
        // never-matching id, so an unresolvable viewer sees nothing rather than
        // everything — failing closed is the point of this whole block.
        List<Long> scopeIds = new ArrayList<>(
                topLevel ? Collections.<Long>emptySet() : userScopeService.getActionableUserIds(userId));
        if (scopeIds.isEmpty()) scopeIds.add(userId == null ? -1L : userId);
        final List<Long> ids = scopeIds;
        final int all = topLevel ? 1 : 0;
        Map<String, Object> rr = resolveRange(range, customFrom, customTo);
        LocalDateTime from = (LocalDateTime) rr.get("from");
        LocalDateTime to   = (LocalDateTime) rr.get("to");
        String granularity = granularityFor(range, from, to);

        long generated = repo.countGenerated(from, to, ids, all);
        long won       = repo.countWon(from, to, ids, all);
        long closed    = repo.countClosed(from, to, ids, all);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range == null ? "last_12_months" : range);
        out.put("from", from.toLocalDate().toString());
        out.put("to", to.toLocalDate().minusDays(1).toString());
        out.put("granularity", granularity);
        // "all" | "team" | "self" — drives the scope badge in the header so a
        // manager understands why their KPIs are smaller than the company's.
        out.put("scope", topLevel ? "all" : (ids.size() > 1 ? "team" : "self"));

        // Headline KPIs
        out.put("leadsGenerated", generated);
        out.put("leadsWon", won);
        // Conversion ratio: won over everything generated in the window.
        out.put("conversionRate", pct(won, generated));
        // Close rate: won over leads that actually reached a closed state.
        out.put("closeRate", pct(won, closed));

        // Status distribution
        out.put("byStatus", labelCount(repo.countByStatus(from, to, ids, all)));
        // Source distribution
        out.put("bySource", labelCount(repo.countBySource(from, to, ids, all)));
        // Geographic
        out.put("byState", labelCount(repo.countByState(from, to, ids, all)));

        // Per-priority conversion + weighted conversion
        List<Object[]> prio = repo.priorityTotalsAndWon(from, to, ids, all);
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

        // Conversion by subgroup + the UNWEIGHTED average of each subgroup's rate.
        // (Volume-weighting each subgroup rate by its own volume algebraically
        // collapses to the global conversion rate — a no-op; the plain mean instead
        // treats every subgroup equally, surfacing small-but-high/low converters.)
        List<Object[]> grp = repo.groupTotalsAndWon(from, to, ids, all);
        List<Map<String, Object>> groupRows = new ArrayList<>();
        double rateSum = 0.0; int rateCnt = 0;
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
            rateSum += rate;
            rateCnt++;
        }
        out.put("byGroup", groupRows);
        out.put("weightedConversionByGroup", rateCnt == 0 ? 0.0 : round1(rateSum / rateCnt));

        // Per-employee handling
        List<Object[]> emp = repo.employeeHandling(from, to, ids, all);
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

        // Time series — Generated (by creation date) vs Won (by win date), bucketed
        // by granularity and zero-filled across the window so there are no gaps.
        out.put("series", buildSeries(from, to, granularity, ids, all));

        // Time-to-convert (days) from lead_history, minute precision
        List<Object[]> mins = repo.minutesToConvertPerLead(from, to, ids, all);
        List<Double> days = new ArrayList<>();
        for (Object[] row : mins) {
            if (row[1] == null) continue;
            double d = ((Number) row[1]).doubleValue() / 1440.0;
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
    //  TEAM LEAD PERFORMANCE — per-person breakdown, scoped by the reporting
    //  graph, exactly like the Leads "Assign To" picker:
    //    top-level role (hierarchy level 1–2) → every active user
    //    everyone else                        → their reporting subtree
    //                                           (themselves + all transitive reports)
    //
    //  This replaces the old level-band scoping (L1/L2 → all, L3 → the free-text
    //  team lookup, L4+ → self). There is no "level 3" special case any more: a
    //  manager at ANY level sees everyone beneath them, and a user with no reports
    //  sees only themselves. Rows, metrics and columns are unchanged — only the
    //  set of people included moved.
    // ═════════════════════════════════════════════════════════════════════════
    public Map<String, Object> buildTeamLeadPerformance(Long userId, String userRole,
                                                        String range, String customFrom, String customTo) {
        int level = roleHierarchyService.getLevelOrder(userRole);
        boolean topLevel = userScopeService.isTopLevel(userId);

        // No range asked for → all time (the Team Lead Performance page). A range
        // narrows every column, which is what the analytics report asks for. The
        // window is always real dates, never nulls, so the SQL stays one shape.
        boolean allTime = range == null || range.isBlank();
        LocalDateTime from, to;
        if (allTime) {
            from = LocalDate.of(1970, 1, 1).atStartOfDay();
            to   = LocalDate.now(ZONE).plusDays(1).atStartOfDay();
        } else {
            Map<String, Object> rr = resolveRange(range, customFrom, customTo);
            from = (LocalDateTime) rr.get("from");
            to   = (LocalDateTime) rr.get("to");
        }

        List<Long> allowedIds = topLevel
            ? repo.findAllActiveUserIds()
            : new ArrayList<>(userScopeService.getActionableUserIds(userId));

        List<Map<String, Object>> rows = new ArrayList<>();
        if (!allowedIds.isEmpty()) {
            for (Object[] r : repo.teamLeadBreakdown(allowedIds, from, to)) {
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
        // Same three values the frontend's SCOPE_LABEL already understands, but now
        // derived from the subtree's shape rather than a hierarchy level: a manager
        // with reports is "team", a leaf user is "self".
        out.put("scope", topLevel ? "all" : (allowedIds.size() > 1 ? "team" : "self"));
        out.put("levelOrder", level);
        out.put("allTime", allTime);
        out.put("from", from.toLocalDate().toString());
        out.put("to", to.toLocalDate().minusDays(1).toString());
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

    private static final DateTimeFormatter LBL_DAY   = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LBL_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    /** Short month name for the month-grouped weekly x-axis (e.g. "Jun"). */
    private static final DateTimeFormatter LBL_MON_SHORT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    /** Generated (by creation date) vs Won (by win date), bucketed by granularity
     *  and zero-filled across [from, to). Days are aggregated in SQL and rolled up
     *  here, so bucket keys/labels are fully controlled in Java. */
    private List<Map<String, Object>> buildSeries(LocalDateTime from, LocalDateTime to, String granularity,
                                                 List<Long> ids, int all) {
        Map<LocalDate, Long> gen = dailyMap(repo.dailyGenerated(from, to, ids, all));
        Map<LocalDate, Long> won = dailyMap(repo.dailyWonByWinDate(from, to, ids, all));
        LocalDate start = from.toLocalDate();
        LocalDate endEx = to.toLocalDate();               // 'to' is exclusive (start of next day)
        List<Map<String, Object>> series = new ArrayList<>();

        if ("daily".equals(granularity)) {
            for (LocalDate d = start; d.isBefore(endEx); d = d.plusDays(1)) {
                series.add(bucket(d.toString(), d.format(LBL_DAY),
                        gen.getOrDefault(d, 0L), won.getOrDefault(d, 0L)));
            }
        } else if ("weekly".equals(granularity)) {
            LocalDate ws = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (; ws.isBefore(endEx); ws = ws.plusWeeks(1)) {
                LocalDate we = ws.plusWeeks(1);
                Map<String, Object> b = bucket(ws.toString(), ws.format(LBL_DAY),
                        sumRange(gen, ws, we), sumRange(won, ws, we));
                // Month context so the frontend can group the x-axis by month and
                // label only the first week of each month. `bucket`/`label` are
                // deliberately left unchanged.
                LocalDate firstMondayOfMonth =
                        ws.withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                int weekOfMonth = (int) ChronoUnit.WEEKS.between(firstMondayOfMonth, ws) + 1;
                b.put("month", ws.format(LBL_MON_SHORT));
                b.put("weekOfMonth", weekOfMonth);
                b.put("isFirstWeekOfMonth", weekOfMonth == 1);
                series.add(b);
            }
        } else if ("quarterly".equals(granularity)) {
            // NOTE: must stay ABOVE the final else — that else is an unguarded
            // monthly fallback and would swallow this granularity silently.
            YearMonth cur = YearMonth.from(start)
                    .withMonth(((YearMonth.from(start).getMonthValue() - 1) / 3) * 3 + 1);
            YearMonth last = YearMonth.from(endEx.minusDays(1));   // undo the exclusive 'to'
            while (!cur.isAfter(last)) {
                LocalDate qs = cur.atDay(1);
                LocalDate qe = cur.plusMonths(3).atDay(1);
                int q = (cur.getMonthValue() - 1) / 3 + 1;
                series.add(bucket(cur.toString(), "Q" + q + " " + cur.getYear(),
                        sumRange(gen, qs, qe), sumRange(won, qs, qe)));
                cur = cur.plusMonths(3);
            }
        } else { // monthly
            YearMonth cur = YearMonth.from(start);
            YearMonth last = YearMonth.from(endEx.minusDays(1));
            while (!cur.isAfter(last)) {
                LocalDate ms = cur.atDay(1);
                LocalDate me = cur.plusMonths(1).atDay(1);
                series.add(bucket(cur.toString(), ms.format(LBL_MONTH),
                        sumRange(gen, ms, me), sumRange(won, ms, me)));
                cur = cur.plusMonths(1);
            }
        }
        return series;
    }

    private static Map<String, Object> bucket(String key, String label, long generated, long won) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket", key);
        m.put("label", label);
        m.put("generated", generated);
        m.put("won", won);
        return m;
    }

    private static Map<LocalDate, Long> dailyMap(List<Object[]> rows) {
        Map<LocalDate, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            m.put(toLocalDate(r[0]), r[1] == null ? 0L : ((Number) r[1]).longValue());
        }
        return m;
    }

    /** Sum daily counts whose date falls in [s, e). */
    private static long sumRange(Map<LocalDate, Long> m, LocalDate s, LocalDate e) {
        long sum = 0;
        for (Map.Entry<LocalDate, Long> en : m.entrySet()) {
            LocalDate d = en.getKey();
            if (!d.isBefore(s) && d.isBefore(e)) sum += en.getValue();
        }
        return sum;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date) return ((java.sql.Date) o).toLocalDate();
        if (o instanceof LocalDate) return (LocalDate) o;
        if (o instanceof java.sql.Timestamp) return ((java.sql.Timestamp) o).toLocalDateTime().toLocalDate();
        String s = o.toString();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
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