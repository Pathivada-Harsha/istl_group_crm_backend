package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 1 — strip the furniture before anything tries to parse the document.
 *
 * <p>Two separate problems, one pass.
 *
 * <p><b>Running headers and footers.</b> A 105-page NIT repeats its title, its
 * agency letterhead, "SIGNATURE OF THE BIDDER WITH SEAL &amp; DATE" and
 * "Page n of 105" on every single page. That is ~12,000 characters of noise: it
 * crowds the LLM's budget, it drags the running title into the work
 * description, and it plants label-shaped phrases on pages that have nothing to
 * do with them. A line is furniture when the same text — digits blanked, so
 * page numbers collapse onto one signature — appears on at least half the
 * pages.
 *
 * <p>Short documents are left alone. A two-page IREPS tender legitimately
 * repeats its own header on both pages, and that header carries the tender
 * number: the one place it is stated as a label/value pair. Below
 * {@link #MIN_PAGES_FOR_REPEAT_STRIPPING} pages there is no budget problem to
 * solve, so nothing is dropped on a frequency rule.
 *
 * <p><b>Whitespace padding.</b> PDF text extraction pads columns with runs of
 * spaces; they are collapsed so a label and its value are separated by exactly
 * one space however the grid was laid out.
 */
public final class TenderTextCleaner {

    /** Below this, repeated-line removal is skipped entirely (see class doc). */
    public static final int MIN_PAGES_FOR_REPEAT_STRIPPING = 4;

    /** A line must repeat on at least this share of pages to count as furniture. */
    private static final double REPEAT_SHARE = 0.5;

    /** ...and on at least this many pages, so a 4-page doc needs more than two hits. */
    private static final int MIN_REPEAT_PAGES = 3;

    /** Always furniture, however often it happens to appear. */
    private static final List<Pattern> ALWAYS_DROP = List.of(
            Pattern.compile("^-?\\s*page\\s*[-:]?\\s*\\d+\\s*(?:of|/)\\s*\\d+\\s*[-.]?$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*page\\s*[-:]?\\s*\\d+\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\d{1,4}$"),
            Pattern.compile("^signature\\s+of\\s+(?:the\\s+)?(?:bidder|tenderer|contractor)\\b.*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^[\\s._=*\\-]+$"));

    private TenderTextCleaner() {}

    public static TenderText clean(TenderText doc) {
        Set<String> furniture = repeatedSignatures(doc);
        List<List<String>> out = new ArrayList<>();
        for (int p = 1; p <= doc.pageCount(); p++) {
            List<String> kept = new ArrayList<>();
            for (String raw : doc.page(p)) {
                String line = squeeze(raw);
                if (line.isEmpty()) continue;
                if (isAlwaysDropped(line)) continue;
                if (furniture.contains(signature(line))) continue;
                kept.add(line);
            }
            out.add(kept);
        }
        return TenderText.ofPages(out);
    }

    /** Collapse the padding runs PDF extraction inserts between columns. */
    public static String squeeze(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").strip();
    }

    static boolean isAlwaysDropped(String line) {
        for (Pattern p : ALWAYS_DROP) {
            if (p.matcher(line).matches()) return true;
        }
        return false;
    }

    /**
     * Signatures that recur on enough pages to be running furniture.
     * Package-private so the stage can be asserted on its own.
     */
    static Set<String> repeatedSignatures(TenderText doc) {
        Set<String> furniture = new HashSet<>();
        int pageCount = doc.pageCount();
        if (pageCount < MIN_PAGES_FOR_REPEAT_STRIPPING) return furniture;

        Map<String, Set<Integer>> seenOn = new HashMap<>();
        for (int p = 1; p <= pageCount; p++) {
            for (String raw : doc.page(p)) {
                String line = squeeze(raw);
                if (line.length() < 4) continue;
                seenOn.computeIfAbsent(signature(line), k -> new HashSet<>()).add(p);
            }
        }
        int threshold = Math.max(MIN_REPEAT_PAGES, (int) Math.ceil(pageCount * REPEAT_SHARE));
        for (Map.Entry<String, Set<Integer>> e : seenOn.entrySet()) {
            if (e.getValue().size() >= threshold) furniture.add(e.getKey());
        }
        return furniture;
    }

    /**
     * Digits blanked and case folded, so "Page 3 of 105" and "Page 47 of 105"
     * are one signature rather than 105 different lines.
     */
    static String signature(String line) {
        return squeeze(line).toLowerCase(Locale.ROOT).replaceAll("\\d+", "#");
    }
}
