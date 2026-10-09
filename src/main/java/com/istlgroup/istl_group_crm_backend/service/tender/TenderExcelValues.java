package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DateUtil;

/**
 * Turns what an LLM (or a person) typed into an Excel cell into the value the
 * tender stores — tolerant of the usual ways a model writes Indian figures, and
 * strict about everything else.
 *
 * <ul>
 *   <li><b>Money</b> → plain rupees: {@code ₹26.90 Crore}, {@code 25 Lakhs},
 *       {@code Rs. 2,50,00,000/-} and a numeric cell all work. Words without a
 *       figure ("Twenty lakh") are refused, not guessed.</li>
 *   <li><b>Dates</b> → yyyy-MM-dd, <b>always day-first</b> ({@code 08/07/2026} is
 *       8 July), via the same {@link TenderValues#toIso} the PDF parser uses; real
 *       Excel date cells and bare date serials are read as dates.</li>
 *   <li><b>Percent</b> → the number: {@code 5%}, {@code 5}, and a %-formatted
 *       cell holding 0.05 are all 5.</li>
 * </ul>
 *
 * Every method returns a {@link Parsed}: the normalised value, or the reason it
 * could not be normalised (the raw text is kept so the reviewer can fix it).
 */
public final class TenderExcelValues {

    /**
     * One cell as read: at most one of {@code date}/{@code number} is set, and
     * {@code text} is always what a person would see in the cell.
     */
    public record Raw(String text, BigDecimal number, LocalDate date, boolean percentFormat) {
        public static Raw text(String s) { return new Raw(s, null, null, false); }
        public static Raw number(double d, boolean percentFormat) {
            BigDecimal n = new BigDecimal(Double.toString(d)).stripTrailingZeros();
            return new Raw(n.toPlainString(), n, null, percentFormat);
        }
        public static Raw date(LocalDate d) { return new Raw(d.toString(), null, d, false); }
        public boolean isBlank() { return date == null && number == null && TenderValues.isBlank(text); }
    }

    /** Normalised value, or null with the reason it could not be read. */
    public record Parsed(String value, String error) {
        static Parsed ok(String v) { return new Parsed(v, null); }
        static Parsed blank() { return new Parsed(null, null); }
        static Parsed fail(String why) { return new Parsed(null, why); }
        public boolean failed() { return error != null; }
    }

    // ₹, Rs, Rs., INR, Rupees — and the "/-" and "only" that close an Indian amount.
    private static final Pattern CURRENCY = Pattern.compile(
            "₹|\\bINR\\b|\\bRs\\b\\.?|\\bRupees\\b|/-|\\bonly\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(lakhs?|lacs?|lakh|crores?|cr|millions?|mn|billions?|bn)?\\.?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*[A-Za-z.]*$");
    private static final Pattern DATE_THEN_TIME = Pattern.compile("^(\\S+)\\s+\\d{1,2}[:.]\\d{2}.*$");

    /** Excel serials for 1990-01-01 … 2100-12-31; anything else is not a date. */
    private static final double SERIAL_MIN = 32874;
    private static final double SERIAL_MAX = 73415;

    private TenderExcelValues() {}

    // ── money ────────────────────────────────────────────────────────────────

    public static Parsed money(Raw raw) {
        if (raw == null || raw.isBlank()) return Parsed.blank();
        if (raw.number() != null) return positive(raw.number(), raw.text());
        if (raw.date() != null) return Parsed.fail("a date, not an amount");
        return money(raw.text());
    }

    public static Parsed money(String text) {
        if (TenderValues.isBlank(text)) return Parsed.blank();
        String s = CURRENCY.matcher(text).replaceAll(" ")
                .replace(",", "").replace(' ', ' ')
                .replaceAll("\\s+", " ").strip();
        Matcher m = MONEY.matcher(s);
        if (!m.matches()) return Parsed.fail("not an amount: \"" + text.strip() + "\"");
        BigDecimal v = new BigDecimal(m.group(1)).multiply(TenderValues.multiplier(m.group(2)));
        return positive(v, text);
    }

    private static Parsed positive(BigDecimal v, String shown) {
        if (v.signum() <= 0) return Parsed.fail("not a positive amount: \"" + shown + "\"");
        return Parsed.ok(plain(v));
    }

