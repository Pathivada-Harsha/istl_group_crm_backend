package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderExcelSchema.Kind;

/**
 * Checks each value of a filled template against the tender PDF it was filled
 * from, so that the import trusts the document and not whichever LLM wrote the
 * spreadsheet.
 *
 * <p>Every value carries its Source Text — the words of the PDF it was read
 * from. Two things are checked:
 * <ol>
 *   <li>the source text is really in the PDF ({@link TenderClauseFinder#locate},
 *       tolerant of spacing, line breaks, case, punctuation and hyphenation);</li>
 *   <li>the value is what that passage says: 59000 is in "Rs.59,000/-",
 *       2026-03-31 in "31/03/2026", 1000000000 in "Rs. 100 Cr".</li>
 * </ol>
 * The checks run on the passage as the PDF prints it, never on the LLM's copy
 * of it, so a source quoted correctly but paired with the wrong figure is
 * still caught.
 *
 * <p>Results: {@link #VERIFIED} (both pass), {@link #UNVERIFIED} (no source,
 * or not found in the PDF, or the PDF could not be read) and {@link #MISMATCH}
 * (found, but the value is not what it says). A value without a matching
 * passage is never verified.
 */
public final class TenderExcelVerifier {

    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";
    public static final String MISMATCH = "MISMATCH";

    /** Share of a text value's words the passage must contain, for a single field. */
    static final double FIELD_TEXT_COVERAGE = 0.7;
    /** For table rows, whose text the LLM shortens more freely (document names, BOQ descriptions). */
    static final double ROW_TEXT_COVERAGE = 0.6;

    /**
     * @param status  VERIFIED / UNVERIFIED / MISMATCH
     * @param pdfText the passage as the PDF prints it (null when it was not found)
     * @param page    the page it starts on (null when not found)
     */
    public record Check(String status, String pdfText, Integer page, String reason) {
        public boolean mismatch() { return MISMATCH.equals(status); }
    }

    /** One value of a table row to hold against the row's source. */
    public record Expect(String label, Kind kind, String value) {}

    /**
     * What the values are checked against: the PDF's text, or the reason there
     * is nothing to check against (a scan, an older template, an expired session).
     */
    public static final class Evidence {
        private final TenderClauseFinder finder;
        private final String unavailable;

        private Evidence(TenderClauseFinder finder, String unavailable) {
            this.finder = finder;
            this.unavailable = unavailable;
        }

        /** The cleaned text of the tender PDF. */
        public static Evidence of(TenderText cleaned) {
            return new Evidence(new TenderClauseFinder(cleaned), null);
        }

        public static Evidence unavailable(String why) {
            return new Evidence(null, why);
        }

        public boolean available() { return finder != null; }
        public String reason() { return unavailable; }
    }

    private TenderExcelVerifier() {}

    /** A single field (a Basic Info / Dates & Money / EMD / Fee row). Null for a blank value. */
    public static Check field(Kind kind, String value, String sourceText, Evidence ev) {
        if (TenderValues.isBlank(value)) return null;
        Located at = locate(sourceText, ev);
        if (at.check != null) return at.check;
        String why = consistent(kind, value, at.clause.text(), FIELD_TEXT_COVERAGE);
        return why == null
                ? new Check(VERIFIED, at.clause.text(), at.clause.page(), null)
                : new Check(MISMATCH, at.clause.text(), at.clause.page(), why);
    }

    /** A table row: its one source, against each value that has to agree with it. */
    public static Check row(String sourceText, List<Expect> expects, Evidence ev) {
        Located at = locate(sourceText, ev);
        if (at.check != null) return at.check;
        List<String> wrong = new ArrayList<>();
        for (Expect e : expects) {
            if (TenderValues.isBlank(e.value())) continue;
            String why = consistent(e.kind(), e.value(), at.clause.text(), ROW_TEXT_COVERAGE);
            if (why != null) wrong.add(e.label() + ": " + why);
        }
        return wrong.isEmpty()
                ? new Check(VERIFIED, at.clause.text(), at.clause.page(), null)
                : new Check(MISMATCH, at.clause.text(), at.clause.page(), String.join(" ", wrong));
    }

    private record Located(TenderClauseFinder.Clause clause, Check check) {}

    private static Located locate(String sourceText, Evidence ev) {
        if (ev == null || !ev.available()) {
            return new Located(null, new Check(UNVERIFIED, null, null,
                    ev == null ? "No PDF to check against." : ev.reason()));
        }
        if (TenderValues.isBlank(sourceText)) {
            return new Located(null, new Check(UNVERIFIED, null, null, "No Source Text was given for this value."));
        }
        TenderClauseFinder.Clause c = ev.finder.locate(sourceText);
        if (c == null) {
            return new Located(null, new Check(UNVERIFIED, null, null, "The Source Text was not found in the PDF."));
        }
        return new Located(c, null);
    }

    /** Null when the value is what the passage says; otherwise why it is not. */
    static String consistent(Kind kind, String value, String passage, double textCoverage) {
        String v = value.strip();
        switch (kind) {
            case MONEY, NUMBER, PERCENT: {
                BigDecimal want;
                try {
                    want = new BigDecimal(v);
                } catch (NumberFormatException e) {
                    return textConsistent(v, passage, textCoverage);
                }
                for (BigDecimal have : TenderEligibilityValidator.figuresIn(passage)) {
                    if (have.compareTo(want) == 0) return null;
                }
                return "the PDF passage does not state " + v + ".";
            }
            case DATE: {
                Matcher m = TenderValues.DATE_PATTERN.matcher(passage);
                while (m.find()) {
                    if (v.equals(TenderValues.toIso(m.group(1)))) return null;
                }
                return "the PDF passage does not give the date " + v + ".";
            }
            case CHOICE:
                // A classification ("PSU", "Solar EPC") is the reader's call, not a quote.
                return null;
            default:
                return textConsistent(v, passage, textCoverage);
        }
    }

    private static String textConsistent(String value, String passage, double coverage) {
        Set<String> have = Set.copyOf(words(passage));
        // Written out whole, ignoring separators ("abc@xyz.com", "SBIN0001234").
        // Short values must match as words: "UP" is not in "supply".
        String sq = squeeze(value);
        if (sq.length() >= 6 && squeeze(passage).contains(sq)) return null;
        List<String> all = words(value);
        if (!all.isEmpty() && have.containsAll(all)) return null;
        List<String> words = all.stream().filter(w -> w.length() >= 3).distinct().toList();
        if (!words.isEmpty()) {
            long hit = words.stream().filter(have::contains).count();
            if ((double) hit / words.size() >= coverage) return null;
        }
        return "\"" + clip(value) + "\" is not what the PDF passage says.";
    }

    private static String squeeze(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static String clip(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "…";
    }
}
