package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a block of lines into label/value <em>records</em>, which is what every
 * field rule downstream reads.
 *
 * <p>Three things a naive "label, then rest of line" capture gets wrong, all of
 * them present in the reference documents:
 *
 * <ol>
 *   <li><b>Two pairs on one line.</b> Handled by {@link TenderLabels}: a value
 *       stops at the next recognised label.</li>
 *   <li><b>Values that wrap.</b> TREDA's name of work runs over six lines. Lines
 *       carrying no label at all belong to the record above them.</li>
 *   <li><b>Labels centred in a tall table cell.</b> IREPS prints
 *       <pre>
 *       Implementation of Rooftop Solar PV System At Various Locations of …
 *       Name of Work Southern Railway, with the Objective of Generating …
 *       Mode
 *       </pre>
 *       — the first line of the value comes out <em>above</em> its own label,
 *       because the label is vertically centred in the cell. Taking the line the
 *       label sits on returns a fragment starting mid-sentence. So an unlabelled
 *       line that directly follows a section heading is held back and prepended
 *       to the first label of the next line.</li>
 * </ol>
 *
 * <p>The orphan rule is deliberately narrow — it only fires straight after a
 * heading, for at most {@link #MAX_ORPHAN_LINES} lines. Anywhere else an
 * unlabelled line is a continuation of the record above, which is the far more
 * common case.
 */
public final class TenderRecords {

    /**
     * One label and everything that belongs to its value.
     *
     * @param labelKey   which field asked for this label ({@link TenderLabels} keys)
     * @param labelText  the label as printed, for the review modal
     * @param strength   how specific the matched label was; higher wins ties
     * @param value      the value as printed, wrapped lines already joined
     * @param page       1-based page the label was found on
     * @param sourceText the line the label came from, for provenance
     * @param atLineStart true when nothing but an item number preceded the label
     * @param inProse     true when the label sits inside a sentence rather than a grid
     */
    public record Record(String labelKey, String labelText, int strength, String value,
                         int page, String sourceText, boolean atLineStart, boolean inProse) {

        /** Label and value together — what the context rules are tested against. */
        public String full() {
            return (labelText + " " + value).trim();
        }
    }

    /** Section headings reset the stitcher: "1. NIT HEADER", "BID INFORMATION SHEET". */
    private static final Pattern HEADING = Pattern.compile(
            "^(?:\\d{1,2}[.)]\\s*)?[A-Z][A-Z0-9 &/()'.\\-]{4,60}$");

    /** Leading "1." / "12)" on a heading line. */
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,2}[.)]");

    /** A lower-case word ahead of a label — the mark of a sentence, not a grid. */
    private static final Pattern PROSE_BEFORE = Pattern.compile("\\b[a-z]+\\b");

    /** A line's leading item number / bullet, which is not part of any value. */
    private static final Pattern BULLET_PREFIX = Pattern.compile("^[\\s\\d.()\\-]*$");

    /** How many held-back lines may be prepended to a following label. */
    private static final int MAX_ORPHAN_LINES = 3;

    /** Beyond this a "value" has stopped being a value and is just prose. */
    private static final int MAX_VALUE_CHARS = 900;

    private TenderRecords() {}

    /** Build the records for one page range (1-based, inclusive). */
    public static List<Record> build(TenderText doc, int fromPage, int toPage) {
        List<Record> out = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        boolean orphansFollowHeading = false;
        StringBuilder continuation = null;              // value of the record being extended
        int continuationIndex = -1;

        for (TenderText.Line line : doc.lines()) {
            if (line.page() < fromPage || line.page() > toPage) continue;
            String text = line.text();

            List<TenderLabels.Hit> hits = TenderLabels.scan(text);

            // Labels win over headings: "TENDER FOR THE WORK OF" is shouted on
            // its own line and is still the label its six wrapped lines belong to.
            if (hits.isEmpty() && isHeading(text)) {
                flush(out, continuation, continuationIndex);
                continuation = null;
                continuationIndex = -1;
                orphans.clear();
                orphansFollowHeading = true;
                continue;
            }

            if (hits.isEmpty()) {
                if (continuation != null) {
                    if (continuation.length() + text.length() < MAX_VALUE_CHARS) {
                        continuation.append(' ').append(text);
                    }
                } else if (orphansFollowHeading && orphans.size() < MAX_ORPHAN_LINES) {
                    orphans.add(text);
                }
                continue;
            }

            flush(out, continuation, continuationIndex);
            continuation = null;
            continuationIndex = -1;

            for (int i = 0; i < hits.size(); i++) {
                TenderLabels.Hit hit = hits.get(i);
                if (TenderLabels.TERMINATOR.equals(hit.label().key())) continue;

                int end = TenderLabels.valueEnd(hits, i, text.length());
                String value = text.substring(Math.min(hit.valueStart(), end), end).strip();

                // Nothing but an item number ahead of it = a grid cell. Prose that
                // merely mentions a label ("As a Tender Inviting Authority, …")
                // never satisfies this, which is how the boilerplate is told apart.
                String before = text.substring(0, hit.start());
                boolean atLineStart = i == 0 && BULLET_PREFIX.matcher(before).matches();
                // A lower-case word ahead of the label means it was mentioned in
                // a sentence — "Certified that this DNIe-T contains 105 pages"
                // is not where the tender reference lives.
                boolean inProse = PROSE_BEFORE.matcher(before).find();

                // Held-back cell lines belong in front of the first label on the
                // line, but only when nothing else precedes it on that line — and
                // only for the work description. That is the one value long enough
                // to be laid out in a tall cell with its label centred; a tender
                // number or an authority takes what is on its own line, so a
                // letterhead sitting above them is never swept in.
                if (atLineStart && !orphans.isEmpty()
                        && TenderLabels.TENDER_NAME.equals(hit.label().key())) {
                    String prefix = String.join(" ", orphans).strip();
                    if (prefix.length() + value.length() < MAX_VALUE_CHARS) {
                        value = (prefix + " " + value).strip();
                    }
                }

                out.add(new Record(hit.label().key(), hit.label().display(),
                        hit.label().strength(), value, line.page(), clip(text),
                        atLineStart, inProse));

                // Only the last label on a line can wrap onto the next line.
                if (i == hits.size() - 1) {
                    continuation = new StringBuilder(value);
                    continuationIndex = out.size() - 1;
                }
            }
            orphans.clear();
            orphansFollowHeading = false;
        }
        flush(out, continuation, continuationIndex);
        return out;
    }

    /** Replace a record's value with the wrapped-line version built for it. */
    private static void flush(List<Record> out, StringBuilder continuation, int index) {
        if (continuation == null || index < 0 || index >= out.size()) return;
        Record r = out.get(index);
        String joined = continuation.toString().strip();
        if (joined.length() > r.value().length()) {
            out.set(index, new Record(r.labelKey(), r.labelText(), r.strength(),
                    joined, r.page(), r.sourceText(), r.atLineStart(), r.inProse()));
        }
    }

    /**
     * A section heading — "1. NIT HEADER", "BID INFORMATION SHEET".
     *
     * <p>Numbered-and-shouted, or shouted across at least three words. The word
     * count matters: without it an all-caps table cell that happens to wrap
     * ("EARNEST MONEY" on its own line) reads as a heading and resets the
     * stitcher in the middle of a record.
     */
    static boolean isHeading(String line) {
        String s = line.strip();
        if (s.length() < 5 || s.length() > 70) return false;
        if (!HEADING.matcher(s).matches()) return false;
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return false;
        boolean numbered = NUMBERED.matcher(s).find();
        return numbered || s.split("\\s+").length >= 3;
    }

    private static String clip(String s) {
        return s.length() <= 240 ? s : s.substring(0, 240).strip() + "…";
    }
}
