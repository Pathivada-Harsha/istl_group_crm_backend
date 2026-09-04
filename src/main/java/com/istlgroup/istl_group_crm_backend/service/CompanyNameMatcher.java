package com.istlgroup.istl_group_crm_backend.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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
     * IDF-style weight for every bigram appearing across a corpus of company
     * names — log((N+1)/(df+1)), df being how many of those names contain
     * the bigram at least once. Built once per match request from every
     * live borrower's name (see {@code BorrowerService#matchBorrower}) and
     * reused for every candidate compared in that request.
     *
     * <p>This is what {@link #similarity(String, String, Map)} uses to stop
     * shared boilerplate from driving the score: a plain (unweighted)
     * bigram comparison scores two completely unrelated companies as "very
     * similar" whenever they share enough of it — the same project type
     * ("wind solar hybrid power"), the same generic legal suffix, or even
     * just the same project location ("anantapur") — e.g. "Vayugrid
     * Anantapur..." vs "Kaveri Anantapur..." shares every bigram except the
     * sponsor name itself, the one part that actually identifies the
     * company. A bigram that shows up in many corpus names (because it's
     * boilerplate everyone shares, or a location several different
     * sponsors happen to build at) gets a low weight here; a bigram unique
     * to a sponsor's own name — appearing in only that sponsor's
     * companies — gets a high one. No hand-maintained list of "generic"
     * words is needed: the weighting adapts to whatever this lender's own
     * portfolio actually contains.
     */
    static Map<String, Double> buildBigramIdf(Collection<String> companyNames) {
        Map<String, Integer> df = new HashMap<>();
        int n = 0;
        for (String name : companyNames) {
            String norm = normalize(name);
            if (norm.isEmpty()) continue;
            n++;
            for (String bg : bigrams(norm)) {
                df.merge(bg, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            // +1 smoothing on both sides: a bigram present in literally
            // every corpus name still gets a small positive weight rather
            // than exactly zero, which would otherwise make it contribute
            // nothing at all even to a name's own weight sum, not just to
            // the overlap with another name.
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 0.1);
        }
        return idf;
    }

    private static double weightSum(Set<String> bigramSet, Map<String, Double> bigramIdf) {
        double sum = 0;
        for (String bg : bigramSet) sum += bigramIdf.getOrDefault(bg, 1.0);
        return sum;
    }

    /**
     * IDF-weighted bigram Dice coefficient in [0,1] — same Dice ratio as a
     * plain bigram comparison, except each shared/total bigram counts for
     * its {@code bigramIdf} weight instead of a flat 1, so shared
     * boilerplate/location contributes far less than a shared, distinctive
     * sponsor name. 1.0 is identical after normalisation, 0.0 shares
     * nothing (or nothing weighted). Used only to rank/flag "possibly the
     * same company" — never to decide anything on its own. Build {@code
     * bigramIdf} once per match request via {@link #buildBigramIdf} and
     * reuse it for every candidate in that request.
     */
    static double similarity(String a, String b, Map<String, Double> bigramIdf) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;
        Set<String> bigramsA = bigrams(na);
        Set<String> bigramsB = bigrams(nb);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0;
        double weightA = weightSum(bigramsA, bigramIdf);
        double weightB = weightSum(bigramsB, bigramIdf);
        if (weightA <= 0 || weightB <= 0) return 0.0;
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        double weightShared = weightSum(intersection, bigramIdf);
        double fullNameScore = (2.0 * weightShared) / (weightA + weightB);

        // Corpus-IDF weighting alone isn't reliably strong enough: with a
        // portfolio of realistic (i.e. not huge) size, a project location
        // shared by only two or three companies still carries enough raw
        // weight — it has almost as many bigrams contributing to the shared
        // total as a genuinely-unique sponsor name would — to keep an
        // unrelated company's score past the fuzzy floor (empirically
        // verified in CompanyNameMatcherTest: "Vayugrid Anantapur..." vs
        // "Kaveri Anantapur..." still scored 0.76 on IDF weighting alone).
        // Every name in this registry conventionally leads with the
        // sponsor/entity name before the location and boilerplate — "Vayugrid
        // Anantapur..." / "Kaveri Anantapur..." — so gating the whole score
        // by how similar just the LEADING token is directly targets the one
        // part of the name that actually carries the company's identity,
        // without hand-listing which words are "generic". Two SPVs from the
        // same sponsor (e.g. "Kaveri Kurnool..." vs "Kaveri Anantapur...")
        // keep their full score, since their leading token matches exactly —
        // this is deliberately still allowed to surface as a fuzzy
        // suggestion, the same as before this fix, since CompanyMatchModal's
        // group-suggestion feature is what that's for.
        double leadingTokenScore = similarityUnweighted(leadingToken(na), leadingToken(nb));
        return fullNameScore * leadingTokenScore;
    }

    private static String leadingToken(String normalized) {
        int space = normalized.indexOf(' ');
        return space < 0 ? normalized : normalized.substring(0, space);
    }

    /** Plain (unweighted) bigram Dice — corpus-IDF weighting isn't meaningful over a single short token. */
    private static double similarityUnweighted(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
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
