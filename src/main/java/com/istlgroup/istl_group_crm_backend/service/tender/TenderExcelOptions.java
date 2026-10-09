package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The fixed vocabularies the template's dropdowns offer and the import matches
 * against. They are <b>sent by the frontend</b> with every request, built from
 * the same constants in {@code tenderData.js} that every dropdown in the app
 * renders — so the template and the app have one source and cannot drift.
 * The one exception is Yes / No, which is not business vocabulary.
 *
 * <p>An empty list means "no vocabulary": the field is then taken as typed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenderExcelOptions(List<String> clientTypes,
                                 List<String> sectors,
                                 List<String> tenderTypes,
                                 List<String> sources,
                                 List<String> eligibilityCategories,
                                 List<Operator> operators) {

    /** An eligibility operator: the stored value ({@code gte}) and what the app shows. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operator(String value, String label) {}

    public static final List<String> YES_NO = List.of("Yes", "No");

    /**
     * How tenders and LLMs write each operator, by stored code. Matched after
     * {@link TenderExcelSchema#norm} and with spaces around "/" removed, so
     * "Not  Less Than" and "yes / no" both land. Longest phrases first, so that
     * {@link #operatorPhraseAt} reads "not less than" before "less than".
     */
    static final Map<String, List<String>> OPERATOR_WORDS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("gte", List.of("greater than or equal to", "not less than", "at least", "atleast", "minimum",
                "more than", "greater than", "min.", "min", "≥", ">=", "=>", ">"));
        m.put("lte", List.of("less than or equal to", "not more than", "not exceeding", "at most", "maximum",
                "less than", "up to", "upto", "max.", "max", "≤", "<=", "=<", "<"));
        m.put("eq", List.of("equal to", "equals", "exactly", "==", "="));
        m.put("contains", List.of("contains", "includes", "class", "grade"));
        m.put("boolean", List.of("yes/no", "y/n", "boolean", "mandatory", "required", "yes"));
        OPERATOR_WORDS = m;
    }

    public static TenderExcelOptions empty() {
        return new TenderExcelOptions(null, null, null, null, null, null);
    }

    /**
     * What a dropdown shows for a list. Operators show the bare symbol or word
     * ("≥", "contains", "yes/no") — short values an LLM copies exactly, where a
     * label like "≥ (at least)" came back as "at least" or "≥ (at least" as
     * often as not.
     */
    public List<String> shown(TenderExcelSchema.ListName list) {
        if (list == TenderExcelSchema.ListName.OPERATOR) {
            List<String> out = new ArrayList<>();
            for (Operator o : safe(operators)) {
                String s = shortLabel(o);
                if (s != null && !out.contains(s)) out.add(s);
            }
            return out;
        }
        return safe(raw(list));
    }

    /** "≥ (at least)" → "≥", "yes / no" → "yes/no", "contains" → "contains". */
    static String shortLabel(Operator o) {
        if (o == null) return null;
        String label = o.label() == null || o.label().isBlank() ? o.value() : o.label();
        if (label == null) return null;
        String s = label.replaceAll("\\(.*?\\)", "").replaceAll("\\s*/\\s*", "/").strip();
        return s.isEmpty() ? o.value() : s;
    }

    /** The stored value for what was written in a cell, or null when nothing matches. */
    public String match(TenderExcelSchema.ListName list, String written) {
        String w = TenderExcelSchema.norm(written);
        if (w.isEmpty()) return null;
        if (list == TenderExcelSchema.ListName.OPERATOR) return matchOperator(w);
        if (list == TenderExcelSchema.ListName.YES_NO) return yesNo(w);
        for (String option : safe(raw(list))) {
            if (w.equals(TenderExcelSchema.norm(option))) return option;
        }
        return null;
    }

    private String matchOperator(String w) {
        for (Operator o : safe(operators)) {
            if (o == null) continue;
            if (w.equals(TenderExcelSchema.norm(o.value())) || w.equals(TenderExcelSchema.norm(o.label()))
                    || w.equals(TenderExcelSchema.norm(shortLabel(o)))) {
                return o.value();
            }
        }
        // The symbol or the words of a label on their own: "≥", "at least".
        for (Operator o : safe(operators)) {
            if (o == null || o.label() == null) continue;
            String label = TenderExcelSchema.norm(o.label());
            String symbol = label.split(" ")[0];
            String words = label.replaceAll("^\\S+\\s*", "").replaceAll("[()]", "").strip();
            if (w.equals(symbol) || (!words.isEmpty() && w.equals(words))) return o.value();
        }
        // How tenders and LLMs write them.
        String key = w.replaceAll("\\s*/\\s*", "/").replaceAll("[.:]+$", "").strip();
        for (Map.Entry<String, List<String>> e : OPERATOR_WORDS.entrySet()) {
            if (has(e.getKey()) && (e.getValue().contains(key) || e.getValue().contains(key + "."))) return e.getKey();
        }
        return null;
    }

    /**
     * The operator a phrase opens with — "More than 10 MWp…" → {@code gte},
     * with the words it used — or null. Used to read an operator out of a
     * Required Value that carries one.
     */
    public static String[] operatorPhraseAt(String text) {
        String t = TenderExcelSchema.norm(text);
        String[] best = null;
        for (Map.Entry<String, List<String>> e : OPERATOR_WORDS.entrySet()) {
            for (String phrase : e.getValue()) {
                if (!t.startsWith(phrase)) continue;
                int end = phrase.length();
                boolean symbol = !Character.isLetter(phrase.charAt(phrase.length() - 1));
                if (!symbol && end < t.length() && Character.isLetterOrDigit(t.charAt(end))) continue;
                if (best == null || phrase.length() > best[1].length()) best = new String[] {e.getKey(), phrase};
            }
        }
        return best;
    }

    private static String yesNo(String w) {
        String s = w.replaceAll("[.!]+$", "").strip();
        return switch (s) {
            case "yes", "y", "true", "refundable" -> "Yes";
            case "no", "n", "false", "non-refundable", "non refundable", "nonrefundable", "not refundable" -> "No";
            default -> null;
        };
    }

    /** True when there is a vocabulary for this list. */
    public boolean constrains(TenderExcelSchema.ListName list) {
        return list == TenderExcelSchema.ListName.OPERATOR ? !safe(operators).isEmpty() : !safe(raw(list)).isEmpty();
    }

    private boolean has(String value) {
        for (Operator o : safe(operators)) if (o != null && value.equals(o.value())) return true;
        return false;
    }

    private List<String> raw(TenderExcelSchema.ListName list) {
        return switch (list) {
            case CLIENT_TYPE -> clientTypes;
            case SECTOR -> sectors;
            case TENDER_TYPE -> tenderTypes;
            case SOURCE -> sources;
            case CATEGORY -> eligibilityCategories;
            case YES_NO -> YES_NO;
            case OPERATOR -> null;
        };
    }

    private static <T> List<T> safe(List<T> l) {
        return l == null ? List.of() : l;
    }
}
