package com.istlgroup.istl_group_crm_backend.service.scope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeSubItemRequest;

import lombok.RequiredArgsConstructor;

/**
 * The second level of a scope line — its sub-item breakdown — wherever one is stored.
 *
 * <p>The same breakdown now lives in three places: on a template scope line
 * ({@code lead_scope_template_items.sub_items}), on a lead's scope item
 * ({@code lead_scope_items.sub_items}) and on a project phase
 * ({@code project_phases.sub_items}). They share one storage shape and one weight
 * rule, so they share this class rather than three copies that drift apart.
 *
 * <p><b>The weight rule:</b> a sub-item's weight is a share of ITS OWN PARENT and the
 * group totals 100 within that parent — never a share of the whole scope. That keeps
 * the parent weights the only thing that has to add up across the scope, so breaking
 * one activity down can never disturb the others. Everything else (pinning, the
 * display-rounding tolerance, pushing the correction onto the largest row) is
 * deliberately identical to the parent-level rules in
 * {@code LeadAdminService.normaliseScopeWeights} and the frontend's
 * {@code utils/scopeWeights.js} — one model, applied at two levels.
 *
 * <p><b>Storage shape</b> is
 * {@code {id, name, description, unit, weightPct, weightManual, children[]}}, a subset of
 * the richer {@code project_phases.sub_items} element (which also carries the execution
 * fields {@code status / progressPercent / dates}). The subset is deliberate: a template
 * and a lead describe work, not a run of it. Because it IS a subset, a project phase
 * seeded from one needs no translation — and {@link #mergePreservingExecutionData} exists
 * to make sure the execution fields a project has already accumulated are not thrown away
 * when the breakdown is re-suggested.
 *
 * <p><b>The tree, and why identity is an id and not a name.</b> {@code children} nests to
 * any depth, so one activity can break down as "Electrical Works → PV Module → Purchase
 * Order". Once that is allowed, the SAME NAME legitimately appears in different branches —
 * a real schedule repeats "Foundation", "Payment", "Receiving", "Installation" under many
 * parents — and a name-keyed store would silently merge their progress and their money.
 * So every node carries a stable {@code id} (a UUID, minted once by {@link #ensureIds} and
 * then carried through every copy and every edit), and
 * {@code project_progress_periods.sub_item_key} together with the planned budget key off
 * THAT. A name is therefore only a label: renaming a node keeps its history, and two nodes
 * that share a name are still two nodes.
 *
 * <p>A concatenated name-path ("Civil &gt; Substation &gt; Excavation") was considered as
 * the identity and rejected: it detaches history the moment any ancestor is renamed.
 */
@Component
@RequiredArgsConstructor
public class ScopeSubItems {

    /** Weights are stored to six decimals, matching {@code project_phases.weight_pct}. */
    private static final int WEIGHT_SCALE = 6;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    /** The most a single two-decimal display value can differ from what is stored. */
    private static final BigDecimal DISPLAY_ROUNDING_STEP = new BigDecimal("0.005");

    /**
     * Fields a project phase's sub-item accumulates while the job runs. They exist on
     * neither a template nor a lead, and must survive a re-suggest — losing one silently
     * rewrites a number nobody typed.
     */
    private static final List<String> EXECUTION_FIELDS = List.of(
            "status", "progressPercent", "plannedProgressPct", "plannedBudget",
            "startDate", "endDate", "startWeek", "endWeek",
            // A node's own span and how it divides. These are a SCHEDULE, which a
            // template has no business overwriting: re-suggesting the standard must
            // not silently move work someone has already planned into a calendar.
            "plannedStartDate", "plannedEndDate", "planUnit");

    private final ObjectMapper objectMapper;

    // ── Identity ─────────────────────────────────────────────────────────────────

    /**
     * The matching key for a sub-item name: trimmed and lower-cased, the same
     * normalisation {@code ProjectLeadSeedService.activityKey} uses for parents.
     *
     * <p>This is for MATCHING ONLY. The stored name is never rewritten to this form —
     * {@code ProjectDetailService.saveScopeBudgets} merges planned budgets by exact,
     * case-sensitive {@code String.equals} against the stored name, so normalising on
     * write would break that merge for every existing row.
     */
    public static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    /** A node's id, or "" when it has none yet. Never null, so it is safe as a map key. */
    public static String idOf(Map<String, Object> node) {
        Object v = node == null ? null : node.get("id");
        String s = v == null ? "" : String.valueOf(v).trim();
        return s;
    }