    /** True when the text carries a currency mark or a scale word — i.e. reads as money. */
    public static boolean looksLikeMoney(String text) {
        if (text == null) return false;
        return CURRENCY.matcher(text).find()
            || Pattern.compile("\\d\\s*(lakhs?|lacs?|crores?|cr\\b)", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    // ── dates ────────────────────────────────────────────────────────────────

    public static Parsed date(Raw raw) {
        if (raw == null || raw.isBlank()) return Parsed.blank();
        if (raw.date() != null) return Parsed.ok(raw.date().toString());
        if (raw.number() != null) {
            double serial = raw.number().doubleValue();
            if (serial >= SERIAL_MIN && serial <= SERIAL_MAX) {
                return Parsed.ok(DateUtil.getLocalDateTime(serial).toLocalDate().toString());
            }
            return Parsed.fail("not a date: \"" + raw.text() + "\"");
        }
        return date(raw.text());
    }

    public static Parsed date(String text) {
        if (TenderValues.isBlank(text)) return Parsed.blank();
        String s = text.strip();
        String iso = TenderValues.toIso(s);
        if (iso == null) {
            // "08/07/2026 15:00" — the time is not ours to keep.
            Matcher m = DATE_THEN_TIME.matcher(s);
            if (m.matches()) iso = TenderValues.toIso(m.group(1));
        }
        return iso != null ? Parsed.ok(iso) : Parsed.fail("not a date (use YYYY-MM-DD): \"" + s + "\"");
    }

    // ── percent ──────────────────────────────────────────────────────────────

    public static Parsed percent(Raw raw) {
        if (raw == null || raw.isBlank()) return Parsed.blank();
        if (raw.number() != null) {
            BigDecimal n = raw.percentFormat() ? raw.number().movePointRight(2) : raw.number();
            return Parsed.ok(plain(n));
        }
        if (raw.date() != null) return Parsed.fail("a date, not a percentage");
        return percent(raw.text());
    }

    public static Parsed percent(String text) {
        if (TenderValues.isBlank(text)) return Parsed.blank();
        String s = text.replaceAll("(?i)%|percent(age)?|\\bpc\\b", "").replace(",", ".").strip();
        try {
            return Parsed.ok(plain(new BigDecimal(s)));
        } catch (NumberFormatException e) {
            return Parsed.fail("not a percentage: \"" + text.strip() + "\"");
        }
    }

    // ── plain numbers (BOQ quantity) ─────────────────────────────────────────

    public static Parsed number(Raw raw) {
        if (raw == null || raw.isBlank()) return Parsed.blank();
        if (raw.number() != null) return Parsed.ok(plain(raw.number()));
        if (raw.date() != null) return Parsed.fail("a date, not a number");
        String s = raw.text().replace(",", "").strip();
        Matcher m = NUMBER_WITH_UNIT.matcher(s);           // "120 Nos" → 120
        if (m.matches()) return Parsed.ok(plain(new BigDecimal(m.group(1))));
        return Parsed.fail("not a number: \"" + raw.text().strip() + "\"");
    }

    // ── text ─────────────────────────────────────────────────────────────────

    /**
     * Text as stored: whitespace collapsed. A numeric cell keeps its digits
     * exactly ({@code 9876543210}, not {@code 9.87654321E9}), and a date cell
     * reads as its ISO date.
     */
    public static Parsed text(Raw raw) {
        if (raw == null || raw.isBlank()) return Parsed.blank();
        if (raw.date() != null) return Parsed.ok(raw.date().toString());
        if (raw.number() != null) return Parsed.ok(plain(raw.number()));
        String s = raw.text().replace(' ', ' ').replaceAll("\\s+", " ").strip();
        return s.isEmpty() ? Parsed.blank() : Parsed.ok(s);
    }

    /** Normalise by schema kind. CHOICE is matched by the caller, which holds the lists. */
    public static Parsed byKind(TenderExcelSchema.Kind kind, Raw raw) {
        return switch (kind) {
            case MONEY -> money(raw);
            case DATE -> date(raw);
            case PERCENT -> percent(raw);
            case NUMBER -> number(raw);
            case TEXT, CHOICE -> text(raw);
        };
    }

    static String plain(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.scale() < 0) r = r.setScale(0);
        return r.toPlainString();
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
