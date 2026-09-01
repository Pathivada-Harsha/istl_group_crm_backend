package com.istlgroup.istl_group_crm_backend.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Company-name normalisation and similarity, used by the sanction-letter
 * import flow to find an existing borrower before offering to create a new
 * one. No third-party fuzzy-matching library is in the project, so this is a
 * small hand-rolled bigram Dice coefficient — good enough to rank candidates
 * for a human to confirm, which is all it is ever used for (see
 * {@link BorrowerService#matchBorrower}: nothing here is allowed to
 * auto-attach a sanction on its own).
 */
final class CompanyNameMatcher {

    private CompanyNameMatcher() { }

    /**
     * Strips a trailing boilerplate incorporation clause some letters
     * append after the actual company name — e.g. ", a Special Purpose
     * Vehicle incorporated under the Companies Act, 2013". Left in, this
     * dominates the bigram similarity below: nearly every SPV in this
     * registry's dataset ends with some variant of it, so two completely
     * unrelated companies that both happen to be SPVs incorporated under
     * the same Act score as "very similar" purely from that shared
     * ~60-character tail — e.g. "Vayugrid Erode..." vs "Kaveri Kurnool..."
     * scored 0.857 (well past the 0.55 fuzzy floor) with the clause left
     * in, and 0.34 with it stripped, even though the names share almost
     * nothing distinctive. Anchored to the end of the string and requires
     * the specific "incorporated under the Companies Act, &lt;year&gt;"
     * phrase, so it only ever strips this exact kind of clause, never a
     * company's own distinguishing name text.
     */
    private static final Pattern INCORPORATION_CLAUSE =
            Pattern.compile(",?\\s*an?\\s+.+?incorporated\\s+under\\s+the\\s+companies\\s+act,?\\s*\\d{4}\\s*$");

    /**
     * Lower-cases, strips punctuation, and canonicalises the handful of legal
     * suffixes that show up written differently letter to letter (e.g.
     * "Pvt. Ltd." vs "Private Limited") so those compare equal, without
     * stripping the suffix outright — "ABC" and "ABC Pvt Ltd" must still be
     * allowed to be different companies.
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ENGLISH).trim();
        s = INCORPORATION_CLAUSE.matcher(s).replaceAll("").trim();
        s = s.replace("&", " and ");
        s = s.replaceAll("[.,()]", " ");
        s = s.replaceAll("-", " ");
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("\\bprivate limited\\b", "pvt ltd");
        s = s.replaceAll("\\bpvt limited\\b", "pvt ltd");
        s = s.replaceAll("\\bprivate ltd\\b", "pvt ltd");
        s = s.replaceAll("\\blimited\\b", "ltd");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** Exact match after normalisation — Priority 2 in the matching rules. */
    static boolean normalizedEquals(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return !na.isEmpty() && na.equals(nb);
    }

    /**
     * Bigram Dice coefficient in [0,1]. 1.0 is identical, 0.0 shares no
     * character pairs. Used only to rank/flag "possibly the same company" —
     * never to decide anything on its own.
     */
    static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;
        Set<String> bigramsA = bigrams(na);
        Set<String> bigramsB = bigrams(nb);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        return (2.0 * intersection.size()) / (bigramsA.size() + bigramsB.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        String packed = s.replace(" ", "");
        for (int i = 0; i < packed.length() - 1; i++) {
            out.add(packed.substring(i, i + 2));
        }
        return out;
    }
}