    /**
     * Give every node in the tree an id, IN PLACE, and return the tree.
     *
     * <p>Only nodes that lack one are touched, so this is idempotent and safe to call on
     * every save: a node the user has been tracking for months keeps the id its progress
     * rows point at, while a node they added a second ago gets a fresh one. That is the
     * whole reason a rename cannot lose history — the id never depends on the name.
     */
    public List<TemplateScopeSubItemRequest> ensureIds(List<TemplateScopeSubItemRequest> nodes) {
        if (nodes == null) return null;
        for (TemplateScopeSubItemRequest n : nodes) {
            if (n == null) continue;
            if (n.getId() == null || n.getId().isBlank()) n.setId(java.util.UUID.randomUUID().toString());
            ensureIds(n.getChildren());
        }
        return nodes;
    }

    /** The same, for the parsed {@code Map} form used on the project side. */
    public List<Map<String, Object>> ensureIdsOnMaps(List<Map<String, Object>> nodes) {
        if (nodes == null) return null;
        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            if (idOf(n).isEmpty()) n.put("id", java.util.UUID.randomUUID().toString());
            ensureIdsOnMaps(childrenOf(n));
        }
        return nodes;
    }

    /**
     * A node's children as a mutable list, or an empty list. Reads the raw parsed form,
     * where {@code children} is whatever Jackson produced.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> childrenOf(Map<String, Object> node) {
        Object c = node == null ? null : node.get("children");
        if (!(c instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** True when this node has no breakdown of its own — the level progress is recorded at. */
    public static boolean isLeaf(Map<String, Object> node) {
        return childrenOf(node).isEmpty();
    }

    /**
     * Every node in the tree, depth-first in reading order, keyed by its id.
     *
     * <p>This is the lookup the project side uses to attach progress and budget to a node
     * at ANY depth: the caller no longer walks a single flat level, it asks this index for
     * the id a progress row names. Nodes with no id (pre-migration data that somehow got
     * here) are skipped rather than colliding on "".
     */
    public Map<String, Map<String, Object>> indexById(List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        collectById(nodes, out);
        return out;
    }

    private void collectById(List<Map<String, Object>> nodes, Map<String, Map<String, Object>> out) {
        if (nodes == null) return;
        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            String id = idOf(n);
            if (!id.isEmpty()) out.putIfAbsent(id, n);
            collectById(childrenOf(n), out);
        }
    }

    // ── Weights ──────────────────────────────────────────────────────────────────

    /**
     * Validate and normalise one parent's breakdown IN PLACE.
     *
     * <p>A null/empty list is "no breakdown" and is left alone — sub-items are opt-in
     * per line, so a scope where nothing has been broken down stays valid.
     *
     * @param activity the parent's name, used only to make an error message point at
     *                 the row the user has to go and fix
     */
    public void normaliseWeights(String activity, List<TemplateScopeSubItemRequest> subs)
            throws CustomException {
        normaliseGroup(activity, subs);
        if (subs == null) return;
        // Each node's own children are their own weight group, totalling 100 within THAT
        // node. Descending after the group is validated means the error the user sees is
        // always the outermost thing that is wrong, not a symptom several levels down.
        for (TemplateScopeSubItemRequest si : subs) {
            if (si == null || si.getChildren() == null || si.getChildren().isEmpty()) continue;
            String childPath = (activity == null || activity.isBlank())
                    ? String.valueOf(si.getName()).trim()
                    : activity + " › " + String.valueOf(si.getName()).trim();
            normaliseWeights(childPath, si.getChildren());
        }
    }

    /**
     * One sibling group, validated and normalised IN PLACE. This is the whole weight rule
     * at a single level; {@link #normaliseWeights} is just this applied down the tree.
     *
     * @param activity the parent's name — a "A › B › C" breadcrumb below the top level, so
     *                 an error names the exact branch to go and fix. It is a message, never
     *                 an identity: see the class note on why the id is not a name-path.
     */
    private void normaliseGroup(String activity, List<TemplateScopeSubItemRequest> subs)
            throws CustomException {
        if (subs == null || subs.isEmpty()) return;

        for (TemplateScopeSubItemRequest si : subs) {
            if (si.getName() == null || si.getName().isBlank()) {
                throw new CustomException("A sub-item under \"" + activity + "\" has no name.");
            }
        }

        boolean allNull = true;
        for (TemplateScopeSubItemRequest si : subs) {
            if (si.getWeightPct() != null) { allNull = false; break; }
        }
        if (allNull) { evenSplit(subs); return; }

        BigDecimal sum = BigDecimal.ZERO;
        for (TemplateScopeSubItemRequest si : subs) {
            if (si.getWeightPct() != null) sum = sum.add(si.getWeightPct());
        }
        BigDecimal delta = ONE_HUNDRED.subtract(sum);

        // The total is checked before the per-line check: when both are wrong — one
        // sub-item typed at 120% leaves the auto-balanced ones at 0% — naming the total
        // explains the problem, whereas naming a zeroed row points away from what the
        // user actually did. Same order as the parent level, so the messages agree.
        BigDecimal tolerance = DISPLAY_ROUNDING_STEP.multiply(BigDecimal.valueOf(subs.size()));
        if (delta.abs().compareTo(tolerance) > 0) {
            throw new CustomException("Sub-items under \"" + activity + "\" total "
                    + sum.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "% — they must add up to 100% of their activity.");
        }
        for (TemplateScopeSubItemRequest si : subs) {
            BigDecimal w = si.getWeightPct();
            if (w == null || w.signum() <= 0) {
                throw new CustomException("Sub-item \"" + si.getName().trim() + "\" under \""
                        + activity + "\" has a weight of 0% — every sub-item must carry a weight above zero.");
            }
        }
        if (delta.signum() == 0) return;

        // The correction goes to the largest row: proportionally the smallest possible
        // adjustment, and invisible at two decimals.
        TemplateScopeSubItemRequest largest = subs.get(0);
        for (TemplateScopeSubItemRequest si : subs) {
            if (si.getWeightPct().compareTo(largest.getWeightPct()) > 0) largest = si;
        }
        largest.setWeightPct(largest.getWeightPct().add(delta).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP));
    }

    /** Even split totalling exactly 100 — the last row absorbs the remainder. */
    private void evenSplit(List<TemplateScopeSubItemRequest> subs) {
        BigDecimal each = ONE_HUNDRED.divide(BigDecimal.valueOf(subs.size()), WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < subs.size(); i++) {
            BigDecimal w = (i == subs.size() - 1) ? ONE_HUNDRED.subtract(running) : each;
            subs.get(i).setWeightPct(w.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP));
            subs.get(i).setWeightManual(Boolean.FALSE);
            running = running.add(w);
        }
    }

    // ── JSON round trip ──────────────────────────────────────────────────────────

    /**
     * Sub-items → the {@code sub_items} JSON column. An empty breakdown is stored as SQL
     * NULL rather than {@code []} so "never broken down" and "broken down, then emptied"
     * read the same downstream.
     *
     * <p>The name is trimmed but its case is kept — see {@link #nameKey}.
     */
    public String serialise(List<TemplateScopeSubItemRequest> subs) throws CustomException {
        if (subs == null || subs.isEmpty()) return null;
        ensureIds(subs);
        try {
            return objectMapper.writeValueAsString(toMaps(subs));
        } catch (Exception e) {
            throw new CustomException("Could not save the sub-items: " + e.getMessage());
        }
    }

    /** The tree as plain maps, depth-first, ids included and children nested. */
    /** Put a value only when it is actually set — blank strings are "not scheduled". */
    private static void putIfSet(Map<String, Object> m, String key, Object v) {
        if (v == null) return;
        if (v instanceof String s && s.isBlank()) return;
        m.put(key, v);
    }

    private List<Map<String, Object>> toMaps(List<TemplateScopeSubItemRequest> subs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (subs == null) return out;
        for (TemplateScopeSubItemRequest si : subs) {
            if (si == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", si.getId());
            m.put("name", si.getName().trim());
            m.put("description", si.getDescription());
            m.put("unit", si.getUnit());
            m.put("weightPct", si.getWeightPct());
            m.put("weightManual", Boolean.TRUE.equals(si.getWeightManual()));
            // The schedule. Written only when set, so a node nobody has planned stays
            // exactly the shape it was before scheduling existed. Omitting these from
            // this explicit field list would have silently dropped every date on the
            // lead save path, which serialises through here.
            putIfSet(m, "planUnit", si.getPlanUnit());
            putIfSet(m, "plannedStartDate", si.getPlannedStartDate());
            putIfSet(m, "plannedEndDate", si.getPlannedEndDate());
            putIfSet(m, "startDate", si.getStartDate());
            putIfSet(m, "endDate", si.getEndDate());
            putIfSet(m, "startWeek", si.getStartWeek());
            putIfSet(m, "endWeek", si.getEndWeek());
            // Omitted entirely for a leaf, so a leaf reads the same as it did before
            // nesting existed and no consumer has to tell null from [].
            List<Map<String, Object>> kids = toMaps(si.getChildren());
            if (!kids.isEmpty()) m.put("children", kids);
            out.add(m);
        }
        return out;
    }

    /**
     * The {@code sub_items} column → a real JSON array, never a string.
     *
     * <p>Unreadable content comes back as an empty list: the column was dormant storage
     * for a while, so anything already in it is not guaranteed to parse, and one bad row
     * must not stop a whole template or lead from loading.
     */
    public List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> parsed =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Roll-up ──────────────────────────────────────────────────────────────────

    /**
     * Roll a numeric field up through the tree, bottom-up.
     *
     * <p>A LEAF contributes the value stored on it. A PARENT contributes the
     * weight-weighted average of its own children — and because those children may
     * themselves be parents, the recursion is what makes the total correct at any depth.
     * A parent's own stored value is ignored when it has children: the children are the
     * finer-grained truth, and a stale number left on the parent must never outrank them.
     *
     * <p>Children with no positive weight are skipped rather than counted as zero: an
     * un-weighted node would otherwise drag the whole branch down. If NO child carries a
     * weight the group falls back to a plain mean, which is what the single-level version
     * did and keeps a half-configured branch reporting something sane instead of 0.
     *
     * @return the weighted value, or null when the subtree holds nothing to average
     */
    public BigDecimal rollUp(List<Map<String, Object>> nodes, String field) {
        if (nodes == null || nodes.isEmpty()) return null;
        BigDecimal acc = BigDecimal.ZERO, wsum = BigDecimal.ZERO;
        BigDecimal plain = BigDecimal.ZERO; int plainN = 0;

        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            List<Map<String, Object>> kids = childrenOf(n);
            BigDecimal v = kids.isEmpty() ? toBigDecimal(n.get(field)) : rollUp(kids, field);
            if (v == null) v = BigDecimal.ZERO;

            plain = plain.add(v); plainN++;
            BigDecimal w = toBigDecimal(n.get("weightPct"));
            if (w == null || w.signum() <= 0) continue;
            acc = acc.add(v.multiply(w));
            wsum = wsum.add(w);
        }
        if (wsum.signum() > 0) return acc.divide(wsum, 2, RoundingMode.HALF_UP);
        if (plainN > 0) return plain.divide(BigDecimal.valueOf(plainN), 2, RoundingMode.HALF_UP);
        return null;
    }

    /**
     * Sum a numeric field over the tree the way money adds up: a parent with children is
     * the SUM of its children (not an average), recursively, and only a leaf contributes
     * a stored figure. Used for planned budget, where a parent total that disagreed with
     * its own breakdown would be a number nobody typed.
     */
    public BigDecimal sumLeaves(List<Map<String, Object>> nodes, String field) {
        if (nodes == null || nodes.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (Map<String, Object> n : nodes) {
            if (n == null) continue;
            List<Map<String, Object>> kids = childrenOf(n);
            BigDecimal v = kids.isEmpty() ? toBigDecimal(n.get(field)) : sumLeaves(kids, field);
            if (v != null) { total = total.add(v); any = true; }
        }
        return any ? total : null;
    }

    /** Lenient number read — the JSON carries these as Integer, Double, String or null. */
    public static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    // ── Suggest: template → response ─────────────────────────────────────────────

    /**
     * Put each template line's breakdown onto the matching suggested scope map, IN PLACE.
     *
     * <p>Correlated by index rather than by name, because
     * {@code LeadSuggestionEngine.expandTemplateScope} is 1:1 and order-preserving over
     * the template lines. That is the same trick
     * {@code ScopeTemplateExpander.attachVariantChoices} uses for BOM variants, and it is
     * why the sizing engine stays untouched: it keeps a passthrough field out of the class
     * that owns the quantity maths.
     *
     * <p>The key is always set, even when there is no breakdown, so a caller never has to
     * tell "no sub-items" from "this response predates sub-items".
     */
    public void attachTo(List<Map<String, Object>> scope, List<String> subItemsJsonByIndex) {
        if (scope == null) return;
        int n = subItemsJsonByIndex == null ? 0 : subItemsJsonByIndex.size();
        for (int i = 0; i < scope.size(); i++) {
            scope.get(i).put("subItems", i < n ? parse(subItemsJsonByIndex.get(i)) : List.of());
        }
    }

    // ── Re-suggest merge ─────────────────────────────────────────────────────────

    /**
     * Fold an incoming breakdown onto the one already stored, keeping what the existing
     * rows have accumulated.
     *
     * <p>This is what stops a re-suggest quietly destroying work. A node's identity is its
     * {@code id} — {@code project_progress_periods.sub_item_key} and the planned-budget
     * merge both key off it — so a node that survives a re-suggest must keep that id, or
     * its weekly progress and budget are orphaned with no error anywhere.
     *
     * <p>Rules, applied independently WITHIN EACH PARENT and then recursively down the
     * tree — never globally by name, which would match the "Excavation" under Civil
     * against the "Excavation" under Substation and move a year of progress into the
     * wrong branch:
     * <ul>
     *   <li>Matched by {@code id}, or failing that by {@link #nameKey} among the remaining
     *       siblings → the EXISTING node is kept (its id, its stored name and every
     *       execution field intact); the incoming node contributes only the definition
     *       fields (description / unit / weight), and their children are merged the same
     *       way one level down.</li>
     *   <li>In the incoming set but not the existing one → added, with a fresh id.</li>
     *   <li>In the existing set but not the incoming one → removed, along with its whole
     *       subtree. It is no longer part of the standard, and the confirm dialog said so.</li>
     * </ul>
     * Order follows the incoming list, which is the order the template defines.
     *
     * <p>The name fallback is what carries pre-migration data and any node the user typed
     * by hand on both sides through its first re-suggest; once matched, the existing id
     * wins and every later merge is id-based.
     *
     * @param existing nodes already stored (parsed form), may be null/empty
     * @param incoming nodes the suggestion produced (parsed form), may be null/empty
     */
    public List<Map<String, Object>> mergePreservingExecutionData(
            List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        if (incoming == null || incoming.isEmpty()) return List.of();

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (existing != null) {
            for (Map<String, Object> si : existing) {
                if (si == null) continue;
                String id = idOf(si);
                if (!id.isEmpty()) byId.putIfAbsent(id, si);
                // First wins: duplicate names within one parent are possible (nothing
                // forbids them), and the first is the one a name lookup resolves to.
                byName.putIfAbsent(nameKey(si.get("name") == null ? null : String.valueOf(si.get("name"))), si);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<Map<String, Object>> claimed = new java.util.HashSet<>();
        for (Map<String, Object> in : incoming) {
            if (in == null) continue;

            Map<String, Object> prior = byId.get(idOf(in));
            if (prior == null || claimed.contains(prior)) {
                Map<String, Object> byNm = byName.get(
                        nameKey(in.get("name") == null ? null : String.valueOf(in.get("name"))));
                prior = (byNm != null && !claimed.contains(byNm)) ? byNm : null;
            }

            Map<String, Object> merged = new LinkedHashMap<>(in);
            if (prior == null) {
                // A genuinely new node. It gets its id from ensureIdsOnMaps below, and its
                // children are new too, so nothing is preserved and nothing is lost.
                merged.remove("id");
            } else {
                claimed.add(prior);
                // Start from the incoming definition, then restore the identity and
                // everything the run of the job has written onto this node.
                merged.put("id", prior.get("id"));
                merged.put("name", prior.get("name"));
                for (String f : EXECUTION_FIELDS) {
                    if (prior.containsKey(f)) merged.put(f, prior.get(f));
                }
            }

            List<Map<String, Object>> kids = mergePreservingExecutionData(
                    prior == null ? List.of() : childrenOf(prior), childrenOf(in));
            if (kids.isEmpty()) merged.remove("children"); else merged.put("children", kids);
            out.add(merged);
        }
        ensureIdsOnMaps(out);
        return out;
    }
}
