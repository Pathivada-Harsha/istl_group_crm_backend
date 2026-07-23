package com.istlgroup.istl_group_crm_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.dto.VariantAttributeDef;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Variant (make/model) management for bom_items_master items. Write-side is
 * greenfield — the item master itself stays read-only. Deactivate never
 * hard-deletes, mirroring the item is_active pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BomItemVariantService {

    private final BomItemVariantRepo variantRepo;
    private final BomItemsMasterRepo itemRepo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByItem(Long itemId, boolean activeOnly) {
        List<BomItemVariantEntity> rows = activeOnly
                ? variantRepo.findByBomItemIdAndIsActiveTrueOrderByMakeAsc(itemId)
                : variantRepo.findByBomItemIdOrderByMakeAsc(itemId);
        return rows.stream().map(this::convertToMap).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> create(Long itemId, BomItemVariantEntity req) {
        BomItemsMasterEntity item = itemRepo.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("BOM item not found with ID: " + itemId));
        BomItemVariantEntity v = new BomItemVariantEntity();
        v.setBomItemId(itemId);
        v.setMake(trimToNull(req.getMake()));
        v.setModel(trimToNull(req.getModel()));
        v.setDescription(req.getDescription());
        v.setAttributeValues(normalizeAttributeValues(item, req.getAttributeValues()));
        v.setIsActive(req.getIsActive() == null ? Boolean.TRUE : req.getIsActive());
        return convertToMap(variantRepo.save(v));
    }

    @Transactional
    public Map<String, Object> update(Long id, BomItemVariantEntity req) {
        BomItemVariantEntity v = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found with ID: " + id));
        // Partial update — only overwrite fields the client actually sent.
        if (req.getMake() != null) v.setMake(trimToNull(req.getMake()));
        if (req.getModel() != null) v.setModel(trimToNull(req.getModel()));
        if (req.getDescription() != null) v.setDescription(req.getDescription());
        if (req.getAttributeValues() != null) {
            BomItemsMasterEntity item = itemRepo.findById(v.getBomItemId()).orElse(null);
            v.setAttributeValues(normalizeAttributeValues(item, req.getAttributeValues()));
        }
        if (req.getIsActive() != null) v.setIsActive(req.getIsActive());
        return convertToMap(variantRepo.save(v));
    }

    /** Soft-delete: deactivate, never remove. */
    @Transactional
    public void deactivate(Long id) {
        BomItemVariantEntity v = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found with ID: " + id));
        v.setIsActive(false);
        variantRepo.save(v);
    }

    private Map<String, Object> convertToMap(BomItemVariantEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("bomItemId", e.getBomItemId());
        m.put("make", e.getMake());
        m.put("model", e.getModel());
        m.put("description", e.getDescription());
        m.put("attributeValues", e.getAttributeValues());
        m.put("isActive", e.getIsActive());
        return m;
    }

    /**
     * Validate + canonicalize a variant's attribute values against its parent
     * item's schema. Returns a clean JSON object string (values in schema order,
     * blanks dropped), or null when the item has no schema or nothing is filled.
     * Throws IllegalArgumentException (→ 404 via the controller's handler) on any
     * unknown key, missing required value, non-numeric number, or off-list dropdown.
     */
    String normalizeAttributeValues(BomItemsMasterEntity item, String rawValues) { // package-private for unit tests
        List<VariantAttributeDef> schema = parseSchema(item == null ? null : item.getVariantAttributes());
        if (schema.isEmpty()) return null; // no schema → item keeps the plain make/model form

        Map<String, String> incoming = parseValueMap(rawValues);
        Set<String> known = schema.stream().map(VariantAttributeDef::getKey).collect(Collectors.toSet());
        for (String k : incoming.keySet()) {
            if (!known.contains(k)) throw new IllegalArgumentException("Unknown variant attribute: " + k);
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (VariantAttributeDef f : schema) {
            String raw = incoming.get(f.getKey());
            String val = raw == null ? "" : raw.trim();
            if (val.isEmpty()) {
                if (Boolean.TRUE.equals(f.getRequired())) {
                    throw new IllegalArgumentException("'" + f.getLabel() + "' is required");
                }
                continue;
            }
            String type = f.getType() == null ? "text" : f.getType();
            if ("number".equals(type)) {
                try {
                    Double.parseDouble(val);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("'" + f.getLabel() + "' must be a number");
                }
            } else if ("dropdown".equals(type)) {
                List<String> opts = f.getOptions() == null ? List.of() : f.getOptions();
                if (!opts.contains(val)) {
                    throw new IllegalArgumentException("'" + f.getLabel() + "' must be one of: " + String.join(", ", opts));
                }
            }
            out.put(f.getKey(), val);
        }
        if (out.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize variant attribute values");
        }
    }

    /** Parse the item's schema JSON; a null/blank/malformed schema means "no schema". */
    private List<VariantAttributeDef> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<List<VariantAttributeDef>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** Parse the incoming values as a flat JSON object; every value coerced to a string. */
    private Map<String, String> parseValueMap(String rawValues) {
        if (rawValues == null || rawValues.isBlank()) return Map.of();
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(rawValues, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Variant attribute values must be a JSON object");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            m.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return m;
    }

    private String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
