package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the column-tagged {@link ExtractedRun}s from
 * {@link InfrastructureMasterListPdfExtractor} into an ordered
 * Category → [Sub-category] structure. Pure, deterministic, no PDF/network
 * I/O — takes the extractor's output as plain data so it is unit-testable
 * against captured fixtures without re-parsing a PDF each time.
 *
 * <p>Parsing rules, derived from inspecting the real 19-Sep-2025 and
 * 11-Oct-2022 Gazette notifications (Annexure-I, a 3-column
 * Sr No / Category / Infrastructure sub-sectors table; the 2022 notice
 * numbers rows "1." while the 2025 one uses a bare "1", so both a
 * standalone and a merged "1. Transport and Logistics"-style run are
 * treated as the Sr No marker):
 * <ul>
 *   <li>The table itself starts after the <em>last</em> mention of
 *       "Annexure" in the document — the notification paragraph that
 *       precedes the real Annexure-I heading mentions "Annexure" and
 *       "sub-sectors" (the column header's own wording) several times
 *       while describing what changed, so anchoring on the first mention
 *       of either would parse that prose as table rows.</li>
 *   <li>Every sub-sector line starts with a bullet glyph; a run whose first
 *       non-whitespace character is not a letter or digit always starts a
 *       new sub-sector item.</li>
 *   <li>The Sr No column only ever carries a value on a category's first
 *       row, so it is used purely as a "the very next Category-column run
 *       starts a brand-new category, not a continuation of the currently
 *       open one" marker — a category name that wraps to a second line
 *       (e.g. "Social and Commercial" / "Infrastructure") has no Sr No
 *       before its second line, and one or more bullet items can
 *       legitimately appear between the two lines of that wrapped name, so
 *       "the previous run was also Category" is not by itself a safe
 *       continuation signal.</li>
 *   <li>A run with no bullet and not an Sr No boundary is either a wrapped
 *       continuation of the previous bullet item's name, or a continuation
 *       of the open category's name — disambiguated by which of the two
 *       x-position clusters found among the table's runs it falls in, not
 *       by line order alone (line order mis-attributes the row at the page
 *       break between the 2nd and 3rd category, per the extractor's
 *       Javadoc).</li>
 *   <li>When joining a wrapped continuation onto an accumulated name, a
 *       short (&le;4-letter) all-lowercase fragment right after a lowercase
 *       letter is treated as the tail of a word split mid-word by an
 *       unusually wide inter-glyph gap (seen in "Logistics" in the real
 *       2025 notice, extracted as "Logis" + "tics") and joined with no
 *       space; anything longer is a genuine wrapped word and gets a
 *       separating space.</li>
 *   <li>Footnote reference digits are appended directly to a name with no
 *       space (e.g. {@code Ports1}, {@code Cold Chain12}) and are stripped.</li>
 * </ul>
 */
@Component
public class InfrastructureMasterListParser {

    private static final Pattern SUB_SECTORS_HEADER = Pattern.compile("(?i)sub[-\\s]?sectors?");
    private static final Pattern ANNEXURE_MARKER = Pattern.compile("(?i)annexure");
    private static final Pattern FOOTNOTE_LINE = Pattern.compile("^(?:[1-9]|1[0-5])\\s+\\S.*");
    private static final Pattern BARE_SR_NO = Pattern.compile("^\\d{1,2}\\.?$");
    private static final Pattern LEADING_SR_NO_PREFIX = Pattern.compile("^(\\d{1,2})\\.?\\s+(\\S.*)$");
    private static final Pattern TRAILING_FOOTNOTE_DIGITS = Pattern.compile("(?<=[\\p{L}\\)])\\d{1,2}$");
    private static final Pattern LEADING_BULLET = Pattern.compile("^[^\\p{L}\\d]+\\s*");

    private static final List<String> HEADER_NOISE = List.of(
            "category", "sr no", "sr", "no", "s.no", "s.no.",
            "infrastructure sub-sectors", "subject");

    public List<ParsedCategory> parse(List<ExtractedRun> runs) {
        List<ExtractedRun> body = extractTableBody(runs);
        if (body.isEmpty()) return List.of();

        // Pass 1: classify + strip a merged "1. " / "1 " Sr No prefix that
        // landed in the same run as the category name (common when the gap
        // between the two is smaller than the extractor's column threshold).
        String[] texts = new String[body.size()];
        boolean[] isBullet = new boolean[body.size()];
        boolean[] isSrNoBoundary = new boolean[body.size()];
        for (int i = 0; i < body.size(); i++) {
            String t = body.get(i).text().trim();
            if (BARE_SR_NO.matcher(t).matches()) {
                texts[i] = "";
                isSrNoBoundary[i] = true;
                continue;
            }
            Matcher prefixMatch = LEADING_SR_NO_PREFIX.matcher(t);
            if (!isBulletStart(t) && prefixMatch.matches()) {
                texts[i] = prefixMatch.group(2);
                isSrNoBoundary[i] = true;
                continue;
            }
            texts[i] = t;
            isBullet[i] = isBulletStart(t);
        }
        float columnSplitX = computeColumnSplitX(body, isBullet, isSrNoBoundary);

        List<CategoryBuilder> categories = new ArrayList<>();
        CategoryBuilder currentCategory = null;
        ItemBuilder currentItem = null;
        boolean nextCategoryIsNew = false;

        for (int i = 0; i < body.size(); i++) {
            String text = texts[i];
            boolean srNoBoundary = isSrNoBoundary[i];
            if (srNoBoundary) nextCategoryIsNew = true;
            if (text.isEmpty()) continue;

            if (isBullet[i]) {
                if (currentItem != null && currentCategory != null) {
                    currentCategory.addItem(currentItem.build());
                }
                String cleaned = LEADING_BULLET.matcher(text).replaceFirst("");
                currentItem = new ItemBuilder(cleaned);
                continue;
            }

            boolean isCategoryColumn = body.get(i).minX() <= columnSplitX;
            if (isCategoryColumn) {
                if (currentCategory != null && !nextCategoryIsNew) {
                    currentCategory.appendNameContinuation(text);
                } else {
                    if (currentItem != null && currentCategory != null) {
                        currentCategory.addItem(currentItem.build());
                        currentItem = null;
                    }
                    if (currentCategory != null) categories.add(currentCategory);
                    currentCategory = new CategoryBuilder(text, categories.size() + 1);
                }
                nextCategoryIsNew = false;
            } else {
                // Sub-sector column, no leading bullet: a wrapped continuation
                // of the item currently being built.
                if (currentItem != null) currentItem.appendContinuation(text);
            }
        }
        if (currentItem != null && currentCategory != null) currentCategory.addItem(currentItem.build());
        if (currentCategory != null) categories.add(currentCategory);

        List<ParsedCategory> result = new ArrayList<>();
        for (CategoryBuilder cb : categories) {
            ParsedCategory pc = cb.build();
            if (!pc.name().isEmpty() && !pc.subCategories().isEmpty()) result.add(pc);
        }
        return result;
    }

    // ── table boundary detection ────────────────────────────────────────

    private List<ExtractedRun> extractTableBody(List<ExtractedRun> runs) {
        int annexureIndex = -1;
        for (int i = 0; i < runs.size(); i++) {
            if (ANNEXURE_MARKER.matcher(runs.get(i).text()).find()) {
                annexureIndex = i; // keep scanning — take the LAST match
            }
        }
        int searchFrom = annexureIndex >= 0 ? annexureIndex : 0;

        List<Integer> headerMatches = new ArrayList<>();
        for (int i = searchFrom; i < runs.size(); i++) {
            if (SUB_SECTORS_HEADER.matcher(runs.get(i).text()).find()) {
                headerMatches.add(i);
                if (headerMatches.size() >= 2) break;
            }
        }
        if (headerMatches.isEmpty()) return List.of();
        // The doc title ("...Infrastructure Sub-sectors") appears once right
        // after Annexure-I, followed by the actual column header — prefer the
        // second match (the real header) when both are present so the title
        // line itself never gets parsed as table content.
        int start = headerMatches.get(headerMatches.size() >= 2 ? 1 : 0) + 1;
        if (start >= runs.size()) return List.of();

        int end = runs.size();
        for (int i = start; i < runs.size(); i++) {
            String t = runs.get(i).text().trim();
            String lower = t.toLowerCase();
            if (lower.contains("uploaded by") || FOOTNOTE_LINE.matcher(t).matches()) {
                end = i;
                break;
            }
        }

        List<ExtractedRun> body = new ArrayList<>();
        for (int i = start; i < end; i++) {
            ExtractedRun r = runs.get(i);
            String t = r.text().trim();
            if (t.isEmpty()) continue;
            if (HEADER_NOISE.contains(t.toLowerCase())) continue;
            if (isRunningHeaderOrFooterNoise(t)) continue;
            body.add(r);
        }
        return body;
    }

    /**
     * The Gazette repeats a running page header/footer ("THE GAZETTE OF
     * INDIA : EXTRAORDINARY", "[PART I--SEC.1]", the page number) at every
     * page break, which can fall inside the table's page range. Left in,
     * a bracketed header like "[PART I--SEC.1]" reads as a bullet-prefixed
     * run at a page-margin x far from the real sub-sector column and skews
     * the column clustering — so these are dropped before that clustering
     * ever runs, not merely ignored by it.
     */
    private static boolean isRunningHeaderOrFooterNoise(String t) {
        String upper = t.toUpperCase();
        if (upper.contains("GAZETTE OF INDIA")) return true;
        if (upper.matches("^\\[?\\s*PART\\s+I.*")) return true;
        return t.matches("^\\[.*]$");
    }

    // ── column classification ───────────────────────────────────────────

    private static boolean isBulletStart(String text) {
        String t = text.trim();
        if (t.isEmpty()) return false;
        char c = t.charAt(0);
        return !Character.isLetterOrDigit(c);
    }

    /**
     * Splits the body runs' x positions into two clusters at the largest
     * gap, then returns the split point as the boundary between the
     * (left) Category column and the (right) Sub-sector column, identified
     * by which side holds the majority of bullet-prefixed runs.
     */
    private float computeColumnSplitX(List<ExtractedRun> body, boolean[] isBullet, boolean[] isSrNoBoundary) {
        List<Float> xs = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            if (isSrNoBoundary[i] && !isBullet[i]) continue;
            xs.add(body.get(i).minX());
        }
        List<Float> sorted = new ArrayList<>(xs);
        sorted.sort(Float::compareTo);

        if (sorted.size() < 2) return Float.MAX_VALUE; // everything -> one column

        float bestGap = -1f;
        float splitPoint = sorted.get(sorted.size() - 1);
        for (int i = 1; i < sorted.size(); i++) {
            float gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > bestGap) {
                bestGap = gap;
                splitPoint = (sorted.get(i) + sorted.get(i - 1)) / 2f;
            }
        }

        int bulletsLow = 0, bulletsHigh = 0;
        for (int i = 0; i < body.size(); i++) {
            if (!isBullet[i]) continue;
            if (body.get(i).minX() <= splitPoint) bulletsLow++;
            else bulletsHigh++;
        }
        // Bullets must fall on the "sub-sector" side. If the low side holds
        // the bullets instead, the two clusters are flipped from what this
        // method assumes — return an unreachable split so every run is
        // treated as sub-sector column rather than mis-labelling categories.
        if (bulletsLow > bulletsHigh) return -Float.MAX_VALUE;
        return splitPoint;
    }

    // ── name cleanup ─────────────────────────────────────────────────────

    /**
     * Note: an earlier version of this method also tried to collapse a
     * stray mid-word space (the source PDF's own glyph stream embeds one in
     * "Logistics", extracted as "Logis tics" — confirmed independently with
     * {@code pdftotext -layout}, so it is a property of the government's
     * typesetting, not of this extractor/parser). That turned out to be
     * unsafe in general: plenty of real, legitimately space-separated short
     * words show up in these sub-sector descriptions ("along with", "in
     * case of", "more than", ...), and a blanket rule for "short lowercase
     * fragment after a lowercase letter" mangled those into "alongwith",
     * "incase of", "morethan". Left as one known, documented cosmetic
     * artifact rather than risking that corruption.
     */
    private static String cleanName(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        s = TRAILING_FOOTNOTE_DIGITS.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * A short, all-lowercase fragment appended right after a lowercase
     * letter is the tail of a word an unusually wide glyph gap split in
     * two — join with no space. Anything else is a genuine new word/line
     * and gets a separating space.
     */
    private static void appendWrapped(StringBuilder accumulated, String fragment) {
        boolean midWordSplit = accumulated.length() > 0
                && Character.isLowerCase(accumulated.charAt(accumulated.length() - 1))
                && fragment.length() <= 4
                && fragment.chars().allMatch(Character::isLowerCase);
        if (!midWordSplit) accumulated.append(' ');
        accumulated.append(fragment);
    }

    // ── builders ─────────────────────────────────────────────────────────

    private static final class ItemBuilder {
        private final StringBuilder name;

        ItemBuilder(String first) {
            this.name = new StringBuilder(first);
        }

        void appendContinuation(String text) {
            appendWrapped(name, text);
        }

        String build() {
            return cleanName(name.toString());
        }
    }

    private static final class CategoryBuilder {
        private final StringBuilder name;
        private final int order;
        private final List<String> items = new ArrayList<>();

        CategoryBuilder(String first, int order) {
            this.name = new StringBuilder(first);
            this.order = order;
        }

        void appendNameContinuation(String text) {
            appendWrapped(name, text);
        }

        void addItem(String itemName) {
            String cleaned = cleanName(itemName);
            if (!cleaned.isEmpty() && !items.contains(cleaned)) items.add(cleaned);
        }

        ParsedCategory build() {
            List<ParsedSubCategory> subs = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) subs.add(new ParsedSubCategory(items.get(i), i + 1));
            return new ParsedCategory(cleanName(name.toString()), order, subs);
        }
    }
}
