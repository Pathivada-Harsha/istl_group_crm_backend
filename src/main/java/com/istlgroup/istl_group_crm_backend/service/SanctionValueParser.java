package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisers shared by the extractors, the service and the derived-value
 * calculator, so a figure typed by a user and the same figure read from a
 * letter land on one canonical value.
 *
 * <p>Money is always plain rupees. Indian sanction letters write amounts as
 * "Rs. 153.75 Crore" or "Rs.2017.56 Lakhs"; a user may type "153.75 Cr" or
 * paste "1537500000". All four parse to the same BigDecimal.
 */
public final class SanctionValueParser {

    private SanctionValueParser() { }

    private static final BigDecimal CRORE = new BigDecimal("10000000");
    private static final BigDecimal LAKH  = new BigDecimal("100000");

    private static final Pattern NUM =
            Pattern.compile("(-?[0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** Date formats seen across the sample letters plus common typed forms. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d MMMM yyyy",  Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy",  Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy",  Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/M/yyyy",     Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd",   Locale.ENGLISH));

    // ── text ────────────────────────────────────────────────────────────────

    public static String clean(String s) {
        if (s == null) return null;
        String t = s.replace('\u00A0', ' ')
                    .replaceAll("\\s+", " ")
                    .trim()
                    // strip a leading label the table walker may have kept
                    .replaceAll("^[:\\-–—]\\s*", "")
                    .trim();
        return t.isEmpty() ? null : t;
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ── money ───────────────────────────────────────────────────────────────

    /**
     * Parse an amount to plain rupees. Returns null when nothing numeric is
     * present — a miss must never become a zero, because zero is a valid amount
     * and would silently corrupt the record.
     */
    public static BigDecimal parseMoney(String raw) {
        String s = clean(raw);
        if (s == null) return null;

        String lower = s.toLowerCase(Locale.ENGLISH);
        Matcher m = NUM.matcher(lower);
        if (!m.find()) return null;

        BigDecimal value;
        try {
            value = new BigDecimal(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }

        // Look at the text after the number for the unit, so "Rs. 205.00 Crore"
        // and "205 Cr" both scale, while a bare "2050000000" does not.
        String tail = lower.substring(m.end());
        if (tail.contains("crore") || tail.matches("^\\s*(cr|crs)\\b.*")) {
            value = value.multiply(CRORE);
        } else if (tail.contains("lakh") || tail.contains("lac")
                || tail.matches("^\\s*(l|lk)\\b.*")) {
            value = value.multiply(LAKH);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Render plain rupees back as "₹153.75 Cr" for display. */
    public static String formatCrore(BigDecimal rupees) {
        if (rupees == null) return null;
        BigDecimal cr = rupees.divide(CRORE, 2, RoundingMode.HALF_UP);
        return "₹" + cr.toPlainString() + " Cr";
    }

    // ── dates ───────────────────────────────────────────────────────────────

    public static LocalDate parseDate(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        // Drop an ordinal suffix: "14th March 2025" → "14 March 2025"
        s = s.replaceAll("(?i)(\\d+)(st|nd|rd|th)\\b", "$1");
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (Exception ignored) {
                // try the next pattern
            }
        }
        return null;
    }

    public static String formatDate(LocalDate d) {
        return d == null ? null : d.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
    }

    // ── interest rate ───────────────────────────────────────────────────────

    /** "10.35% p.a. (floating, linked to 1-yr MCLR + spread)" → 10.35 */
    public static BigDecimal parseRatePct(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%").matcher(s);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    // ── tenor ───────────────────────────────────────────────────────────────

    /**
     * "16 years including moratorium of 6 months" → 192 months total.
     * Also handles a tenor already given in months.
     */
    public static Integer parseTenorMonths(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        String lower = s.toLowerCase(Locale.ENGLISH);

        Matcher years = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:years?|yrs?)").matcher(lower);
        if (years.find()) {
            double y = Double.parseDouble(years.group(1));
            return (int) Math.round(y * 12);
        }
        // No "years" anywhere — a tenor stated directly in months. Take the
        // first number, but only if it isn't the moratorium clause.
        Matcher months = Pattern.compile("([0-9]+)\\s*months?").matcher(lower);
        if (months.find() && !lower.substring(0, months.start()).contains("moratorium")) {
            return Integer.parseInt(months.group(1));
        }
        return null;
    }

    /** Pull the moratorium out of the same phrase, in months. */
    public static Integer parseMoratoriumMonths(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        String lower = s.toLowerCase(Locale.ENGLISH);
        if (!lower.contains("moratorium")) return null;

        String after = lower.substring(lower.indexOf("moratorium"));
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(months?|years?|yrs?)").matcher(after);
        if (m.find()) {
            double n = Double.parseDouble(m.group(1));
            return m.group(2).startsWith("y") ? (int) Math.round(n * 12) : (int) Math.round(n);
        }
        return null;
    }

    // ── misc ────────────────────────────────────────────────────────────────

    public static Integer parseInt(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        Matcher m = Pattern.compile("(-?[0-9]+)").matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal parseDecimal(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        Matcher m = NUM.matcher(s);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
