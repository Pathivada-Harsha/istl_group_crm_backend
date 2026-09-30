package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find the bill of quantities, and read its row lines.
 *
 * <p>A schedule is recognised by its rows, not its title: the phrase "Bill of
 * Quantities" turns up in the contents, the conditions of contract and the
 * definitions, while an actual schedule is a run of lines that each end in a
 * quantity and a unit ("… ITEM2 58 Nos. 0 0.00 0.00 INR Zero Only"). So the
 * section is the densest run of such lines, preferably under a BOQ heading.
 *
 * <p>Many NITs do not carry the schedule at all — KPTCL's Section 9 reads "Refer
 * Techno Commercial Sheets & Financial bid uploaded in KPP Portal". That is
 * reported as such, so an empty BOQ reads as "published separately" rather
 * than "the parser failed", and nothing is invented to fill it.
 *
 * <p>Row lines are the ground truth for the <em>numbers</em>: item number,
 * quantity, unit. Descriptions wrap above and below their row in these layouts
 * and cannot be reassembled reliably from text alone; the AI writes those, and
 * its rows are only kept when they agree with a row line here.
 */
public final class TenderBoqLocator {

    /** One schedule line: the figures are exact, the description is best-effort. */
    public record Row(String itemNo, String quantity, String unit, String description, int page, String scope) {}

    /** Where the schedule is, what was read, and whether the PDF says it lives elsewhere. */
    public record Result(Integer fromPage, Integer toPage, List<Row> rows, String elsewhere) {
        public boolean found() { return fromPage != null; }
    }

    static final String UNITS =
            "Nos?|Numbers?|Each|Sets?|Lots?|Jobs?|Pairs?|Points?|Units?|Lump\\s*Sum|L\\.?S|"
          + "M\\.?T|Kgs?|Km|Kms|Mtrs?|Metres?|Meters?|RMT|R\\.?Mt|Sq\\.?\\s?m(?:tr|m)?|Sqm|"
          + "Cu\\.?\\s?m(?:tr)?|Cum|Ltrs?|Litres?|Tonnes?|Tons?|Bags?|Months?|Days?|Years?|"
          + "kWp|kW|MWp|MW|kVA|MVA";

    /**
     * Quantity then unit, followed by nothing, or by the rate columns (numbers,
     * "INR …"). Requiring that tail is what separates a row's quantity from a
     * rating written inside a description ("commissioning of 10 KWp Solar …").
     */
    private static final Pattern QTY_UNIT = Pattern.compile(
            "(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?)\\s+(" + UNITS + ")\\.?(?=\\s*$|\\s+[\\d.]|\\s+INR\\b)",
            Pattern.CASE_INSENSITIVE);

