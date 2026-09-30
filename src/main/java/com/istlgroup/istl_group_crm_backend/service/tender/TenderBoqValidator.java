package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge the AI's BOQ with the schedule's own row lines.
 *
 * <p>The row lines ({@link TenderBoqLocator}) are exact about the numbers and
 * poor at descriptions, which wrap above and below their row. The AI is the
 * reverse. So each schedule row keeps its item number, quantity, unit and page,
 * and takes the AI's description only when the AI read the same item with the
 * same quantity <em>and</em> described it in the document's own words. An AI
 * row that matches no schedule line is not in the schedule, and is dropped. A
 * schedule row the AI skipped is kept with its best-effort description —
 * a row is never lost to the AI.
 *
 * <p>When the layout has no recognisable row lines, the AI's rows stand alone,
 * and then each must state a quantity that is written in the section.
 */
public final class TenderBoqValidator {

    /** Share of an AI description's words that must occur in the schedule. */
    static final double MIN_WORD_COVERAGE = 0.7;
    static final int MAX_DESCRIPTION = 500;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    public record Result(List<Map<String, Object>> rows, List<TenderParseResult.Discarded> discarded) {}

    private TenderBoqValidator() {}

    /** The schedule as read without the AI: row lines only. */
    public static List<Map<String, Object>> fromLayout(List<TenderBoqLocator.Row> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenderBoqLocator.Row r : rows) out.add(row(r.itemNo(), r.scope(), r.description(), r.unit(), r.quantity(), r.page()));
        return out;
    }

    public static Result validate(List<Map<String, Object>> ai, List<TenderBoqLocator.Row> layout, TenderText section) {
        List<TenderParseResult.Discarded> discarded = new ArrayList<>();
        List<Map<String, Object>> proposed = ai == null ? List.of() : ai;
        TenderClauseFinder finder = new TenderClauseFinder(section);

        if (!layout.isEmpty()) {
            boolean[] used = new boolean[proposed.size()];
            List<Map<String, Object>> out = new ArrayList<>();
            for (TenderBoqLocator.Row r : layout) {
                String desc = r.description();
                String scope = r.scope();
                for (int i = 0; i < proposed.size(); i++) {
                    if (used[i]) continue;
                    Map<String, Object> a = proposed.get(i);
                    if (!sameItem(str(a.get("itemNo")), r.itemNo()) || !sameNumber(str(a.get("quantity")), r.quantity())) continue;
                    used[i] = true;
                    String aiDesc = TenderValues.tidy(str(a.get("description")));
                    if (aiDesc != null && finder.wordCoverage(aiDesc) >= MIN_WORD_COVERAGE) desc = aiDesc;
                    String s = TenderValues.tidy(str(a.get("scope")));
                    if (s != null && finder.wordCoverage(s) >= MIN_WORD_COVERAGE) scope = TenderValues.clip(s, 80);
                    break;
                }
                out.add(row(r.itemNo(), scope, desc, r.unit(), r.quantity(), r.page()));
            }
            for (int i = 0; i < proposed.size(); i++) {
                if (used[i]) continue;
                Map<String, Object> a = proposed.get(i);
                discarded.add(new TenderParseResult.Discarded("boqItems",
                        str(a.get("itemNo")) + " " + str(a.get("description")),
                        "no schedule line has that item number and quantity"));
            }
            return new Result(out, discarded);
        }

        // No row lines to check against: the AI's rows, each with a quantity
        // the section actually states and a description in its words.
        List<Map<String, Object>> out = new ArrayList<>();
        String flat = section.flat();
        for (Map<String, Object> a : proposed) {
            String desc = TenderValues.tidy(str(a.get("description")));
            String qty = str(a.get("quantity"));
            if (desc == null) continue;
            boolean qtyStated = qty == null || statesNumber(flat, qty);
            if (!qtyStated || finder.wordCoverage(desc) < MIN_WORD_COVERAGE) {
                discarded.add(new TenderParseResult.Discarded("boqItems", desc,
                        qtyStated ? "the description is not in the document's words"
                                  : "quantity " + qty + " is not written in the schedule"));
                continue;
            }
            String scope = TenderValues.tidy(str(a.get("scope")));
            out.add(row(str(a.get("itemNo")), scope == null ? "" : TenderValues.clip(scope, 80), desc,
                    str(a.get("unit")), qty == null ? null : qty.replace(",", ""), null));
        }
        return new Result(out, discarded);
    }

    private static Map<String, Object> row(String itemNo, String scope, String desc, String unit, String qty, Integer page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemNo", itemNo == null ? "" : itemNo);
        m.put("scope", scope == null ? "" : scope);
        m.put("description", TenderValues.clip(desc == null ? "" : desc, MAX_DESCRIPTION));
        m.put("unit", unit == null ? "" : unit);
        if (qty != null) m.put("quantity", qty);
        if (page != null) m.put("page", page);
        return m;
    }

    static boolean sameItem(String a, String b) {
        if (a == null || b == null) return false;
        return norm(a).equals(norm(b));
    }

    private static String norm(String item) {
        return item.strip().replaceAll("^(?i)item\\s*", "").replaceAll("[.)\\s]+$", "");
    }

    static boolean sameNumber(String a, String b) {
        BigDecimal x = decimal(a);
        BigDecimal y = decimal(b);
        return x != null && y != null && x.compareTo(y) == 0;
    }

    private static boolean statesNumber(String text, String qty) {
        BigDecimal want = decimal(qty);
        if (want == null) return false;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            BigDecimal d = decimal(m.group());
            if (d != null && d.compareTo(want) == 0) return true;
        }
        return false;
    }

    private static BigDecimal decimal(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", "").strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return TenderValues.aiText(o);
    }
}
