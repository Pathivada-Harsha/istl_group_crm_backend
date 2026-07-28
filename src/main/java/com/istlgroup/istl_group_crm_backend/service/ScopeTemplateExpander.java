package com.istlgroup.istl_group_crm_backend.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.TemplateLineVariantEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadScopeTemplateRepo;
import com.istlgroup.istl_group_crm_backend.repo.TemplateLineVariantRepo;
import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;

/**
 * Expands a standardised scope/BOM template (keyed by {@code project_type} =
 * sub-group name) into pre-fill scope lines + BOM lines, reusing
 * {@link LeadSuggestionEngine} for the eight capacity/basis rules — the engine is
 * the single source of the sizing maths and is never copied.
 *
 * This is the reuse seam between the Leads-Enquire flow (LeadScopeService) and the
 * Projects flow (ProjectScopeSuggestionService): both feed it the same neutral
 * inputs ({@code projectType}, {@link CapacityInfo}, siteVisit map) and get back
 * the same response shape, so the frontend can share rendering code. Mining a
 * similar past job (lead-only) stays in LeadScopeService; this component covers the
 * TEMPLATE and NONE paths that both callers share.
 */
@Component
public class ScopeTemplateExpander {

    @Autowired private LeadSuggestionEngine suggestionEngine;
    @Autowired private LeadBomTemplateItemRepo leadBomTemplateItemRepo;
    @Autowired private LeadScopeTemplateItemRepo leadScopeTemplateItemRepo;
    @Autowired private LeadScopeTemplateRepo leadScopeTemplateRepo;
    @Autowired private BomItemVariantRepo bomItemVariantRepo;
    @Autowired private TemplateLineVariantRepo templateLineVariantRepo;
    @Autowired private BomItemsMasterRepo bomItemsMasterRepo;
    @Autowired private ObjectMapper objectMapper;

    /** Result of a template expansion — scope lines + BOM lines + provenance. */
    public static class TemplateExpansion {
        public String source = "NONE";              // TEMPLATE | NONE
        public boolean hasTemplate = false;
        public List<Map<String, Object>> scopeItems = new ArrayList<>();
        public List<Map<String, Object>> bomLines = new ArrayList<>();
        public Long templateId;                     // active template header id (provenance)
        public Integer templateVersion;             // active template version (provenance)
    }

    /**
     * Expand the active template for {@code projectType} at the given capacity.
     * Appends any sizing warnings ({@code W_*}) to {@code warnings} in place; when
     * no template exists, appends {@code W_NO_TEMPLATE_NO_HISTORY} and returns an
     * empty (source=NONE) result — never throws. With an unusable capacity the BOM
     * lines come back with {@code quantity=null}, {@code autoQty=true} and the basis
     * preserved (the engine emits {@code W_NEEDS_CAPACITY}).
     */
    public TemplateExpansion expand(String projectType, CapacityInfo cap, Map<String, String> siteVisit,
                                    boolean wantScope, boolean wantBom, List<Map<String, Object>> warnings) {
        // Resolve the ACTIVE template first, then take ITS lines. Querying lines by
        // project_type instead would combine every template for that type — with
        // two active templates a project comes out seeded at 200%, and every
        // subsequent save on its scope tab is rejected with no visible cause.
        LeadScopeTemplateEntity header = projectType == null ? null
                : leadScopeTemplateRepo.findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);

        List<LeadBomTemplateItemEntity> templateBom = header == null ? List.of()
                : leadBomTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(header.getId());
        List<LeadScopeTemplateItemEntity> templateScope = header == null ? List.of()
                : leadScopeTemplateItemRepo.findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(header.getId());

        TemplateExpansion out = new TemplateExpansion();
        out.hasTemplate = !templateBom.isEmpty() || !templateScope.isEmpty();

        if (!out.hasTemplate) {
            out.source = "NONE";
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("code", LeadSuggestionEngine.W_NO_TEMPLATE_NO_HISTORY);
            w.put("message", "No template exists for this project type yet.");
            warnings.add(w);
            return out;
        }

        out.source = "TEMPLATE";
        out.templateId = header.getId();
        out.templateVersion = header.getVersion();

