package com.istlgroup.istl_group_crm_backend.service.tender;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A tender document as <em>pages of lines</em>, which is the shape every later
 * stage needs.
 *
 * <p>The old extractor flattened the whole PDF to one string before parsing.
 * That threw away the two things this work depends on: the page a value came
 * from (there is no usable review modal without it) and the line structure that
 * tells a two-column summary grid apart from running prose.
 *
 * <p>Text is pulled in a single PDFBox pass with a form-feed page separator
 * rather than one {@code getText} call per page — a 105-page NIT is common and
 * per-page stripping is markedly slower for identical output.
 */
public final class TenderText {

    /** One line of the document, carrying the page it was printed on. */
    public record Line(int page, String text) {}

    private static final String PAGE_BREAK = "\f";

    private final List<List<String>> pages;

    private TenderText(List<List<String>> pages) {
        this.pages = pages;
    }

    // ── construction ─────────────────────────────────────────────────────────

    public static TenderText fromPdf(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setPageEnd(PAGE_BREAK);
            String text = stripper.getText(doc);
            return fromPagedText(text == null ? "" : text);
        }
    }

    /** Split a form-feed separated dump into pages. */
    public static TenderText fromPagedText(String pagedText) {
        List<List<String>> out = new ArrayList<>();
        for (String page : normalize(pagedText).split(PAGE_BREAK, -1)) {
            out.add(splitLines(page));
        }
        // A trailing separator leaves an empty final page; drop it.
        if (out.size() > 1 && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return new TenderText(out);
    }

    /** Treat a plain string as a one-page document (used by the unit tests). */
    public static TenderText fromPlainText(String text) {
        return new TenderText(List.of(splitLines(normalize(text == null ? "" : text))));
    }

    public static TenderText ofPages(List<List<String>> pages) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> p : pages) copy.add(List.copyOf(p));
        return new TenderText(copy);
    }

    private static List<String> splitLines(String page) {
        List<String> lines = new ArrayList<>();
        for (String raw : page.split("\r?\n")) {
            String s = raw.strip();
            if (!s.isEmpty()) lines.add(s);
        }
        return lines;
    }

    // ── access ───────────────────────────────────────────────────────────────

    public int pageCount() { return pages.size(); }

    public boolean isEmpty() { return lines().isEmpty(); }

    /** Lines of one 1-based page, or empty when the page does not exist. */
    public List<String> page(int oneBased) {
        if (oneBased < 1 || oneBased > pages.size()) return List.of();
        return Collections.unmodifiableList(pages.get(oneBased - 1));
    }

    /** Every line in document order, each tagged with its 1-based page. */
    public List<Line> lines() {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            for (String s : pages.get(i)) out.add(new Line(i + 1, s));
        }
        return out;
    }

    /** A sub-document covering pages {@code from}..{@code to} (1-based, inclusive). */
    public TenderText slice(int from, int to) {
        List<List<String>> out = new ArrayList<>();
        for (int p = 1; p <= pages.size(); p++) {
            out.add(p >= from && p <= to ? pages.get(p - 1) : List.of());
        }
        return new TenderText(out);
    }

    /** Whitespace-collapsed single-string view, for whole-document scans. */
    public String flat() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(l.text());
        }
        return sb.toString();
    }

    /**
     * The page an offset into {@link #flat()} falls on. Scans that work over the
     * flattened document (a clause that wraps across lines, say) still have to
     * report where they found what they found.
     */
    public int pageAtFlatOffset(int offset) {
        int at = 0;
        int lastPage = 1;
        for (Line l : lines()) {
            int end = at + l.text().length();
            if (offset <= end) return l.page();
            at = end + 1;                                  // the joining space
            lastPage = l.page();
        }
        return lastPage;
    }

    /** Line-structured view, one line per row — what the LLM is handed. */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines()) sb.append(l.text()).append('\n');
        return sb.toString();
    }

    public int charCount() {
        int n = 0;
        for (Line l : lines()) n += l.text().length() + 1;
        return n;
    }

    // ── typographic normalisation ────────────────────────────────────────────

    /**
     * Fold the characters PDF extraction leaves behind — non-breaking and narrow
     * spaces, soft hyphens, en/em dashes, curly quotes, fi/fl ligatures. Without
     * this a label that reads "Tender No." on screen can carry a NBSP that
     * {@code \s} never matches, and every regex downstream silently misses.
     */
    public static String normalize(String t) {
        if (t == null) return "";
        return t.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace("­", "").replace("​", "")
                .replace("ﬁ", "fi").replace("ﬂ", "fl")
                .replaceAll("[‐-―−]", "-")
                .replaceAll("[‘’‛]", "'")
                .replaceAll("[“”]", "\"");
    }
}
