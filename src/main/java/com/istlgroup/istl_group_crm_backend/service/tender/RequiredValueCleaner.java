package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Parsed;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelValues.Raw;

/**
 * Puts an eligibility row's Required Value into the shape the eligibility check
 * compares: a bare figure, with the condition in Operator.
 *
 * <p>LLMs write the threshold the way the tender does — "More than 10 MWp in
 * SPV Power Plants", "Rs. 100 Cr", "Required" — however clearly the prompt asks
 * otherwise, and differently from one model to the next. Each such value is
 * taken apart here rather than refused:
 * <ul>
 *   <li>"Required" / "Yes" / "Mandatory" — a yes/no condition: blank value,
 *       operator yes/no;</li>
 *   <li>money, in figures or words — plain rupees;</li>
 *   <li>a figure with words around it — the figure is the value, the operator
 *       is read from the words when the row has none, and the words go on the
 *       criterion so nothing the tender said is lost.</li>
 * </ul>
 * Every change is reported with the original next to what replaced it; the
 * reviewer sees each one as a warning. A value that is plainly text
 * ("ISO 9001:2015") is left as it was.
 */
public final class RequiredValueCleaner {

    /** What the row becomes, and a warning for every change made to it. */
    public record Cleaned(String requiredValue, String operator, String criterionName, List<String> warnings) {}

    private static final Pattern YES_WORDS = Pattern.compile(
            "^(required|yes|mandatory|compulsory|essential|must|applicable)\\.?$", Pattern.CASE_INSENSITIVE);
    /** A figure, Indian or Western grouped, with an optional decimal part. */
    private static final Pattern FIGURE = Pattern.compile("(?<![\\p{L}\\d.])(\\d[\\d,]*(?:\\.\\d+)?)(?![\\d:/])");
    private static final Pattern PLAIN_FIGURE = Pattern.compile("^\\d[\\d,]*(?:\\.\\d+)?$");

    private RequiredValueCleaner() {}

    /**
     * @param raw        the Required Value cell
     * @param operator   the row's operator code after matching ({@code ""} when blank or unknown)
     * @param criterion  the row's criterion text
     * @param label      how the reviewer reads an operator code ({@code gte} → "≥")
     */
    public static Cleaned clean(Raw raw, String operator, String criterion,
                                java.util.function.Function<String, String> label) {
        String op = operator == null ? "" : operator;
        String crit = criterion == null ? "" : criterion;
        List<String> warnings = new ArrayList<>();
        if (raw == null || raw.isBlank()) return new Cleaned("", op, crit, warnings);
        if (raw.number() != null) return new Cleaned(TenderExcelValues.plain(raw.number()), op, crit, warnings);

        String original = raw.text().strip().replace(' ', ' ').replaceAll("\\s+", " ");

        // "Required" — a condition met or not, with no figure to compare. A row
        // already marked yes/no (as the app itself exports one) is left as it is:
        // the yes/no check never reads the value.
        if (YES_WORDS.matcher(original).matches()) {
            if ("boolean".equals(op)) return new Cleaned(original, op, crit, warnings);
            warnings.add("Required Value \"" + original + "\" → blank, operator " + label.apply("boolean")
                    + " (a yes/no condition).");
            return new Cleaned("", "boolean", crit, warnings);
        }

        // A bare figure: only grouping commas to drop.
        if (PLAIN_FIGURE.matcher(original).matches()) {
            String v = original.replace(",", "");
            if (!v.equals(original)) warnings.add("Required Value \"" + original + "\" → " + v + ".");
            return new Cleaned(plain(v), op, crit, warnings);
        }

        // The operator, when the text opens with one ("More than", "≥", "Minimum").
        String[] lead = TenderExcelOptions.operatorPhraseAt(original);
        String rest = lead == null ? original : original.substring(Math.min(original.length(), lead[1].length())).strip();

        // Money, written as money: the whole cell, or an amount opening the text.
        if (TenderExcelValues.looksLikeMoney(rest)) {
            Parsed whole = TenderExcelValues.money(rest);
            String value = !whole.failed() && whole.value() != null ? whole.value() : TenderValues.money(rest);
            if (value != null) {
                String opNow = op;
                StringBuilder w = new StringBuilder("Required Value \"" + original + "\" → " + value);
                if (lead != null && op.isEmpty()) {
                    opNow = lead[0];
                    w.append(", operator ").append(label.apply(opNow)).append(" from \"").append(lead[1]).append('"');
                }
                String critNow = crit;
                if (whole.failed()) {
                    // Words beyond the amount ("Rs 50 Cr in each of the last 3 years") belong to the criterion.
                    critNow = withWords(crit, rest);
                    if (!critNow.equals(crit)) w.append("; the wording was added to the criterion");
                }
                warnings.add(w + ".");
                return new Cleaned(value, opNow, critNow, warnings);
            }
        }

        // Plain text, not a threshold: "ISO 9001:2015", "Class I", "As per NIT".
        // Only a value that opens with an operator or a figure is taken apart.
        Matcher f = FIGURE.matcher(rest);
        boolean opensWithFigure = f.find() && f.start() == 0;
        if (lead == null && !opensWithFigure) return new Cleaned(original, op, crit, warnings);
        if (!opensWithFigure && !f.find(0)) return new Cleaned(original, op, crit, warnings);

        String value = plain(f.group(1).replace(",", ""));
        String opNow = op;
        StringBuilder w = new StringBuilder("Required Value \"" + original + "\" → " + value);
        if (lead != null && op.isEmpty()) {
            opNow = lead[0];
            w.append(", operator ").append(label.apply(opNow)).append(" from \"").append(lead[1]).append('"');
        }
        String critNow = withWords(crit, rest);
        if (!critNow.equals(crit)) w.append("; \"").append(rest).append("\" was added to the criterion");
        Set<String> figures = new LinkedHashSet<>();
        Matcher all = FIGURE.matcher(rest);
        while (all.find()) figures.add(plain(all.group(1).replace(",", "")));
        if (figures.size() > 1) {
            w.append(". It states more than one figure (").append(String.join(", ", figures))
             .append(") — only the first is compared; check the criterion");
        }
        warnings.add(w + ".");
        return new Cleaned(value, opNow, critNow, warnings);
    }

    /**
     * The criterion with the descriptive words appended, unless the criterion
     * already says them. "Solar EPC experience" + "10 MWp in SPV Power Plants"
     * → "Solar EPC experience (10 MWp in SPV Power Plants)".
     */
    static String withWords(String criterion, String words) {
        if (words == null || words.isBlank()) return criterion;
        Set<String> have = new LinkedHashSet<>(tokens(criterion));
        List<String> said = tokens(words).stream().filter(t -> t.length() >= 2 && !t.matches("\\d+")).toList();
        if (said.isEmpty() || have.containsAll(said)) return criterion;
        return criterion.isBlank() ? words : criterion + " (" + words + ")";
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String plain(String digits) {
        try {
            return TenderExcelValues.plain(new BigDecimal(digits));
        } catch (NumberFormatException e) {
            return digits;
        }
    }
}