        if (wantScope) out.scopeItems = resolveTemplateScope(templateScope, templateBom);
        if (wantBom) {
            Map<Long, BigDecimal> driverAttrs = resolveDriverAttrs(templateBom);
            out.bomLines = suggestionEngine.expandTemplateBom(templateBom, cap, siteVisit, driverAttrs, warnings);
            attachVariantChoices(out.bomLines, templateBom);
        }
        return out;
    }

    /** Lightweight "is there a template?" probe for the Suggest button label. */
    public Map<String, Object> templateInfo(String projectType) {
        LeadScopeTemplateEntity tpl = projectType == null ? null
                : leadScopeTemplateRepo.findFirstByProjectTypeAndIsActiveTrueAndDeletedAtIsNull(projectType).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectType", projectType);
        data.put("hasTemplate", tpl != null);
        data.put("templateName", tpl != null ? tpl.getName() : null);
        data.put("templateId", tpl != null ? tpl.getId() : null);
        data.put("templateVersion", tpl != null ? tpl.getVersion() : null);
        return data;
    }

    /**
     * The active makes for a catalog item, as choice maps (variantId / make / model /
     * spec / attributeValues). Used by the reload path so a saved project BOM line
     * repopulates its make dropdown — the single source for variant mapping.
     */
    public List<Map<String, Object>> variantChoicesFor(Long bomItemId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (bomItemId == null) return out;
        String schemaJson = variantSchemaJson(bomItemId);
        for (BomItemVariantEntity v : bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(bomItemId)) {
            out.add(variantChoiceMap(v, schemaJson));
        }
        return out;
    }

    // ── Template → scope/BOM helpers (mirror LeadScopeService, engine-neutral) ──

    /**
     * Scope items for a template: the explicit scope lines if any, otherwise the
     * distinct scope activities implied by the BOM lines' {@code scope_activity}.
     * Either way the result carries a weight on every line (see
     * {@link #ensureWeights}).
     */
    private List<Map<String, Object>> resolveTemplateScope(
            List<LeadScopeTemplateItemEntity> templateScope,
            List<LeadBomTemplateItemEntity> templateBom) {
        List<Map<String, Object>> scope = suggestionEngine.expandTemplateScope(templateScope);
        if (!scope.isEmpty()) { ensureWeights(scope); return scope; }

        List<Map<String, Object>> derived = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LeadBomTemplateItemEntity b : templateBom) {
            String act = b.getScopeActivity();
            if (act == null || act.isBlank()) continue;
            String key = act.trim().toLowerCase();
            if (!seen.add(key)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activity", act.trim());
            m.put("specification", null);
            m.put("unit", null);
            m.put("quantity", null);
            m.put("category", b.getCategory());
            // Derived lines have no template record, so no weight to inherit —
            // ensureWeights gives the whole set an even split below.
            m.put("weightPct", null);
            derived.add(m);
        }
        ensureWeights(derived);
        return derived;
    }

    /** Weights are stored to six decimals; see {@code project_phases.weight_pct}. */
    private static final int WEIGHT_SCALE = 6;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * Guarantees every generated scope line carries a weight, so a project is never
     * generated with one missing.
     *
     * When the template weights every line, they are copied through untouched — no
     * recalculation, no precision loss. Otherwise (a template that predates the
     * feature, or a scope derived from BOM activities) the whole set gets an even
     * split totalling exactly 100, with the last line absorbing the remainder.
     */
    private void ensureWeights(List<Map<String, Object>> scope) {
        if (scope.isEmpty()) return;

        boolean complete = true;
        for (Map<String, Object> m : scope) {
            Object w = m.get("weightPct");
            if (!(w instanceof BigDecimal) || ((BigDecimal) w).signum() <= 0) { complete = false; break; }
        }
        if (complete) return;

        BigDecimal each = ONE_HUNDRED.divide(BigDecimal.valueOf(scope.size()), WEIGHT_SCALE, java.math.RoundingMode.HALF_UP);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < scope.size(); i++) {
            BigDecimal w = (i == scope.size() - 1) ? ONE_HUNDRED.subtract(running) : each;
            scope.get(i).put("weightPct", w.setScale(WEIGHT_SCALE, java.math.RoundingMode.HALF_UP));
            running = running.add(w);
        }
    }

    /**
     * Attach each suggested BOM line's makes + default, correlating the engine
     * output to its template line by index (expandTemplateBom is 1:1 and
     * order-preserving). Additive only — the engine is not modified.
     */
    private void attachVariantChoices(List<Map<String, Object>> bomLines,
                                      List<LeadBomTemplateItemEntity> templateBom) {
        int n = Math.min(bomLines.size(), templateBom.size());
        for (int i = 0; i < n; i++) {
            LeadBomTemplateItemEntity tl = templateBom.get(i);
            if (tl.getBomItemId() == null || tl.getId() == null) continue;

            List<BomItemVariantEntity> active =
                    bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(tl.getBomItemId());
            if (active.isEmpty()) continue;

            String schemaJson = variantSchemaJson(tl.getBomItemId());
            List<Map<String, Object>> variants = new ArrayList<>();
            for (BomItemVariantEntity v : active) variants.add(variantChoiceMap(v, schemaJson));

            Long defId = defaultVariantId(tl, active);

            Map<String, Object> line = bomLines.get(i);
            line.put("bomItemId", tl.getBomItemId());
            line.put("variants", variants);
            line.put("defaultVariantId", defId);
            line.put("variantId", defId);
        }
    }

    private Map<String, Object> variantChoiceMap(BomItemVariantEntity v, String schemaJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", v.getId());
        m.put("make", v.getMake());
        m.put("model", v.getModel());
        m.put("description", v.getDescription());
        Map<String, Object> attrs = parseAttrs(v.getAttributeValues());
        m.put("attributeValues", attrs);
        m.put("spec", composeSpec(schemaJson, attrs));
        return m;
    }

    private String variantSchemaJson(Long bomItemId) {
        if (bomItemId == null) return null;
        return bomItemsMasterRepo.findById(bomItemId)
                .map(BomItemsMasterEntity::getVariantAttributes).orElse(null);
    }

    private String composeSpec(String schemaJson, Map<String, Object> values) {
        if (schemaJson == null || schemaJson.isBlank() || values == null || values.isEmpty()) return null;
        List<Map<String, Object>> schema;
        try {
            schema = objectMapper.readValue(schemaJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> f : schema) {
            Object keyO = f.get("key");
            if (keyO == null) continue;
            Object val = values.get(String.valueOf(keyO));
            if (val == null || String.valueOf(val).isBlank()) continue;
            Object unit = f.get("unit");
            String u = unit == null ? "" : String.valueOf(unit).trim();
            parts.add(String.valueOf(val) + (u.isEmpty() ? "" : " " + u));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * The numeric driver each template line's default make contributes: module
     * wattage (Wp) for PER_WATT_PEAK lines, inverter capacity (kW) for
     * PER_INVERTER_KW lines. Keyed by template-line id; absent when unresolved.
     */
    private Map<Long, BigDecimal> resolveDriverAttrs(List<LeadBomTemplateItemEntity> templateBom) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (LeadBomTemplateItemEntity t : templateBom) {
            String key = attrKeyForBasis(t.getBasis());
            if (key == null || t.getBomItemId() == null || t.getId() == null) continue;
            List<BomItemVariantEntity> active =
                    bomItemVariantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(t.getBomItemId());
            Long defId = defaultVariantId(t, active);
            if (defId == null) continue;
            BomItemVariantEntity v = active.stream().filter(x -> x.getId().equals(defId)).findFirst().orElse(null);
            BigDecimal attr = numericAttr(v, key);
            if (attr != null) out.put(t.getId(), attr);
        }
        return out;
    }

    /** Curated default make if present and still active, else the first active make. */
    private Long defaultVariantId(LeadBomTemplateItemEntity tl, List<BomItemVariantEntity> active) {
        if (active == null || active.isEmpty()) return null;
        Long defId = null;
        for (TemplateLineVariantEntity x : templateLineVariantRepo.findByTemplateItemId(tl.getId())) {
            if (Boolean.TRUE.equals(x.getIsDefault())) { defId = x.getVariantId(); break; }
        }
        if (defId != null) {
            for (BomItemVariantEntity v : active) if (v.getId().equals(defId)) return defId;
        }
        return active.get(0).getId();
    }

    private String attrKeyForBasis(String basis) {
        if (LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)) return "wattage";
        if (LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)) return "capacity";
        return null;
    }

    private Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal numericAttr(BomItemVariantEntity v, String key) {
        if (v == null || key == null) return null;
        Map<String, Object> attrs = parseAttrs(v.getAttributeValues());
        if (attrs == null) return null;
        Object raw = attrs.get(key);
        if (raw == null) return null;
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
