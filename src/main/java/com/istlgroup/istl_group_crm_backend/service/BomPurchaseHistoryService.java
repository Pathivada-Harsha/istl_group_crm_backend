package com.istlgroup.istl_group_crm_backend.service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseHintRow;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.util.BomRateVisibility;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  BomPurchaseHistoryService — "what did we last pay for this item, in this make?"
 *
 *  Powers the read-only price hint beside a BOM line's unit-rate field. It is a
 *  SUGGESTION: this class never writes anything, and the value it returns only
 *  ever reaches a rate field through an explicit user click.
 *
 *  ── The one rule, and why it is structural ──────────────────────────────────
 *  The spec requires identical behaviour for every item category, with no
 *  per-item-type branches anywhere. That is enforced by the signature, not by
 *  discipline: {@link #hints} takes catalog id pairs and a role, and nothing else.
 *  There is no lead, no project, no group and no category in scope — so there is
 *  no value a branch could key off even if someone tried to add one.
 *
 *  ── Matching, in the spec's order ───────────────────────────────────────────
 *   1. VARIANT — purchases of this item in the make the estimator picked. Headline.
 *   2. ITEM    — if that make was never bought, the newest purchase of the same
 *                item in a DIFFERENT make, always carrying that make's label so it
 *                can never be read as the price for the selected one.
 *   3. nothing — the pair is ABSENT from the returned map. Not null, not zero, not
 *                an empty shell. Absence is what makes "never show a blank
 *                placeholder as if it were a price" impossible to get wrong at the
 *                render site: there is no object there to render.
 *
 *  ── What is deliberately NOT here ───────────────────────────────────────────
 *  No fuzzy matching. Makes are matched on {@code bom_item_variants.id} only, and
 *  the query drops rows whose catalog link was itself inferred from free text. A
 *  make spelled differently on an old PO is a miss, and a miss is the right answer.
 *
 *  No unit comparison. Whether the purchased unit matches the BOM line's unit is a
 *  per-LINE question, but this map is keyed per (item, make) — two lines can share
 *  one pair and disagree on unit, so any answer stored here would be wrong for one
 *  of them. The purchased {@code unit} is returned and the caller compares.
 *
 *  No margin. Cost only, ex-GST, exclusive of freight and handling. Not landed cost.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
public class BomPurchaseHistoryService {

    private static final Logger log = LoggerFactory.getLogger(BomPurchaseHistoryService.class);

    @Autowired private PurchaseOrderItemRepository poItemRepo;
    @Autowired private BomItemVariantRepo          variantRepo;

    // Package-private with field initialisers: Spring overwrites these from the
    // properties at runtime, and the initialisers are what a plain unit test (no
    // context) runs on. Tests in this package tune them directly.
    @Value("${bom.purchase-hint.enabled:true}")   boolean enabled     = true;
    @Value("${bom.purchase-hint.stale-days:90}")  int     staleDays   = 90;
    @Value("${bom.purchase-hint.history-size:3}") int     historySize = 3;
    @Value("${bom.purchase-hint.row-cap:2000}")   int     rowCap      = 2000;

    /** Overridden in tests so "stale" and "ageDays" are assertable. */
    Clock clock = Clock.systemDefaultZone();

    /** A catalog item and the catalog make chosen for it. Both required — see {@link #parsePairs}. */
    public record ItemVariantPair(Long itemId, Long variantId) {
        public String key() { return itemId + ":" + variantId; }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * @param pairs    the (item, make) pairs to price. Pairs with no qualifying
     *                 history are omitted from the result — see the class note.
     * @param userRole raw role header; null is unrestricted, matching the rest of the
     *                 codebase. A role that may not see rates gets an empty map rather
     *                 than a 403, so the BOM tab still renders, just without hints.
     */
    public Map<String, Object> hints(Collection<ItemVariantPair> pairs, String userRole) {
        Map<String, Object> hints = new LinkedHashMap<>();

        if (enabled && pairs != null && !pairs.isEmpty() && BomRateVisibility.canSeeRates(userRole)) {
            Set<Long> itemIds = new LinkedHashSet<>();
            for (ItemVariantPair p : pairs) {
                if (p != null && p.itemId() != null && p.variantId() != null) itemIds.add(p.itemId());
            }
            if (!itemIds.isEmpty()) buildHints(pairs, itemIds, hints);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("staleDays", staleDays);
        data.put("hints", hints);
        return data;
    }

    private void buildHints(Collection<ItemVariantPair> pairs, Set<Long> itemIds,
                            Map<String, Object> out) {

        List<PurchaseHintRow> rows = poItemRepo.findPurchaseHistoryForItems(itemIds, rowCap);
        if (rows.size() >= rowCap) {
            // Not paging — a runaway guard. If this ever fires, a flat recency fetch has
            // stopped being able to guarantee coverage for the rarer items in the set,
            // and the query wants ROW_NUMBER() partitioned by bom_item_id instead.
            log.warn("BOM price hint hit the {} row cap for {} item(s); older purchases of "
                   + "less-frequently-bought items may be invisible.", rowCap, itemIds.size());
        }
        if (rows.isEmpty()) return;

        // Rows arrive newest-first and every list below preserves that order, so "the
        // latest" is always simply the first survivor of a filter.
        Map<Long, List<PurchaseHintRow>> byItem = new LinkedHashMap<>();
        Set<Long> variantIds = new LinkedHashSet<>();
        for (PurchaseHintRow r : rows) {
            byItem.computeIfAbsent(r.getItemId(), k -> new ArrayList<>()).add(r);
            if (r.getVariantId() != null) variantIds.add(r.getVariantId());
        }

        Map<Long, String> labels = variantLabels(variantIds);

        for (ItemVariantPair pair : pairs) {
            if (pair == null || pair.itemId() == null || pair.variantId() == null) continue;
            if (out.containsKey(pair.key())) continue;               // duplicate pair in the request

            List<PurchaseHintRow> forItem = byItem.get(pair.itemId());
            if (forItem == null || forItem.isEmpty()) continue;

            // 1. Exact make.
            List<PurchaseHintRow> exact = new ArrayList<>();
            for (PurchaseHintRow r : forItem) {
                if (pair.variantId().equals(r.getVariantId())) exact.add(r);
            }
            List<PurchaseHintRow> history = dedupeByPo(exact);
            String match     = "VARIANT";
            String makeLabel = null;

            // 2. Fallback: same item, different make — but only if it can be NAMED. An
            //    unlabelled fallback would read as the selected make's price, which is
            //    the one thing this must never do.
            if (history.isEmpty()) {
                List<PurchaseHintRow> other = new ArrayList<>();
                for (PurchaseHintRow r : forItem) {
                    if (labelFor(r, labels) != null) other.add(r);
                }
                history = dedupeByPo(other);
                if (history.isEmpty()) continue;                     // 3. no hint at all
                match     = "ITEM";
                makeLabel = labelFor(history.get(0), labels);
            }

            out.put(pair.key(), hint(pair, match, makeLabel, history, labels));
        }
    }

    // ── Shaping ──────────────────────────────────────────────────────────────

    private Map<String, Object> hint(ItemVariantPair pair, String match, String makeLabel,
                                     List<PurchaseHintRow> history, Map<Long, String> labels) {

        PurchaseHintRow top = history.get(0);
        LocalDate       on  = top.getOrderDate().toLocalDate();
        // Clamped: a future-dated draft must not read as "stale in -12 days".
        long ageDays = Math.max(0, ChronoUnit.DAYS.between(on, LocalDate.now(clock)));

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("bomItemId", pair.itemId());
        h.put("variantId", pair.variantId());
        h.put("match",     match);
        h.put("makeLabel", makeLabel);
        // Rounded to the 2dp lead_bom.unit_rate actually holds, so applying a hint cannot
        // silently change the number on the next save/reload. The 6dp source rides along
        // for the detail panel.
        h.put("unitRate",    top.getUnitPrice().setScale(2, RoundingMode.HALF_UP));
        h.put("unitRateRaw", top.getUnitPrice());
        h.put("unit",        top.getUnit());
        h.put("orderDate",   on.toString());
        h.put("ageDays",     ageDays);
        h.put("stale",       ageDays > staleDays);
        h.put("poNo",        top.getPoNo());
        h.put("poStatus",    top.getPoStatus());
        h.put("vendorName",  top.getVendorName());
        h.put("quantity",    top.getQuantity());
        h.put("taxPercent",  top.getTaxPercent());

        List<Map<String, Object>> rows = new ArrayList<>(history.size());
        for (PurchaseHintRow r : history) rows.add(historyRow(r, labels));
        h.put("history", rows);
        return h;
    }

    private Map<String, Object> historyRow(PurchaseHintRow r, Map<Long, String> labels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderDate", r.getOrderDate().toLocalDate().toString());
        m.put("poNo",      r.getPoNo());
        // Carried on every row because DRAFT POs count towards this hint (procurement has
        // one liveness predicate and this does not fork it). Showing the status is what
        // keeps a draft-sourced price honest.
        m.put("poStatus",    r.getPoStatus());
        m.put("vendorName",  r.getVendorName());
        m.put("quantity",    r.getQuantity());
        m.put("unitRate",    r.getUnitPrice().setScale(2, RoundingMode.HALF_UP));
        m.put("unitRateRaw", r.getUnitPrice());
        m.put("unit",        r.getUnit());
        m.put("taxPercent",  r.getTaxPercent());
        m.put("variantId",   r.getVariantId());
        m.put("makeLabel",   labelFor(r, labels));
        return m;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * One row per PO, newest first, capped at the history size. "The last 3 purchases"
     * means three buying EVENTS — without this, one PO carrying five lines of the same
     * item would fill the panel and hide the two purchases before it entirely.
     */
    private List<PurchaseHintRow> dedupeByPo(List<PurchaseHintRow> rows) {
        Map<Long, PurchaseHintRow> byPo = new LinkedHashMap<>();
        for (PurchaseHintRow r : rows) {
            byPo.putIfAbsent(r.getPoId(), r);                        // first seen = newest (query order)
            if (byPo.size() >= historySize) break;
        }
        return new ArrayList<>(byPo.values());
    }

    /**
     * Display name for the make behind a purchase, preferring the LIVE catalog row over
     * the PO's snapshot — the same call {@code PurchaseOrderService.enrichVendorName}
     * makes for vendor names. Null when the make cannot be named at all, which is the
     * caller's signal to skip the row rather than offer a nameless "different make".
     */
    private String labelFor(PurchaseHintRow r, Map<Long, String> labels) {
        if (r.getVariantId() != null) {
            String catalog = labels.get(r.getVariantId());
            if (catalog != null && !catalog.isBlank()) return catalog;
        }
        String text = r.getMakeText();
        return text != null && !text.isBlank() ? text.trim() : null;
    }

    private Map<Long, String> variantLabels(Set<Long> variantIds) {
        if (variantIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> labels = new LinkedHashMap<>();
        for (BomItemVariantEntity v : variantRepo.findAllById(variantIds)) {
            String label = join(v.getMake(), v.getModel());
            if (!label.isBlank()) labels.put(v.getId(), label);
        }
        return labels;
    }

    private static String join(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isBlank()) sb.append(a.trim());
        if (b != null && !b.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b.trim());
        }
        return sb.toString();
    }

    // ── Request parsing ──────────────────────────────────────────────────────

    /**
     * Parse the {@code pairs=12:45,80:3} query parameter. Anything malformed, or any
     * pair missing either id, is dropped silently: the spec's trigger is "item AND make
     * both chosen from the catalog", so a half-identified line has no hint by definition
     * and is not an error worth failing the whole request over.
     */
    public static List<ItemVariantPair> parsePairs(String raw) {
        List<ItemVariantPair> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty() || !seen.add(t)) continue;
            int colon = t.indexOf(':');
            if (colon <= 0 || colon == t.length() - 1) continue;
            try {
                out.add(new ItemVariantPair(Long.parseLong(t.substring(0, colon).trim()),
                                            Long.parseLong(t.substring(colon + 1).trim())));
            } catch (NumberFormatException ignored) {
                // Not a pair of ids, so there is nothing to look up.
            }
        }
        return out;
    }
}
