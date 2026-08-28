package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value grammars: money, dates, and the small normalisations both the regex
 * path and the AI path have to agree on.
 *
 * <p><b>Money</b> arrives in two unrelated dialects. A state NIT writes
 * {@code Rs.12,66,67,376/-} or {@code Rs.2017.56 Lakhs}; an IREPS grid writes a
 * bare {@code 68918769.50} with no symbol, no grouping and decimals present.
 * Both normalise to plain rupees. A bare number is only ever read as money when
 * it was anchored to a money label — the same grid puts
 * {@code Validity of Offer ( Days) 180} and
 * {@code Number of JV Member Allowed 6} on the very same lines.
 *
 * <p><b>Dates are always day-first.</b> {@code 08/07/2026} is 8 July. There is
 * no month-first fallback and no "swap if the first number is over 12" rule:
 * guessing between the two readings is how a submission deadline silently moves
 * a month, and every reference document is Indian.
 */
public final class TenderValues {

    /** Currency markers seen ahead of an amount. */
    static final String CUR = "(?:₹|Rs\\.?|INR|Rupees)";

    /** An amount: Indian or western comma grouping, optional decimals. */
    static final String NUM = "(\\d[\\d,]*(?:\\.\\d+)?)";

    /** Magnitude words that multiply the amount. */
    static final String SCALE =
            "(Lakhs?|Lacs?|Crores?|Cr\\b|Millions?|Mn\\b|Billions?|Bn\\b)";

    static final String MON =
            "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|"
          + "Aug(?:ust)?|Sept?(?:ember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?";

    /** Every date shape seen in tender docs. Day-first everywhere it is ambiguous. */
    static final String DATE =
            "(\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{4}"
          + "|\\d{4}-\\d{1,2}-\\d{1,2}"
          + "|\\d{1,2}[\\s\\-.]{1,3}(?:" + MON + ")[\\s\\-.,]{1,3}\\d{4}"
          + "|(?:" + MON + ")[\\s\\-.]{1,3}\\d{1,2}[\\s,]{1,3}\\d{4})";

    static final Pattern AMOUNT = Pattern.compile(
            CUR + "?\\s*\\.?\\s*" + NUM + "\\s*" + SCALE + "?", Pattern.CASE_INSENSITIVE);

    static final Pattern DATE_PATTERN = Pattern.compile(DATE, Pattern.CASE_INSENSITIVE);

    /**
     * Without a magnitude word, a tender figure is never a handful of rupees — a
     * small bare number is a serial or a page number the scan ran into.
     */
    private static final BigDecimal BARE_MINIMUM = new BigDecimal("1000");

    private TenderValues() {}

    // ── money ────────────────────────────────────────────────────────────────

    /** First amount inside an already label-anchored value, in plain rupees. */
    public static String money(String labelledValue) {
        if (labelledValue == null) return null;
        Matcher m = AMOUNT.matcher(labelledValue);
        while (m.find()) {
            String scale = m.group(2) != null
                    ? m.group(2)
                    : scaleFromContext(labelledValue.substring(0, m.start()));
            String v = rupees(m.group(1), scale);
            if (v != null) return v;
        }
        return null;
    }

    /** Amount + magnitude word to plain rupees: ("2017.56", "Lakhs") to 201756000. */
    public static String rupees(String num, String scale) {
        if (num == null) return null;
        BigDecimal v;
        try {
            v = new BigDecimal(num.replace(",", ""));
        } catch (RuntimeException e) {
            return null;
        }
        BigDecimal mult = multiplier(scale);
        if (mult.equals(BigDecimal.ONE) && v.compareTo(BARE_MINIMUM) < 0) return null;
        v = v.multiply(mult).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return v.toPlainString();
    }

    public static BigDecimal multiplier(String scale) {
        if (scale == null) return BigDecimal.ONE;
        String s = scale.toLowerCase(Locale.ROOT).replace(".", "");
        if (s.startsWith("lakh") || s.startsWith("lac")) return new BigDecimal("100000");
        if (s.startsWith("cr")) return new BigDecimal("10000000");
        if (s.startsWith("million") || s.equals("mn")) return new BigDecimal("1000000");
        if (s.startsWith("billion") || s.equals("bn")) return new BigDecimal("1000000000");
        return BigDecimal.ONE;
    }

    /** "(Rs. in lakhs)" style text sitting between a label and its figure. */
    public static String scaleFromContext(String filler) {
        if (filler == null) return null;
        String l = filler.toLowerCase(Locale.ROOT);
        // Only the text immediately before the figure can qualify its scale.
        if (l.length() > 45) l = l.substring(l.length() - 45);
        if (l.contains("lakh") || l.contains("lac")) return "Lakh";
        if (l.contains("crore")) return "Crore";
        if (l.contains("million")) return "Million";
        return null;
    }

