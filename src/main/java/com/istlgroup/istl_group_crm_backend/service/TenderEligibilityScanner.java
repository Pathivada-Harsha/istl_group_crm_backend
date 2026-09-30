package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderValues;

/**
 * The eligibility figures that can be read without the AI, from the located
 * qualifying-requirements section. Never imported without the reviewer ticking
 * it. The bill of quantities has its own locator, {@code TenderBoqLocator}.
 */
final class TenderEligibilityScanner {

    // ── eligibility: only what the section states with a value ───────────────

    /**
     * The criteria that can be read without understanding the clause: a rupee
     * figure after a financial label, a named licence class, a blacklisting
     * condition. Each carries the sentence it came from and its page.
     *
     * <p>Only the located eligibility section is scanned. The old scan ran over
     * the whole document and emitted fixed rows — "Similar Work Experience: As
     * per NIT" whenever "similar works" appeared anywhere in 200 pages — which
     * looked like a reading of the clause and was not one. Experience clauses
     * are read by the AI or not at all.
     */
    static List<Map<String, Object>> eligibility(TenderText section) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (section == null || section.isEmpty()) return out;
        String flat = section.flat();

        Hit turnover = moneyAfter(flat,
                "(?:Average\\s+)?Annual\\s+(?:Financial\\s+)?[Tt]urnover|Average\\s+Turnover"
              + "|minimum\\s+financial\\s+turnover|financial\\s+turnover\\s+of");
        if (turnover != null) {
            out.add(criterion(section, flat, turnover, "Financial", "Minimum annual turnover", "gte"));
        }

        Hit liquid = moneyAfter(flat,
                "Liquid\\s+Assets|[Ww]orking\\s+[Cc]apital|credit\\s+facilities\\s+of|Solvency");
        if (liquid != null) {
            out.add(criterion(section, flat, liquid, "Financial", "Liquid assets / credit facility", "gte"));
        }

        Hit netWorth = moneyAfter(flat, "Net\\s*Worth");
        if (netWorth != null) {
            out.add(criterion(section, flat, netWorth, "Financial", "Minimum net worth", "gte"));
        }

        Hit licence = group(flat,
                "((?:Super\\s+Grade|Class[\\s\\-]*A|Class[\\s\\-]*1|Class[\\s\\-]*I)\\s+"
              + "Electrical\\s+Contractors?'?s?\\s+Licen[cs]e)");
        if (licence != null) {
            out.add(criterion(section, flat, licence, "Legal", "Electrical contractor licence", "contains"));
        }

        Hit blacklisted = group(flat, "((?:not\\s+have\\s+been\\s+)?[Bb]lack\\s?listed)");
        if (blacklisted != null) {
            out.add(criterion(section, flat, new Hit("Required", blacklisted.start(), blacklisted.end()),
                    "Legal", "Not blacklisted / debarred", "boolean"));
        }
        return out;
    }

    /** A value and where in the flattened section it was read. */
    private record Hit(String value, int start, int end) {}

    private static Map<String, Object> criterion(TenderText section, String flat, Hit hit,
                                                 String category, String name, String operator) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("category", category);
        c.put("criterionName", name);
        c.put("requiredValue", hit.value());
        c.put("ourValue", "");
        c.put("operator", operator);
        c.put("clauseText", sentenceAround(flat, hit.start(), hit.end()));
        c.put("sourcePage", section.pageAtFlatOffset(hit.start()));
        return c;
    }

    /** How far past a label an eligibility figure is still that label's figure. */
    private static final int MONEY_WINDOW = 120;
    /** The longest sentence quoted back as a criterion's source. */
    private static final int MAX_SENTENCE = 400;

    private static Hit moneyAfter(String flat, String labelAlt) {
        Matcher label = Pattern.compile("(?:" + labelAlt + ")", Pattern.CASE_INSENSITIVE).matcher(flat);
        while (label.find()) {
            String window = flat.substring(label.end(),
                    Math.min(flat.length(), label.end() + MONEY_WINDOW));
            String v = TenderValues.money(window);
            if (v != null) return new Hit(v, label.start(), label.end() + window.length());
        }
        return null;
    }

    private static Hit group(String src, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(src);
        return m.find() ? new Hit(TenderValues.tidy(m.group(1)), m.start(1), m.end(1)) : null;
    }

    /** The sentence holding {@code [start, end)}, clipped to a readable length. */
    private static String sentenceAround(String flat, int start, int end) {
        int from = flat.lastIndexOf(". ", start);
        from = from < 0 || start - from > MAX_SENTENCE / 2 ? Math.max(0, start - 40) : from + 2;
        int to = flat.indexOf(". ", end);
        to = to < 0 || to - from > MAX_SENTENCE ? Math.min(flat.length(), from + MAX_SENTENCE) : to + 1;
        return flat.substring(from, to).replaceAll("\\s+", " ").strip();
    }
}
