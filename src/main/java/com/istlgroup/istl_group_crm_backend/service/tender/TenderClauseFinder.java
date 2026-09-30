package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds a passage of the document from the AI's verbatim anchors — the first
 * and last few words it quotes — and slices it out exactly as printed.
 *
 * <p>Shared by every AI read (eligibility clauses, the documents checklist, BOQ
 * descriptions). It is the check that makes the AI's output traceable: a
 * passage it cannot be matched to is, for our purposes, not in the document.
 *
 * <p>The section is flattened, with a letters-and-digits-only shadow copy that
 * anchors are matched against. PDF extraction and the AI disagree about
 * punctuation, line breaks, hyphens and case; they do not disagree about the
 * words. The shadow keeps an index back into the original so the passage is
 * sliced out verbatim.
 */
public final class TenderClauseFinder {

    /** A located passage: verbatim text and the page it starts on. */
    public record Clause(String text, int page) {}

    /** A passage longer than this is a section, not a clause. */
    static final int MAX_CLAUSE_CHARS = 1500;
    /** When the closing anchor is missing, this much of the passage is kept. */
    static final int FALLBACK_CLAUSE_CHARS = 600;
    /** An opening anchor shorter than this matches too much to prove anything. */
    static final int MIN_ANCHOR_WORDS = 4;
    /** A partly paraphrased opening anchor must still match this share of its words. */
    static final double MIN_ANCHOR_SHARE = 0.75;
    /** Leading words of an anchor that may be the AI's own tidying rather than the document's. */
    static final int MAX_SKIPPED_LEAD_WORDS = 2;

    private final TenderText section;
    private final String flat;
    private final String norm;
    private final List<Integer> origin = new ArrayList<>();