    /**
     * A magnitude word left stranded just past the figure that was converted —
     * "2017.56/- in Lakhs" reads as two thousand rupees unless the Lakhs is
     * noticed. When the word sits immediately after the digits the amount
     * pattern swallows it and the conversion is already right; this catches the
     * cases where punctuation or a filler word got between the two.
     */
    public static boolean hasStrayScaleWord(String labelledValue) {
        if (labelledValue == null) return false;
        Matcher m = AMOUNT.matcher(labelledValue);
        if (!m.find()) return false;                                    // no figure at all
        if (m.group(2) != null) return false;                           // it was consumed
        String after = labelledValue.substring(m.end(),
                Math.min(labelledValue.length(), m.end() + STRAY_SCALE_WINDOW));
        return STRAY_SCALE.matcher(after).find();
    }

    /** How far past a figure a magnitude word can still belong to it. */
    private static final int STRAY_SCALE_WINDOW = 14;

    private static final Pattern STRAY_SCALE = Pattern.compile(
            "^[\\s/\\-.,()]*(?:in\\s+|of\\s+)?" + SCALE, Pattern.CASE_INSENSITIVE);

    // ── dates ────────────────────────────────────────────────────────────────

    /** First date inside an already label-anchored value, as yyyy-MM-dd. */
    public static String date(String labelledValue) {
        if (labelledValue == null) return null;
        Matcher m = DATE_PATTERN.matcher(labelledValue);
        while (m.find()) {
            String iso = toIso(m.group(1));
            if (iso != null) return iso;
        }
        return null;
    }

    /**
     * Any supported spelling to yyyy-MM-dd, or null when it is not a real date.
     * Trailing time components ("17/08/2026 15:00") never reach here — the date
     * pattern stops at the year.
     */
    public static String toIso(String v) {
        if (v == null) return null;
        String s = v.strip();

        Matcher m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(s);
        if (m.find()) {
            return iso(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }

        // dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy — day first, always. A first
        // component over 12 is simply an invalid month here, never a swap cue.
        m = Pattern.compile("^(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})$").matcher(s);
        if (m.find()) {
            return iso(Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }

        m = Pattern.compile("^(\\d{1,2})[\\s\\-.]{1,3}(" + MON + ")[\\s\\-.,]{1,3}(\\d{4})$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            return iso(Integer.parseInt(m.group(3)), monthOf(m.group(2)),
                    Integer.parseInt(m.group(1)));
        }

        m = Pattern.compile("^(" + MON + ")[\\s\\-.]{1,3}(\\d{1,2})[\\s,]{1,3}(\\d{4})$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            return iso(Integer.parseInt(m.group(3)), monthOf(m.group(1)),
                    Integer.parseInt(m.group(2)));
        }
        return null;
    }

    private static String iso(int year, int month, int day) {
        if (year < 1990 || year > 2100) return null;
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (DateTimeException e) {
            return null;                                  // 31 February and friends
        }
    }

    private static int monthOf(String name) {
        switch (name.substring(0, 3).toLowerCase(Locale.ROOT)) {
            case "jan": return 1;  case "feb": return 2;  case "mar": return 3;
            case "apr": return 4;  case "may": return 5;  case "jun": return 6;
            case "jul": return 7;  case "aug": return 8;  case "sep": return 9;
            case "oct": return 10; case "nov": return 11; case "dec": return 12;
            default: return 0;
        }
    }

    // ── financial year ───────────────────────────────────────────────────────

    /**
     * "2025-26" pulled straight out of a tender reference. The reference is the
     * only trustworthy source: TREDA's DNIe-T carries {@code 2025-26} while the
     * document itself was published in June 2026.
     */
    public static String fyFromReference(String ref) {
        if (ref == null) return null;
        Matcher m = Pattern.compile("(20\\d{2})\\s*[-/]\\s*(\\d{2})(?!\\d)").matcher(ref);
        while (m.find()) {
            int start = Integer.parseInt(m.group(1));
            int end = Integer.parseInt(m.group(2));
            if (end == (start + 1) % 100) return start + "-" + m.group(2);
        }
        return null;
    }

    /** Indian FY from an ISO date: Apr–Mar, so 2026-01-15 is 2025-26. */
    public static String fyFromDate(String iso) {
        if (iso == null) return null;
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-\\d{2}$").matcher(iso);
        if (!m.find()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int start = (month >= 4) ? year : year - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    // ── text tidying ─────────────────────────────────────────────────────────

    public static boolean isBlank(String s) {
        return s == null || s.strip().isEmpty();
    }

    public static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    public static String clip(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max).strip();
    }

    public static String tidy(String v) {
        if (v == null) return null;
        String s = v.replaceAll("\\s+", " ")
                    .replaceAll("^[\\s:'\"\\-]+", "")
                    .replaceAll("['\"\\s]+$", "")
                    .strip();
        return s.isEmpty() ? null : s;
    }
}
