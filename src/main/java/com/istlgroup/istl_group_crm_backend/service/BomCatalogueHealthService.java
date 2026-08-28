package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.util.VariantAttributes;

import lombok.RequiredArgsConstructor;

/**
 * Which catalogue makes are missing a number that some template line's basis
 * depends on.
 *
 * A make-driven line (module count from Wp, inverter count from kW) reads a
 * numeric attribute off whichever make the estimator picks. When that attribute
 * was never filled in, the line silently produces nothing and the estimator has
 * no way to know the catalogue is at fault. This walks the other way — from the
 * bases actually in use back to the makes — so the catalogue admin can see the
 * gaps in the one screen where they can be filled.
 *
 * Only ACTIVE templates count: a basis on a deactivated template is not "in
 * use", and flagging makes against it would be noise.
 */
@Service
@RequiredArgsConstructor
public class BomCatalogueHealthService {

    private final LeadScopeTemplateRepo templateRepo;
    private final LeadBomTemplateItemRepo bomTemplateItemRepo;
    private final BomItemVariantRepo bomItemVariantRepo;
    private final BomItemsMasterRepo bomItemsMasterRepo;

    /**
     * {@code { requiredAttributes: [...], items: { "<itemId>": {...} } }} — one
     * entry per catalogue item a driver basis currently points at, listing the
     * attribute it needs and the makes that don't carry it.
     */
    public Map<String, Object> attributeHealth() {
        // itemId → the attribute keys some active template line reads off it.
        Map<Long, Set<String>> keysByItem = new LinkedHashMap<>();
        // itemId → the project types whose template asks for it (for the message).
        Map<Long, Set<String>> typesByItem = new LinkedHashMap<>();

        for (LeadScopeTemplateEntity t : templateRepo.findByDeletedAtIsNullOrderByProjectTypeAscIdAsc()) {
            if (!Boolean.TRUE.equals(t.getIsActive())) continue;
            for (LeadBomTemplateItemEntity line
                    : bomTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(t.getId())) {
                String key = VariantAttributes.attrKeyForBasis(line.getBasis());
                if (key == null || line.getBomItemId() == null) continue;
                keysByItem.computeIfAbsent(line.getBomItemId(), k -> new LinkedHashSet<>()).add(key);
                typesByItem.computeIfAbsent(line.getBomItemId(), k -> new LinkedHashSet<>())
                        .add(t.getProjectType());
            }
        }

        Set<String> allKeys = new LinkedHashSet<>();
        Map<String, Object> items = new LinkedHashMap<>();

        for (Map.Entry<Long, Set<String>> e : keysByItem.entrySet()) {
            Long itemId = e.getKey();
            Set<String> keys = e.getValue();
            allKeys.addAll(keys);

            BomItemsMasterEntity item = bomItemsMasterRepo.findById(itemId).orElse(null);
            String schema = item == null ? null : item.getVariantAttributes();

            List<BomItemVariantEntity> makes =
                    bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(itemId);

            List<Map<String, Object>> incomplete = new ArrayList<>();
            for (BomItemVariantEntity v : makes) {
                List<String> missing = new ArrayList<>();
                for (String key : keys) {
                    if (!VariantAttributes.hasUsableNumeric(v.getAttributeValues(), key)) missing.add(key);
                }
                if (missing.isEmpty()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("variantId", v.getId());
                m.put("make", v.getMake());
                m.put("model", v.getModel());
                m.put("missingKeys", missing);
                m.put("missingLabels", missing.stream().map(k -> VariantAttributes.labelForKey(schema, k)).toList());
                incomplete.add(m);
            }

            List<Map<String, Object>> required = new ArrayList<>();
            for (String key : keys) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", key);
                r.put("label", VariantAttributes.labelForKey(schema, key));
                // A key the item's own schema never declares can only ever be blank,
                // so the fix is to add the field, not to fill it in on each make.
                r.put("inSchema", schemaDeclares(schema, key));
                required.add(r);
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemId", itemId);
            entry.put("itemName", item == null ? null : item.getItemName());
            entry.put("requiredAttributes", required);
            entry.put("usedByProjectTypes", new ArrayList<>(typesByItem.getOrDefault(itemId, Set.of())));
            entry.put("makeCount", makes.size());
            entry.put("incompleteMakes", incomplete);
            // No make at all carries what the basis needs → nothing seeded from this
            // item can ever size, which is a harder failure than a patchy catalogue.
            entry.put("blocking", makes.isEmpty() || incomplete.size() == makes.size());
            items.put(String.valueOf(itemId), entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredAttributeKeys", new ArrayList<>(allKeys));
        out.put("items", items);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basis advice — the same catalogue knowledge, read the other way round
    // ─────────────────────────────────────────────────────────────────────────

    /** Units that make a number a module wattage / an inverter rating rather than something else. */
    private static final Map<String, Set<String>> UNITS_FOR_ATTR = Map.of(
            VariantAttributes.ATTR_MODULE_WATTAGE, Set.of("wp", "w"),
            VariantAttributes.ATTR_INVERTER_KW, Set.of("kw"));

    /** The make-driven basis each attribute key powers. */
    private static final Map<String, String> BASIS_FOR_ATTR = Map.of(
            VariantAttributes.ATTR_MODULE_WATTAGE, LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK,
            VariantAttributes.ATTR_INVERTER_KW, LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW);

    /** Bases worth advising away from — a line already sized off a make needs no advice. */
    private static final Set<String> ADVISABLE_FROM = Set.of(
            LeadBomTemplateItemEntity.BASIS_FIXED,
            LeadBomTemplateItemEntity.BASIS_PER_KW,
            LeadBomTemplateItemEntity.BASIS_PER_STEP,
            LeadBomTemplateItemEntity.BASIS_FROM_SITE_VISIT,
            LeadBomTemplateItemEntity.BASIS_PER_MODULE,
            LeadBomTemplateItemEntity.BASIS_PER_INVERTER);

    /**
     * A make-driven basis this line could be using but isn't, or null.
     *
     * The failure this exists to prevent: a template line linked to the right
     * catalogue item, with the right makes curated, set to "Fixed quantity" and
     * left without a number — so it can never size, and nothing on screen says
     * that the makes already carry everything needed to size it for free.
     *
     * Advisory only, and deliberately narrow: a wrong suggestion here is worse
     * than none, so every gate below must pass. Notably it does NOT require the
     * line to be broken — gating on that would make the advice vanish the moment
     * someone types a stopgap quantity, which is precisely the wrong fix.
     */
    public Map<String, Object> basisAdvice(Long bomItemId, List<Long> allowedVariantIds, String currentBasis) {
        if (bomItemId == null) return null;
        String basis = currentBasis == null ? LeadBomTemplateItemEntity.BASIS_PER_KW : currentBasis;
        if (!ADVISABLE_FROM.contains(basis)) return null; // already make-driven

        BomItemsMasterEntity item = bomItemsMasterRepo.findById(bomItemId).orElse(null);
        if (item == null) return null;
        String schema = item.getVariantAttributes();

        List<BomItemVariantEntity> makes = selectableMakes(bomItemId, allowedVariantIds);
        if (makes.isEmpty()) return null;

        // An item that qualifies on BOTH keys can't be read as a module or an
        // inverter with any confidence, so it gets no advice at all.
        String hit = null;
        for (String key : BASIS_FOR_ATTR.keySet()) {
            if (!qualifies(schema, makes, key)) continue;
            if (hit != null) return null;
            hit = key;
        }
        if (hit == null || basis.equals(BASIS_FOR_ATTR.get(hit))) return null;

        String adviceBasis = BASIS_FOR_ATTR.get(hit);
        String attrLabel = VariantAttributes.labelForKey(schema, hit);
        String basisLabel = basisLabel(adviceBasis);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "MAKE_DRIVEN_BASIS_AVAILABLE");
        // Load-bearing: lets the client drop the advice the instant the row's
        // catalogue item changes, rather than showing it against a new item.
        m.put("itemId", bomItemId);
        m.put("basis", adviceBasis);
        m.put("basisLabel", basisLabel);
        m.put("attrKey", hit);
        m.put("attrLabel", attrLabel);
        m.put("makeCount", makes.size());
        m.put("message", "All " + makes.size() + " make" + (makes.size() == 1 ? "" : "s")
                + " of this item record a " + attrLabel + ", so “" + basisLabel
                + "” can size this line from the capacity with no factor to maintain.");
        return m;
    }

    /**
     * Whether this item's makes can drive the given attribute. The unit gate is
     * what keeps a battery in Ah, a tank in L or a transformer in kVA from being
     * read as an inverter just because its field happens to be called "capacity".
     */
    static boolean qualifies(String schemaJson, List<BomItemVariantEntity> makes, String key) {
        Map<String, Object> field = null;
        for (Map<String, Object> f : VariantAttributes.parseSchema(schemaJson)) {
            if (key.equals(String.valueOf(f.get("key")))) { field = f; break; }
        }
        if (field == null) return false;
        if (!"number".equals(String.valueOf(field.get("type")))) return false;

        Object unit = field.get("unit");
        String u = unit == null ? "" : String.valueOf(unit).trim().toLowerCase();
        if (!UNITS_FOR_ATTR.get(key).contains(u)) return false;

        // EVERY selectable make, not just one: switching the basis on a partial
        // catalogue would leave some makes unable to size, which isn't clean advice.
        for (BomItemVariantEntity v : makes) {
            if (!VariantAttributes.hasUsableNumeric(v.getAttributeValues(), key)) return false;
        }
        return true;
    }

    /** The makes a lead may choose: the curated subset when set, else every active make. */
    private List<BomItemVariantEntity> selectableMakes(Long bomItemId, List<Long> allowedVariantIds) {
        List<BomItemVariantEntity> active =
                bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(bomItemId);
        if (allowedVariantIds == null || allowedVariantIds.isEmpty()) return active;
        List<BomItemVariantEntity> curated = new ArrayList<>();
        for (BomItemVariantEntity v : active) if (allowedVariantIds.contains(v.getId())) curated.add(v);
        return curated.isEmpty() ? active : curated; // a stale curated set is not empty intent
    }

    /** Matches the labels in the admin's basis dropdown, so the server text is the only copy. */
    private static String basisLabel(String basis) {
        if (LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)) return "Module count (from Wp)";
        if (LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)) return "Inverter count (from kW)";
        return basis;
    }

    private boolean schemaDeclares(String schemaJson, String key) {
        for (Map<String, Object> f : VariantAttributes.parseSchema(schemaJson)) {
            if (key.equals(String.valueOf(f.get("key")))) return true;
        }
        return false;
    }
}
