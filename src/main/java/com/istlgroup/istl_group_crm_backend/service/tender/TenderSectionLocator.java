package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find the pages that hold one kind of section — the bidder's qualifying
 * requirements ({@link #ELIGIBILITY}) or the documents to upload with the bid
 * ({@link #DOCUMENTS}). Eligibility is the worked example below.
 *
 * <p>Every issuer lays these out differently — KPTCL writes numbered prose with
 * centred "OR" lines, TREDA a two-column CRITERIA | DOCUMENTS table whose
 * columns interleave when extracted, IREPS an S.No / Description grid — so no
 * clause grammar survives contact with a new template. What does carry across
 * is the <em>language</em>: requirement pages are dense with "should have",
 * "at least", "not less than", "in the last N years", turnover, net worth,
 * similar works. That density is template-agnostic, so pages are scored on it.
 *
 * <p>A heading ("3.0 Qualification of the Tenderer", "SECTION - 3: ELIGIBILITY
 * CONDITION", "4. ELIGIBILITY CONDITIONS") anchors a section; the section then
 * runs forward over pages that keep scoring. Cross-references ("…meeting the
 * Qualifying Requirements as below"), the table of contents and the
 * "Qualification Information" proforma pages are the traps — each is rejected
 * by shape, not by wording.
 *
 * <p>This stage only decides <em>where</em> to look. Reading the clauses is the
 * AI's job, and its answers are checked against exactly the text found here.
 */
public final class TenderSectionLocator {

    /** A run of pages holding the section looked for. */
    public record Section(int fromPage, int toPage, String heading, int score) {}

    /**
     * What one kind of section reads like: the language that makes a page dense
     * with it, the headings that open it, and the lead-in sentences that
     * introduce it under a generic heading.
     */
    public record Profile(String name, Pattern signal, Pattern heading, Pattern leadIn, Pattern exclude) {}

    /** Requirement language. Each hit on a page adds one to its score. */
    private static final Pattern SIGNAL = Pattern.compile(
            "\\b(?:should|shall|must)\\s+have\\b|\\bat\\s?least\\b|\\bnot\\s+less\\s+than\\b"
          + "|\\bminimum\\b|\\bin\\s+the\\s+last\\b|\\bpreceding\\b|\\bturnover\\b|\\bnet\\s*worth\\b"
          + "|\\bliquid\\s+assets\\b|\\bcredit\\s+facilit|\\bsolvency\\b|\\bsimilar\\s+(?:nature|works?)\\b"
          + "|\\bsatisfactor(?:y|ily)\\b|\\bcommissioned\\b|\\bexperience\\b|\\bcumulative(?:ly)?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A section heading: optional numbering, then the eligibility vocabulary,
     * and nothing much after it. Anchored at line start, which is what separates
     * "3.0 Qualification of the Tenderer:" from a sentence that merely mentions
     * qualifying requirements.
     */
    private static final Pattern HEADING = Pattern.compile(
            "^(?:section\\s*[-:]?\\s*\\d+\\s*[:\\-]?\\s*|\\d{1,2}(?:\\.\\d{1,2}){0,2}\\.?\\s*"
          + "|\\(?[a-h]\\)\\s*|\\(?[ivx]{1,4}\\)\\s*)?"
          + "(?:(?:pre[-\\s]?)?qualif(?:ying|ication)\\s+(?:of\\s+the\\s+(?:tenderer|bidder)s?"
          + "|requirements?|criteria)"
          + "|(?:minimum\\s+)?eligibility(?:\\s+(?:criteria|conditions?|requirements?))?"
          + "|(?:technical|financial)\\s+(?:eligibility|qualif\\w*|criteria)"
          + "|eligible\\s+(?:tenderers?|bidders?)|pq\\s+criteria)"
          + "\\b[^.]{0,40}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A lead-in sentence that introduces a list of requirements — "The Bidder
     * should have the following Qualifying Requirements:". Issuers that head
     * the block with something generic ("2.4 General Requirements") still
     * announce it this way.
     */
    private static final Pattern LEAD_IN = Pattern.compile(
            "\\b(?:should|shall|must)\\s+(?:have|meet|possess)\\b.{0,50}"
          + "\\b(?:qualif\\w*|eligibility)\\b[^.:]{0,40}:$",
            Pattern.CASE_INSENSITIVE);

    /** The bidder's qualifying requirements. */
    public static final Profile ELIGIBILITY = new Profile("eligibility", SIGNAL, HEADING, LEAD_IN, null);

    /**
     * The documents a bidder must upload with the bid. Pages dense with
     * "upload", "copy of", "certificate", "self-declaration" — the checklist,
     * the "documents in support of…" list, the cover-1 contents.
     */
    public static final Profile DOCUMENTS = new Profile("documents",
            Pattern.compile(
                    "\\bupload(?:ed|ing)?\\b|\\bsubmit(?:ted)?\\b|\\bfurnish(?:ed)?\\b|\\bcop(?:y|ies)\\s+of\\b"
                  + "|\\bcertificates?\\b|\\bself[-\\s]?(?:declaration|attested)\\b|\\baffidavit\\b"
                  + "|\\bundertaking\\b|\\bdocumentary\\s+(?:proof|evidence)\\b|\\bscanned\\b"
                  + "|\\bregistration\\b|\\bdocuments?\\b|\\bduly\\s+(?:filled|signed|certified)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "^(?:section\\s*[-:]?\\s*\\d+\\s*[:\\-]?\\s*|\\d{1,2}(?:\\.\\d{1,2}){0,2}\\.?\\s*"
                  + "|\\(?[a-h]\\)\\s*|\\(?[ivx]{1,4}\\)\\s*)?"
                  + "(?:(?:list\\s+of\\s+)?documents?\\s+(?:to\\s+be\\s+)?(?:uploaded|submitted|furnished|required|in\\s+support)"
                  + "|(?:document|bid|submission)\\s+check\\s*-?\\s*list|check\\s*-?\\s*list\\s+of\\s+documents"
                  + "|contents\\s+of\\s+(?:the\\s+)?(?:technical\\s+|first\\s+|cover[-\\s]?1\\s+)?bid"
                  + "|(?:technical|cover[-\\s]?1|first\\s+cover|envelope[-\\s]?1)\\s+(?:bid\\s+)?documents)"
                  + "\\b[^.]{0,60}$",
                    Pattern.CASE_INSENSITIVE),
            // The lead-in must be about the BID: "Bids shall include the
            // following", "The bidder shall upload the following documents".
            Pattern.compile(
                    "\\b(?:bids?|tenders?|bidders?|tenderers?|offers?)\\b.{0,60}"
                  + "\\b(?:shall|should|must|are\\s+to|is\\s+to|required\\s+to)\\b.{0,40}"
                  + "\\b(?:upload|submit|furnish|include|enclose)\\w*\\b.{0,60}\\bfollowing\\b[^.]{0,50}:?$",
                    Pattern.CASE_INSENSITIVE),
            // …and not about what the contractor hands over after award.
            Pattern.compile("\\bsuccessful\\b|\\bcontractor\\b|\\bafter\\s+(?:the\\s+)?(?:award|completion|commissioning)\\b"
                  + "|\\bcontract\\s+documents\\b", Pattern.CASE_INSENSITIVE));

    /** A table-of-contents row: text followed by a bare page number. */
    private static final Pattern TOC_ROW = Pattern.compile("^.{3,90}?(?:\\.{3,}|\\s)\\s*\\d{1,3}$");

    /** Proformas the bidder fills in — they name the topic but state no requirement. */
    private static final Pattern FORM_HEADING = Pattern.compile(
            "qualification\\s+information|format|proforma|annexure|undertaking", Pattern.CASE_INSENSITIVE);

    /** A page scoring below this does not extend a section. */
    static final int CONTINUE_MIN = 4;
    /** Without any heading, a page this dense is still taken as a section. */
    static final int HEADLESS_MIN = 12;
    /** One section never runs longer than this. */
    static final int MAX_SECTION_PAGES = 8;
    /** Pages handed on in total, across all sections. */
    static final int MAX_TOTAL_PAGES = 12;
    /**
     * A headed section scoring below this is a passing mention, not a list of
     * requirements. Absolute rather than relative to the best section: a short
     * "general requirements" page is worth keeping next to a dense technical one.
     */
    static final int KEEP_MIN = 6;

    private TenderSectionLocator() {}

    /** Eligibility sections in page order, or empty when the document states none. */
    public static List<Section> locate(TenderText doc) {
        return locate(doc, ELIGIBILITY);
    }

    /** Sections of the given kind in page order, or empty when the document has none. */
    public static List<Section> locate(TenderText doc, Profile profile) {
        int n = doc.pageCount();
        if (n == 0) return List.of();

        int[] score = new int[n + 1];
        boolean[] toc = new boolean[n + 1];
        for (int p = 1; p <= n; p++) {
            toc[p] = isTocPage(doc.page(p));
            score[p] = toc[p] ? 0 : score(doc.page(p), profile.signal());
        }

        List<Section> found = new ArrayList<>();
        for (int p = 1; p <= n; p++) {
            if (toc[p]) continue;
            String heading = heading(doc.page(p), profile);
            if (heading == null) continue;
            // The heading's own page must read like requirements, or it is a
            // divider page / a cross-reference that happened to fit the shape.
            if (score[p] < CONTINUE_MIN && (p == n || score[p + 1] < CONTINUE_MIN)) continue;
            int to = p;
            while (to < n && to - p + 1 < MAX_SECTION_PAGES && score[to + 1] >= CONTINUE_MIN) to++;
            found.add(new Section(p, to, heading, sum(score, p, to)));
        }

        // A template with no recognisable heading still gets its densest page,
        // widened over neighbours that also read as requirements.
        if (found.isEmpty()) {
            int best = 0;
            for (int p = 1; p <= n; p++) if (score[p] > score[best]) best = p;
            if (best == 0 || score[best] < HEADLESS_MIN) return List.of();
            int from = best;
            int to = best;
            while (from > 1 && best - from + 1 < MAX_SECTION_PAGES / 2 && score[from - 1] >= CONTINUE_MIN) from--;
            while (to < n && to - from + 1 < MAX_SECTION_PAGES && score[to + 1] >= CONTINUE_MIN) to++;
            found.add(new Section(from, to, null, sum(score, from, to)));
        }
        return select(merge(found, score));
    }

    /** The located pages as one sub-document — what the AI is handed. */
    public static TenderText text(TenderText doc, List<Section> sections) {
        List<List<String>> pages = new ArrayList<>();
        for (int p = 1; p <= doc.pageCount(); p++) {
            boolean in = false;
            for (Section s : sections) if (p >= s.fromPage() && p <= s.toPage()) in = true;
            pages.add(in ? doc.page(p) : List.of());
        }
        return TenderText.ofPages(pages);
    }

    // ── scoring ──────────────────────────────────────────────────────────────

    static int score(List<String> page, Pattern signal) {
        int s = 0;
        for (String line : page) {
            Matcher m = signal.matcher(line);
            while (m.find()) s++;
        }
        return s;
    }

    /** The first line on the page that reads as a heading of the profile's kind. */
    static String heading(List<String> page, Profile profile) {
        for (String line : page) {
            String l = line.strip();
            if (l.length() > MAX_LEAD_IN || TOC_ROW.matcher(l).matches()) continue;
            if (FORM_HEADING.matcher(l).find()) continue;
            if (profile.exclude() != null && profile.exclude().matcher(l).find()) continue;
            // A heading is short; a lead-in is a sentence and may run the width
            // of the page ("…effectively. Bids shall include the following").
            if (l.length() <= MAX_HEADING && profile.heading().matcher(l).find()) return l;
            if (profile.leadIn().matcher(l).find()) return l;
        }
        return null;
    }

    private static final int MAX_HEADING = 90;
    private static final int MAX_LEAD_IN = 140;

    /** Contents pages list many headings, each trailed by a page number. */
    static boolean isTocPage(List<String> page) {
        int rows = 0;
        for (String line : page) if (TOC_ROW.matcher(line.strip()).matches()) rows++;
        return rows >= 6 && rows * 3 >= page.size();
    }

    private static int sum(int[] score, int from, int to) {
        int s = 0;
        for (int p = from; p <= to; p++) s += score[p];
        return s;
    }

    // ── assembling the result ────────────────────────────────────────────────

    /** Overlapping or touching sections become one, keeping the first heading. */
    private static List<Section> merge(List<Section> in, int[] score) {
        List<Section> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingInt(Section::fromPage));
        List<Section> out = new ArrayList<>();
        for (Section s : sorted) {
            if (!out.isEmpty() && s.fromPage() <= out.get(out.size() - 1).toPage() + 1) {
                Section last = out.remove(out.size() - 1);
                int to = Math.max(last.toPage(), s.toPage());
                out.add(new Section(last.fromPage(), to, last.heading(), sum(score, last.fromPage(), to)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Strongest sections first until the page budget is spent, then back into
     * page order. A weak straggler (a lone heading in the conditions of
     * contract) is dropped rather than diluting what the AI reads.
     */
    private static List<Section> select(List<Section> merged) {
        if (merged.isEmpty()) return merged;
        List<Section> ranked = new ArrayList<>(merged);
        ranked.sort(Comparator.comparingInt(Section::score).reversed());
        List<Section> out = new ArrayList<>();
        int pages = 0;
        for (Section s : ranked) {
            if (s.score() < KEEP_MIN) break;
            int len = s.toPage() - s.fromPage() + 1;
            if (pages + len > MAX_TOTAL_PAGES) {
                if (out.isEmpty()) {
                    out.add(new Section(s.fromPage(), s.fromPage() + MAX_TOTAL_PAGES - 1, s.heading(), s.score()));
                }
                break;
            }
            out.add(s);
            pages += len;
        }
        out.sort(Comparator.comparingInt(Section::fromPage));
        return out;
    }
}
