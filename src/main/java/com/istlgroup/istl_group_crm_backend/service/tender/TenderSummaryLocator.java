package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stage 2 — find the block that actually holds the summary, and parse that
 * first.
 *
 * <p>Both reference templates state every headline value once, in a compact
 * grid: TREDA's "BID INFORMATION SHEET" on page 3, IREPS' "NIT HEADER" on
 * page 1. Everything after that is contract conditions, proformas and annexures
 * — prose that happens to contain the same words. Parsing the flattened whole
 * document lets a bank-guarantee clause 30 pages in outrank the real value on
 * page 1, which is exactly how "issuing authority" ended up as a sentence
 * fragment.
 *
 * <p>Pages are scored by how many <em>distinct</em> summary anchors they carry.
 * The best-scoring page, plus its immediate neighbours if they score too (a bid
 * information sheet often runs over a page break), becomes the first block to
 * parse; the opening pages and then the whole document follow as fallbacks.
 */
public final class TenderSummaryLocator {

    /** Phrases that only cluster together on a summary page. */
    private static final List<String> ANCHORS = List.of(
            "bid information sheet", "nit header", "name of work", "name of the work",
            "estimated cost", "estimated value", "advertised value", "amount put to tender",
            "emd", "bid security", "earnest money",
            "tender no", "tender reference", "bid enquiry", "nit no", "dnie-t", "dnit",
            "closing date", "last date for submission", "last date of submission",
            "bid submission", "tender closing date", "tender fee", "tender doc. cost",
            "date of publishing", "bidding portal", "tender type", "bid validity",
            "completion period", "period of completion", "tender inviting authority",
            "officer inviting bids", "pre-bid", "bid opening");

    /** A contiguous run of pages to parse as one unit. */
    public record Block(int fromPage, int toPage, int score, String label) {}

    /** How many pages count as "the opening pages" for the fallback block. */
    private static final int OPENING_PAGES = 3;

    /** Neighbouring pages join the summary block only if they score at least this. */
    private static final int NEIGHBOUR_MIN_SCORE = 2;

    private TenderSummaryLocator() {}

    /**
     * Blocks to search, best first: the summary block, then the opening pages,
     * then the whole document. Duplicates are dropped, so a document whose
     * summary <em>is</em> page 1 yields two blocks, not three.
     */
    public static List<Block> rank(TenderText doc) {
        List<Block> out = new ArrayList<>();
        if (doc.pageCount() == 0) return out;

        Block summary = summaryBlock(doc);
        if (summary != null) out.add(summary);

        int openingTo = Math.min(OPENING_PAGES, doc.pageCount());
        addUnlessCovered(out, new Block(1, openingTo, 0, "opening pages"));
        addUnlessCovered(out, new Block(1, doc.pageCount(), 0, "whole document"));
        return out;
    }

    /** The highest-scoring page, widened over neighbours that also score. */
    public static Block summaryBlock(TenderText doc) {
        int best = -1;
        int bestScore = 0;
        int[] scores = new int[doc.pageCount() + 2];
        for (int p = 1; p <= doc.pageCount(); p++) {
            scores[p] = score(doc.page(p));
            if (scores[p] > bestScore) { bestScore = scores[p]; best = p; }
        }
        if (best < 0 || bestScore < NEIGHBOUR_MIN_SCORE) return null;

        int from = best;
        int to = best;
        while (from > 1 && scores[from - 1] >= NEIGHBOUR_MIN_SCORE) from--;
        while (to < doc.pageCount() && scores[to + 1] >= NEIGHBOUR_MIN_SCORE) to++;
        return new Block(from, to, bestScore, "summary block");
    }

    /** Distinct anchors present on a page — repeats of one anchor count once. */
    public static int score(List<String> pageLines) {
        String hay = String.join(" ", pageLines).toLowerCase(Locale.ROOT);
        Set<String> hit = new LinkedHashSet<>();
        for (String a : ANCHORS) {
            if (hay.contains(a)) hit.add(a);
        }
        return hit.size();
    }

    private static void addUnlessCovered(List<Block> out, Block candidate) {
        for (Block b : out) {
            if (b.fromPage() == candidate.fromPage() && b.toPage() == candidate.toPage()) return;
        }
        out.add(candidate);
    }

    /** Order blocks best-score-first; used when callers rank their own set. */
    public static Comparator<Block> byScore() {
        return Comparator.comparingInt(Block::score).reversed()
                .thenComparingInt(Block::fromPage);
    }
}