    /** The older "description unit quantity" order, as in "4 Comprehensive maintenance Month 60". */
    private static final Pattern UNIT_QTY = Pattern.compile(
            "\\s(" + UNITS + ")\\.?\\s+(\\d[\\d,]*(?:\\.\\d+)?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEADING_ITEM = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,2}){0,2})[.)]?\\s+");
    private static final Pattern ITEM_CODE = Pattern.compile("\\bITEM\\s?(\\d{1,3}(?:\\.\\d{1,2})?)\\.?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_TAIL = Pattern.compile("\\s+\\d[\\d,.]*(?:\\s+[\\d,.]+)*\\s*(?:INR\\b.*)?$");

    private static final Pattern HEADING = Pattern.compile(
            "bill\\s+of\\s+quantit|price\\s+schedule|schedule\\s+of\\s+(?:rates|quantities|prices)"
          + "|\\bBOQ\\b|item[\\s-]*wise\\s+(?:boq|financial\\s+bid)|price\\s+bid",
            Pattern.CASE_INSENSITIVE);

    /** "Refer … uploaded in KPP Portal", "to be filled in separate BOQ file", "in MS-Excel". */
    private static final Pattern ELSEWHERE = Pattern.compile(
            "(?:refer|uploaded|separate|available)\\b[^.]{0,80}?\\b(?:portal|excel|xls\\w*|file|website)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Lines that close a schedule. */
    private static final Pattern TOTAL = Pattern.compile("^(?:grand\\s+)?total\\b|quoted\\s+rate\\s+in\\s+words",
            Pattern.CASE_INSENSITIVE);

    /** A page needs this many row lines to be part of a schedule. */
    static final int MIN_ROWS_PER_PAGE = 2;
    /** A schedule this short is a table in the conditions, not a BOQ. */
    static final int MIN_ROWS = 3;
    static final int MAX_PAGES = 40;
    static final int MAX_ROWS = 300;

    private TenderBoqLocator() {}

    public static Result locate(TenderText doc) {
        int n = doc.pageCount();
        int[] rows = new int[n + 2];
        for (int p = 1; p <= n; p++) {
            if (TenderSectionLocator.isTocPage(doc.page(p))) continue;
            for (String line : doc.page(p)) if (rowOf(line, p) != null) rows[p]++;
        }

        // The densest run of row-bearing pages; a heading nearby breaks ties.
        int bestFrom = 0;
        int bestTo = 0;
        int bestCount = 0;
        for (int p = 1; p <= n; p++) {
            if (rows[p] < MIN_ROWS_PER_PAGE) continue;
            int to = p;
            int count = rows[p];
            while (to < n && to - p + 1 < MAX_PAGES && rows[to + 1] >= MIN_ROWS_PER_PAGE) {
                to++;
                count += rows[to];
            }
            boolean headed = hasHeading(doc, Math.max(1, p - 1), p);
            int score = count + (headed ? count : 0);
            int bestScore = bestCount + (hasHeading(doc, Math.max(1, bestFrom - 1), bestFrom) ? bestCount : 0);
            if (bestFrom == 0 || score > bestScore) { bestFrom = p; bestTo = to; bestCount = count; }
            p = to;
        }

        if (bestFrom == 0 || bestCount < MIN_ROWS) {
            return new Result(null, null, List.of(), elsewhere(doc));
        }
        return new Result(bestFrom, bestTo, readRows(doc, bestFrom, bestTo), null);
    }

    /** The schedule pages as one sub-document — what the AI is handed. */
    public static TenderText text(TenderText doc, Result r) {
        if (!r.found()) return TenderText.ofPages(List.of());
        List<List<String>> pages = new ArrayList<>();
        for (int p = 1; p <= doc.pageCount(); p++) {
            // One page of lead-in above the first row page carries the headings.
            boolean in = p >= Math.max(1, r.fromPage() - 1) && p <= r.toPage();
            pages.add(in ? doc.page(p) : List.of());
        }
        return TenderText.ofPages(pages);
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private static List<Row> readRows(TenderText doc, int from, int to) {
        List<Row> out = new ArrayList<>();
        List<String> pending = new ArrayList<>();            // description lines since the last row
        String scope = "";                                    // the "Part - A: Supply" the rows sit under
        for (int p = from; p <= to; p++) {
            for (String line : doc.page(p)) {
                if (TOTAL.matcher(line).find()) return out;
                Row r = rowOf(line, p);
                if (r == null) {
                    String part = partCaption(line);
                    if (part != null) { scope = part; pending.clear(); continue; }
                    if (!isColumnHeader(line)) pending.add(line.strip());
                    if (pending.size() > 6) pending.remove(0);
                    continue;
                }
                // The row's own text is the best description; failing that, the
                // lines just above it (layouts that centre a wrapped description
                // on its row put the first half there).
                String desc = r.description();
                if (desc.split("\\s+").length < 3 && !pending.isEmpty()) {
                    int take = Math.min(3, pending.size());
                    desc = (String.join(" ", pending.subList(pending.size() - take, pending.size())) + " " + desc).strip();
                }
                out.add(new Row(r.itemNo(), r.quantity(), r.unit(), TenderValues.clip(tidy(desc), 300), p, scope));
                pending.clear();
                if (out.size() >= MAX_ROWS) return out;
            }
        }
        return out;
    }

    /** A schedule line, or null. Needs an item number (leading, or an ITEM code) and a quantity+unit. */
    static Row rowOf(String raw, int page) {
        String line = raw.strip();
        Matcher lead = LEADING_ITEM.matcher(line);
        Matcher code = ITEM_CODE.matcher(line);
        boolean leading = lead.find();
        String itemNo = leading ? lead.group(1) : (code.find() ? code.group(1) : null);
        if (itemNo == null) return null;
        int afterItem = leading ? lead.end() : 0;

        // The last quantity+unit on the line is the row's; an earlier one is a
        // rating inside the description. The item number itself is never it.
        String qty = null;
        String unit = null;
        int cut = -1;
        Matcher qu = QTY_UNIT.matcher(line);
        while (qu.find()) {
            if (qu.start() < afterItem) continue;
            qty = qu.group(1);
            unit = qu.group(2);
            cut = qu.start();
        }
        if (qty == null) {
            Matcher uq = UNIT_QTY.matcher(line);
            if (!uq.find()) return null;
            unit = uq.group(1);
            qty = uq.group(2);
            cut = uq.start();
        }
        String body = line.substring(0, cut);
        body = LEADING_ITEM.matcher(body).replaceFirst("");
        body = ITEM_CODE.matcher(body).replaceAll(" ");
        body = RATE_TAIL.matcher(body).replaceFirst("");
        return new Row(itemNo, qty.replace(",", ""), normalizeUnit(unit), tidy(body), page, "");
    }

    private static final Pattern PART = Pattern.compile(
            "^(?:part|schedule)\\s*[-–—]?\\s*([A-Z]|\\d{1,2})\\b[:.\\-\\s]*(.{0,60})$", Pattern.CASE_INSENSITIVE);

    /** "Part - A: Supply of Materials" → "Supply of Materials"; a bare "Part - B" → "Part B". */
    static String partCaption(String line) {
        String l = line.strip();
        if (l.length() > 80) return null;
        Matcher m = PART.matcher(l);
        if (!m.find()) return null;
        String caption = m.group(2) == null ? "" : tidy(m.group(2));
        // "PART B (PRICE BID)" names the envelope, not a scope of work.
        if (ENVELOPE.matcher(caption).find()) return null;
        return caption.isEmpty() ? "Part " + m.group(1).toUpperCase(Locale.ROOT) : caption;
    }

    private static final Pattern ENVELOPE = Pattern.compile(
            "price\\s+bid|financial\\s+bid|techno|commercial|cover|envelope|packet|bid\\s+document",
            Pattern.CASE_INSENSITIVE);

    private static boolean isColumnHeader(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("item description") || l.contains("quantity") && l.contains("unit")
                || l.matches("^[\\d\\s#]+$") || l.contains("number # text")
                || l.matches("^(?:rs\\.?\\s*p\\s*)+$");
    }

    private static boolean hasHeading(TenderText doc, int from, int to) {
        for (int p = from; p <= to; p++) {
            for (String line : doc.page(p)) {
                if (line.length() <= 90 && HEADING.matcher(line).find()) return true;
            }
        }
        return false;
    }

    /**
     * When there is no schedule: does the BOQ section say where it is? The
     * sentence is returned for the review header.
     */
    private static String elsewhere(TenderText doc) {
        // The last such heading wins: the section itself sits after the
        // instructions that list it ("Section 9 : Bill Of Quantities" in a list
        // of sections, followed by an unrelated note about Section 11).
        String found = null;
        List<TenderText.Line> lines = doc.lines();
        for (int i = 0; i < lines.size(); i++) {
            TenderText.Line l = lines.get(i);
            if (l.text().length() > 90 || !HEADING.matcher(l.text()).find()) continue;
            if (TenderSectionLocator.isTocPage(doc.page(l.page()))) continue;
            StringBuilder next = new StringBuilder();
            for (int j = i + 1; j < Math.min(lines.size(), i + 3); j++) next.append(lines.get(j).text()).append(' ');
            if (ELSEWHERE.matcher(next).find()) {
                found = "p." + l.page() + ": \"" + tidy(next.toString()) + "\"";
            }
        }
        return found;
    }

    static String normalizeUnit(String u) {
        if (u == null) return null;
        String l = u.toLowerCase(Locale.ROOT).replace(".", "").replace(" ", "");
        if (l.startsWith("lump") || l.equals("ls")) return "Lump Sum";
        if (l.startsWith("no") || l.startsWith("number")) return "Nos";
        if (l.startsWith("met") || l.startsWith("mtr")) return "Meters";
        if (l.startsWith("month")) return "Month";
        if (l.startsWith("sq")) return "Sqm";
        if (l.startsWith("cu")) return "Cum";
        if (l.equals("mt")) return "MT";
        return u.strip();
    }

    private static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").replaceAll("^[\\s:;,\\-]+|[\\s:;,\\-]+$", "").strip();
        return t.replaceAll(":-$", "").strip();
    }
}
