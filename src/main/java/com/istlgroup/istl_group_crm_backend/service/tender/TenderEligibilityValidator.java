package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 4 for eligibility criteria: the AI proposes, the document decides.
 *
 * <p>The AI is asked for a short name, a governing value and two verbatim
 * anchors — the first and last few words of the clause each criterion came
 * from. Everything it says is then checked against the located section text:
 *
 * <ul>
 *   <li><b>Anchored or dropped.</b> A criterion whose opening words cannot be
 *       found in the section is not in the document, whatever it says. The
 *       anchors are also how the verbatim clause text and its page are
 *       recovered, without the AI having to spend output tokens repeating it.
 *   <li><b>Numbers must be in the clause.</b> A required value of "1.82" has to
 *       appear in the clause it cites; a rupee figure has to equal an amount
 *       written there once Lakh/Crore are applied. A figure that is not there is
 *       blanked — the row survives, the invented number does not.
 *   <li><b>OR groups need two members.</b> A group of one is not a choice.
 * </ul>
 *
 * <p>Pure and static, so it is tested on canned AI replies without a network.
 */
public final class TenderEligibilityValidator {

    public static final Set<String> CATEGORIES = Set.of("Technical", "Financial", "Legal", "General");
    public static final Set<String> OPERATORS = Set.of("gte", "lte", "eq", "contains", "boolean");

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final Map<String, Integer> WORD_NUMBERS = new HashMap<>();
    static {
        String[] words = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
                "eighteen", "nineteen", "twenty"};
        for (int i = 0; i < words.length; i++) WORD_NUMBERS.put(words[i], i);
    }

    /** What survived, and what was thrown away with the reason. */
    public record Result(List<Map<String, Object>> criteria, List<TenderParseResult.Discarded> discarded) {}

    private TenderEligibilityValidator() {}

    public static Result validate(List<Map<String, Object>> proposed, TenderText section) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<TenderParseResult.Discarded> discarded = new ArrayList<>();
        if (proposed == null || proposed.isEmpty() || section == null || section.isEmpty()) {
            return new Result(kept, discarded);
        }
        TenderClauseFinder hay = new TenderClauseFinder(section);
        Set<String> seen = new HashSet<>();

        for (Map<String, Object> row : proposed) {
            String name = TenderValues.tidy(str(row.get("criterionName")));
            if (name == null) continue;

            TenderClauseFinder.Clause clause = hay.find(str(row.get("sourceStart")), str(row.get("sourceEnd")));
            if (clause == null) {
                discarded.add(new TenderParseResult.Discarded("eligibilityCriteria", name,
                        "its source clause is not in the document's eligibility section"));
                continue;
            }

            String operator = str(row.get("operator"));
            operator = operator == null ? "" : operator.strip().toLowerCase(Locale.ROOT);
            if (!OPERATORS.contains(operator)) operator = "contains";

            String required = TenderValues.tidy(str(row.get("requiredValue")));
            String note = null;
            if ("boolean".equals(operator)) {
                required = "Required";
            } else if (required != null && !numbersAppearIn(required, clause.text())) {
                note = "The AI read \"" + required + "\", which is not written in the clause — enter it yourself.";
                discarded.add(new TenderParseResult.Discarded("eligibilityCriteria", name + ": " + required,
                        "that value is not written in the clause it cites"));
                required = "";
            }

            String key = name.toLowerCase(Locale.ROOT) + "|" + (required == null ? "" : required);
            if (!seen.add(key)) continue;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("category", category(str(row.get("category"))));
            out.put("criterionName", TenderValues.clip(name, 200));
            out.put("requiredValue", required == null ? "" : required);
            out.put("ourValue", "");
            out.put("operator", operator);
            out.put("altGroup", TenderValues.clip(TenderValues.tidy(str(row.get("altGroup"))), 40));
            out.put("clauseText", clause.text());
            out.put("sourcePage", clause.page());
            if (note != null) out.put("note", note);
            kept.add(out);
        }
        dropLoneGroups(kept);
        return new Result(kept, discarded);
    }

    // ── rules ────────────────────────────────────────────────────────────────

    static String category(String c) {
        if (c == null) return "General";
        for (String k : CATEGORIES) if (k.equalsIgnoreCase(c.strip())) return k;
        return "General";
    }

    /** An OR group needs at least two alternatives; otherwise it is just a criterion. */
    static void dropLoneGroups(List<Map<String, Object>> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object g = r.get("altGroup");
            if (g != null) counts.merge(g.toString(), 1, Integer::sum);
        }
        for (Map<String, Object> r : rows) {
            Object g = r.get("altGroup");
            if (g != null && counts.get(g.toString()) < 2) r.put("altGroup", null);
        }
    }

    /**
     * Every figure in {@code value} must be written in {@code clause} — as the
     * same number, as a rupee amount once its Lakh/Crore is applied, or as a
     * number word ("Five (5) Nos."). A value with no figure in it passes: it is
     * a description, and the clause text travels with it for the reader.
     */
    static boolean numbersAppearIn(String value, String clause) {
        List<BigDecimal> wanted = new ArrayList<>();
        String money = TenderValues.money(value);
        if (money != null) {
            wanted.add(new BigDecimal(money));
        } else {
            Matcher m = NUMBER.matcher(value);
            while (m.find()) {
                BigDecimal d = decimal(m.group());
                if (d != null) wanted.add(d);
            }
        }
        if (wanted.isEmpty()) return true;
        Set<BigDecimal> present = figuresIn(clause);
        for (BigDecimal w : wanted) {
            boolean found = false;
            for (BigDecimal p : present) if (p.compareTo(w) == 0) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    /** Every number the clause states, in every reading of it. */
    static Set<BigDecimal> figuresIn(String clause) {
        Set<BigDecimal> out = new HashSet<>();
        Matcher m = NUMBER.matcher(clause);
        while (m.find()) {
            BigDecimal d = decimal(m.group());
            if (d != null) out.add(d);
        }
        Matcher a = TenderValues.AMOUNT.matcher(clause);
        while (a.find()) {
            if (a.group(2) == null) continue;
            String r = TenderValues.rupees(a.group(1), a.group(2));
            if (r != null) out.add(new BigDecimal(r));
        }
        for (String w : clause.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            Integer n = WORD_NUMBERS.get(w);
            if (n != null) out.add(BigDecimal.valueOf(n));
        }
        // A column-interleaved table can separate a figure from its scale word
        // ("minimum Rs. 6.34 and Mobile Number). crore"). The digits must still
        // be in the clause; a Lakh/Crore anywhere in the same clause may scale them.
        String lower = clause.toLowerCase(Locale.ROOT);
        List<BigDecimal> scales = new ArrayList<>();
        if (lower.matches("(?s).*\\b(?:lakhs?|lacs?)\\b.*")) scales.add(new BigDecimal("100000"));
        if (lower.matches("(?s).*\\bcrores?\\b.*")) scales.add(new BigDecimal("10000000"));
        for (BigDecimal raw : new ArrayList<>(out)) {
            if (raw.scale() <= 0 && raw.compareTo(BigDecimal.valueOf(1000)) >= 0) continue;   // already rupees
            for (BigDecimal s : scales) out.add(raw.multiply(s).stripTrailingZeros());
        }
        return out;
    }

    private static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return TenderValues.aiText(o);
    }

}