    public TenderClauseFinder(TenderText section) {
        this.section = section;
        this.flat = section.flat();
        StringBuilder sb = new StringBuilder();
        boolean space = true;
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
                origin.add(i);
                space = false;
            } else if (!space) {
                sb.append(' ');
                origin.add(i);
                space = true;
            }
        }
        this.norm = sb.toString();
        int start = 0;
        for (int i = 0; i <= norm.length(); i++) {
            if (i == norm.length() || norm.charAt(i) == ' ') {
                if (i > start) { tokens.add(norm.substring(start, i)); tokenStart.add(start); }
                start = i + 1;
            }
        }
    }

    /** The shadow text as words, with where each starts in it — for gapped matching. */
    private final List<String> tokens = new ArrayList<>();
    private final List<Integer> tokenStart = new ArrayList<>();

    /** A gapped match: first and last matched word. */
    private record Span(int firstToken, int lastToken) {}

    /** Most other-column words tolerated between two consecutive anchor words. */
    static final int MAX_GAP = 10;
    /** A gapped match is weaker evidence than a contiguous one, so it must cover more of the anchor. */
    static final double MIN_GAPPED_SHARE = 0.8;

    /**
     * Share of the distinct words of {@code text} (3+ letters) that occur
     * anywhere in the section — for short AI-written text such as a BOQ
     * description, which is a tidy of the document's words, not a quote.
     */
    public double wordCoverage(String text) {
        List<String> ws = words(text).stream().filter(w -> w.length() >= 3).distinct().toList();
        if (ws.isEmpty()) return 0;
        String padded = " " + norm + " ";
        long hit = ws.stream().filter(w -> padded.contains(" " + w + " ")).count();
        return (double) hit / ws.size();
    }

    public Clause find(String start, String end) {
        List<String> words = words(start);
        if (words.size() < MIN_ANCHOR_WORDS) return null;
        int at = bestStart(words);
        if (at < 0) {
            // Multi-column tables (TREDA's CRITERIA | DOCUMENTS) interleave the
            // columns word by word in the text layer. The AI quotes the clean
            // criterion; its words are all there, in order, with the other
            // column's words between them.
            Span s = gapped(words, 0, tokens.size());
            if (s == null) return null;
            at = tokenStart.get(s.firstToken());
        }
        int from = origin.get(at);

        // The closing anchor is the first match after the opening one: OR
        // alternatives often share a tail ("…prior to the date of submission
        // of the bid"), and the nearest one closes this clause.
        int to = -1;
        List<String> endWords = words(end);
        int minEnd = (int) Math.max(3, Math.ceil(endWords.size() * MIN_ANCHOR_SHARE));
        for (int n = endWords.size(); n >= minEnd && to < 0; n--) {
            String needle = String.join(" ", endWords.subList(endWords.size() - n, endWords.size()));
            int e = norm.indexOf(needle, at);
            if (e >= 0) {
                int last = e + needle.length() - 1;
                int toFlat = origin.get(last) + 1;
                if (toFlat - from <= MAX_CLAUSE_CHARS) to = toFlat;
            }
        }
        if (to < 0 && endWords.size() >= MIN_ANCHOR_WORDS) {
            int fromToken = tokenAt(at);
            Span s = gapped(endWords, fromToken, Math.min(tokens.size(), fromToken + MAX_CLAUSE_CHARS / 4));
            if (s != null) {
                int last = tokenStart.get(s.lastToken()) + tokens.get(s.lastToken()).length() - 1;
                int toFlat = origin.get(last) + 1;
                if (toFlat > from && toFlat - from <= MAX_CLAUSE_CHARS) to = toFlat;
            }
        }
        if (to < 0) to = sentenceEnd(from);
        String text = flat.substring(from, to).replaceAll("\\s+", " ").strip();
        return new Clause(text, section.pageAtFlatOffset(from));
    }

    /**
     * The anchor's words, in order, each within {@link #MAX_GAP} words of the
     * last. Words the AI paraphrased may be missing, but the match must cover
     * {@link #MIN_GAPPED_SHARE} of the anchor and stay compact, so a scatter of
     * common words across a page never adds up to a match.
     */
    private Span gapped(List<String> words, int fromToken, int toToken) {
        Span best = null;
        double bestShare = 0;
        for (int skip = 0; skip <= MAX_SKIPPED_LEAD_WORDS && skip < words.size(); skip++) {
            String first = words.get(skip);
            for (int i = fromToken; i < toToken; i++) {
                if (!tokens.get(i).equals(first)) continue;
                int pos = i;
                int matched = 1;
                for (int w = skip + 1; w < words.size(); w++) {
                    int limit = Math.min(toToken - 1, pos + MAX_GAP);
                    for (int j = pos + 1; j <= limit; j++) {
                        if (tokens.get(j).equals(words.get(w))) { pos = j; matched++; break; }
                    }
                }
                double share = (double) matched / words.size();
                boolean compact = pos - i <= words.size() * 4;
                if (compact && share > bestShare) { bestShare = share; best = new Span(i, pos); }
            }
            if (bestShare == 1.0) break;
        }
        return bestShare >= MIN_GAPPED_SHARE ? best : null;
    }

    /** The word that starts at or after a position in the shadow text. */
    private int tokenAt(int normPos) {
        for (int t = 0; t < tokenStart.size(); t++) if (tokenStart.get(t) >= normPos) return t;
        return tokens.size();
    }

    /**
     * Where the opening anchor starts. OR-alternatives open with the same
     * words ("Bidder should have Satisfactorily Constructed, Completed &
     * Commissioned …"), so the first place a prefix matches is often a
     * sibling clause. Every occurrence of the first few words is a
     * candidate; the one that goes on to match the most of the anchor wins,
     * and it must match all of it or at least {@link #MIN_ANCHOR_SHARE}.
     * The AI occasionally paraphrases the tail of its quote, which is the
     * only reason a partial match is accepted at all. It also tidies the
     * opening ("The Bidder should…" for a clause that begins "Bidder
     * should…"), so up to {@link #MAX_SKIPPED_LEAD_WORDS} leading words may
     * go unmatched.
     */
    private int bestStart(List<String> anchor) {
        int best = -1;
        double bestShare = 0;
        for (int skip = 0; skip <= MAX_SKIPPED_LEAD_WORDS; skip++) {
            List<String> words = anchor.subList(skip, anchor.size());
            if (words.size() < MIN_ANCHOR_WORDS) break;
            String head = String.join(" ", words.subList(0, MIN_ANCHOR_WORDS));
            for (int at = norm.indexOf(head); at >= 0; at = norm.indexOf(head, at + 1)) {
                if (at > 0 && Character.isLetterOrDigit(norm.charAt(at - 1))) continue;   // mid-word
                int len = matchedWords(at, words);
                double share = (double) len / anchor.size();
                if (share > bestShare) { best = at; bestShare = share; }
            }
            if (bestShare == 1.0) break;
        }
        return bestShare >= MIN_ANCHOR_SHARE ? best : -1;
    }

    /** How many of {@code words} match consecutively from {@code at}. */
    private int matchedWords(int at, List<String> words) {
        int pos = at;
        int n = 0;
        for (String w : words) {
            if (!norm.startsWith(w, pos)) break;
            int after = pos + w.length();
            boolean boundary = after == norm.length() || norm.charAt(after) == ' ';
            if (!boundary) break;
            n++;
            pos = after + 1;
        }
        return n;
    }

    /** Without a closing anchor: the end of the sentence, within the fallback length. */
    private int sentenceEnd(int from) {
        int limit = Math.min(flat.length(), from + FALLBACK_CLAUSE_CHARS);
        int dot = flat.indexOf(". ", from + 20);
        return dot > 0 && dot < limit ? dot + 1 : limit;
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }
}
