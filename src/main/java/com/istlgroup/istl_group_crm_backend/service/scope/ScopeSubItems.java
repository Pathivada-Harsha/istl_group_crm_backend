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
 * <p><b>Storage shape</b> is {@code {name, description, unit, weightPct, weightManual}},
 * a subset of the richer {@code project_phases.sub_items} element (which also carries
 * the execution fields {@code status / progressPercent / dates}). The subset is
 * deliberate: a template and a lead describe work, not a run of it. Because it IS a
 * subset, a project phase seeded from one needs no translation — and
 * {@link #mergePreservingExecutionData} exists to make sure the execution fields a
 * project has already accumulated are not thrown away when the breakdown is re-suggested.
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
            "startDate", "endDate", "startWeek", "endWeek");

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
        List<Map<String, Object>> out = new ArrayList<>();
        for (TemplateScopeSubItemRequest si : subs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", si.getName().trim());
            m.put("description", si.getDescription());
            m.put("unit", si.getUnit());
            m.put("weightPct", si.getWeightPct());
            m.put("weightManual", Boolean.TRUE.equals(si.getWeightManual()));
            out.add(m);
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new CustomException("Could not save the sub-items: " + e.getMessage());
        }
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
     * <p>This is what stops a re-suggest quietly destroying work. A sub-item's only
     * identity in this system is its NAME — {@code project_progress_periods.sub_item_key}
     * and the planned-budget merge both key off the raw string — so a sub-item that
     * survives a re-suggest under the same name must keep that name byte-for-byte, or
     * its weekly progress and budget are orphaned with no error anywhere.
     *
     * <p>Rules, matching the parent level:
     * <ul>
     *   <li>Matched by {@link #nameKey} → the EXISTING row is kept (its stored name and
     *       every execution field intact); the incoming row contributes only the
     *       definition fields (description / unit / weight).</li>
     *   <li>In the incoming set but not the existing one → added.</li>
     *   <li>In the existing set but not the incoming one → removed. It is no longer part
     *       of the standard, and the confirm dialog said so.</li>
     * </ul>
     * Order follows the incoming list, which is the order the template defines.
     *
     * @param existing rows already stored (parsed form), may be null/empty
     * @param incoming rows the suggestion produced (parsed form), may be null/empty
     */
    public List<Map<String, Object>> mergePreservingExecutionData(
            List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        if (incoming == null || incoming.isEmpty()) return List.of();

        Map<String, Map<String, Object>> existingByName = new LinkedHashMap<>();
        if (existing != null) {
            for (Map<String, Object> si : existing) {
                if (si == null) continue;
                String key = nameKey(si.get("name") == null ? null : String.valueOf(si.get("name")));
                // First wins: duplicate names are possible (nothing forbids them), and
                // the first is the one the name-keyed lookups elsewhere resolve to.
                existingByName.putIfAbsent(key, si);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> in : incoming) {
            if (in == null) continue;
            String key = nameKey(in.get("name") == null ? null : String.valueOf(in.get("name")));
            Map<String, Object> prior = existingByName.get(key);
            if (prior == null) { out.add(new LinkedHashMap<>(in)); continue; }

            // Start from the incoming definition, then restore the stored name and
            // everything the run of the job has written onto this row.
            Map<String, Object> merged = new LinkedHashMap<>(in);
            merged.put("name", prior.get("name"));
            for (String f : EXECUTION_FIELDS) {
                if (prior.containsKey(f)) merged.put(f, prior.get(f));
            }
            out.add(merged);
        }
        return out;
    }
}
