package com.istlgroup.istl_group_crm_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.dto.VariantAttributeDef;
import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadBomTemplateItemRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BomItemsMasterService {

    private final BomItemsMasterRepo bomItemsMasterRepo;
    private final LeadBomTemplateItemRepo leadBomTemplateItemRepo;
    private final BomItemVariantRepo bomItemVariantRepo;
    private final ObjectMapper objectMapper;

    /**
     * Get all active BOM items
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllActiveBomItems() {
        return bomItemsMasterRepo.findByIsActiveTrue().stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Get BOM items by category
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBomItemsByCategory(String category) {
        return bomItemsMasterRepo.findByCategoryAndIsActiveTrue(category).stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Search BOM items (autocomplete)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchBomItems(String searchTerm, String category) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            // Return all items if no search term
            if (category != null && !category.isEmpty()) {
                return getBomItemsByCategory(category);
            }
            return getAllActiveBomItems();
        }

        List<BomItemsMasterEntity> items;
        if (category != null && !category.isEmpty()) {
            items = bomItemsMasterRepo.searchBomItemsByCategory(category, searchTerm.trim());
        } else {
            items = bomItemsMasterRepo.searchBomItems(searchTerm.trim());
        }

        return items.stream()
                .limit(20) // Limit to 20 results for autocomplete
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Get distinct categories
     */
    @Transactional(readOnly = true)
    public List<String> getDistinctCategories() {
        return bomItemsMasterRepo.findDistinctCategories();
    }

    /**
     * Get BOM item by ID
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getBomItemById(Long id) {
        return bomItemsMasterRepo.findById(id)
                .map(this::convertToMap);
    }

    // ── Master-data admin (write side) ──────────────────────────────────────

    /** Admin list incl. inactive items, filtered by category + free-text. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminList(String category, String term) {
        return bomItemsMasterRepo.adminSearch(
                        (category == null || category.isBlank()) ? null : category,
                        term == null ? "" : term.trim())
                .stream().limit(300).map(this::convertToAdminMap).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> createItem(BomItemsMasterEntity req) {
        validateItem(req);
        BomItemsMasterEntity e = new BomItemsMasterEntity();
        applyItem(e, req);
        if (e.getIsActive() == null) e.setIsActive(Boolean.TRUE);
        return convertToAdminMap(bomItemsMasterRepo.save(e));
    }

    @Transactional
    public Map<String, Object> updateItem(Long id, BomItemsMasterEntity req) {
        BomItemsMasterEntity e = bomItemsMasterRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BOM item not found with ID: " + id));
        validateItem(req);
        applyItem(e, req);
        return convertToAdminMap(bomItemsMasterRepo.save(e));
    }

    /** Soft-delete: deactivate, never remove (mirrors the is_active pattern). */
    @Transactional
    public void deactivateItem(Long id) {
        BomItemsMasterEntity e = bomItemsMasterRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BOM item not found with ID: " + id));
        e.setIsActive(false);
        bomItemsMasterRepo.save(e);
    }

    // ── Subgroup BOM view + adopt-into-catalog ──────────────────────────────

    /**
     * The BOM items already used in a subgroup's template BOM (project_type =
     * sub_group_name), each with its catalog-link status. Lets the admin work
     * from an existing BOM (e.g. Rooftop) rather than re-typing items.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> itemsUsedInSubgroup(String subGroup) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (subGroup == null || subGroup.isBlank()) return out;
        for (LeadBomTemplateItemEntity t : leadBomTemplateItemRepo
                .findByProjectTypeAndDeletedAtIsNullOrderBySeqNoAscIdAsc(subGroup)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateItemId", t.getId());
            m.put("scopeActivity", t.getScopeActivity());
            m.put("itemName", t.getItemName());
            m.put("make", t.getMake());
            m.put("specification", t.getSpecification());
            m.put("bomItemId", t.getBomItemId());
            m.put("catalogItem", t.getBomItemId() == null ? null
                    : bomItemsMasterRepo.findById(t.getBomItemId()).map(this::convertToAdminMap).orElse(null));
            out.add(m);
        }
        return out;
    }

    /**
     * Bring a template BOM line into the catalog: reuse an existing item with the
     * same name, else create one from the line, then link the line to it. Seeds a
     * first make from the line's make text if the item has none. Idempotent — a
     * line that's already linked just returns its item.
     */
    @Transactional
    public Map<String, Object> adoptFromTemplateLine(Long templateItemId) {
        LeadBomTemplateItemEntity t = leadBomTemplateItemRepo.findById(templateItemId)
                .orElseThrow(() -> new IllegalArgumentException("Template line not found: " + templateItemId));
        if (t.getItemName() == null || t.getItemName().isBlank()) {
            throw new IllegalArgumentException("Template line has no item name to adopt");
        }

        BomItemsMasterEntity item;
        if (t.getBomItemId() != null) {
            item = bomItemsMasterRepo.findById(t.getBomItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Linked catalog item is missing"));
        } else {
            item = bomItemsMasterRepo.findFirstByItemNameIgnoreCase(t.getItemName().trim())
                    .orElseGet(() -> {
                        BomItemsMasterEntity e = new BomItemsMasterEntity();
                        e.setCategory((t.getCategory() != null && !t.getCategory().isBlank())
                                ? t.getCategory().trim() : "COMMON");
                        e.setItemName(t.getItemName().trim());
                        e.setSpecification(t.getSpecification());
                        if (t.getUnit() != null && !t.getUnit().isBlank()) e.setDefaultUnit(t.getUnit().trim());
                        e.setIsActive(Boolean.TRUE);
                        return bomItemsMasterRepo.save(e);
                    });
            t.setBomItemId(item.getId());
            leadBomTemplateItemRepo.save(t);
        }

        // Seed a first make from the line's make text if the item has none yet.
        if (t.getMake() != null && !t.getMake().isBlank()
                && bomItemVariantRepo.findByBomItemIdOrderByMakeAsc(item.getId()).isEmpty()) {
            BomItemVariantEntity v = new BomItemVariantEntity();
            v.setBomItemId(item.getId());
            v.setMake(t.getMake().trim());
            v.setIsActive(Boolean.TRUE);
            bomItemVariantRepo.save(v);
        }
        return convertToAdminMap(item);
    }

    private void validateItem(BomItemsMasterEntity req) {
        if (req.getCategory() == null || req.getCategory().isBlank()) {
            throw new IllegalArgumentException("Category is required");
        }
        if (req.getItemName() == null || req.getItemName().isBlank()) {
            throw new IllegalArgumentException("Item name is required");
        }
    }

    private void applyItem(BomItemsMasterEntity e, BomItemsMasterEntity req) {
        e.setCategory(req.getCategory().trim());
        e.setItemName(req.getItemName().trim());
        e.setDescription(req.getDescription());
        e.setSpecification(req.getSpecification());
        if (req.getDefaultUnit() != null && !req.getDefaultUnit().isBlank()) {
            e.setDefaultUnit(req.getDefaultUnit().trim());
        }
        if (req.getDefaultTaxPercent() != null) e.setDefaultTaxPercent(req.getDefaultTaxPercent());
        e.setHsnCode(req.getHsnCode());
        e.setVariantAttributes(normalizeVariantAttributes(req.getVariantAttributes()));
        if (req.getIsActive() != null) e.setIsActive(req.getIsActive());
        // make_brand is a frozen shadow column — makes now live in bom_item_variants,
        // managed via the makes modal. Left untouched here so edits never clobber it.
    }

    /**
     * Validate + canonicalize the variant-attribute schema JSON. Returns a clean
     * JSON string, or null when the item has no schema (blank/empty → legacy item,
     * kept working as-is). Throws IllegalArgumentException (→ 400) on any bad field.
     *
     * Rules: every field needs a non-blank key + label; keys unique (case-insensitive);
     * type ∈ {text,dropdown,number}; dropdown needs ≥1 non-blank option; unit only
     * kept for number. Re-serialized NON_NULL so it round-trips byte-stable on reload.
     */
    String normalizeVariantAttributes(String raw) { // package-private for unit tests
        if (raw == null || raw.isBlank()) return null;
        List<VariantAttributeDef> defs;
        try {
            defs = objectMapper.readValue(raw, new TypeReference<List<VariantAttributeDef>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Variant attributes must be a JSON array of field definitions");
        }
        if (defs == null || defs.isEmpty()) return null;

        List<VariantAttributeDef> norm = new ArrayList<>(defs.size());
        Set<String> seenKeys = new HashSet<>();
        for (VariantAttributeDef d : defs) {
            String key = d.getKey() == null ? "" : d.getKey().trim();
            String label = d.getLabel() == null ? "" : d.getLabel().trim();
            if (key.isEmpty()) throw new IllegalArgumentException("Every variant attribute needs a key");
            if (label.isEmpty()) throw new IllegalArgumentException("Attribute '" + key + "' needs a label");
            if (!seenKeys.add(key.toLowerCase())) {
                throw new IllegalArgumentException("Duplicate variant attribute key: " + key);
            }
            String type = d.getType() == null ? "text" : d.getType().trim().toLowerCase();
            if (!type.equals("text") && !type.equals("dropdown") && !type.equals("number")) {
                throw new IllegalArgumentException("Attribute '" + label + "' has an invalid type: " + type);
            }

            VariantAttributeDef out = new VariantAttributeDef();
            out.setKey(key);
            out.setLabel(label);
            out.setType(type);
            out.setRequired(Boolean.TRUE.equals(d.getRequired()) ? Boolean.TRUE : Boolean.FALSE);
            if (type.equals("dropdown")) {
                List<String> opts = new ArrayList<>();
                if (d.getOptions() != null) {
                    for (String o : d.getOptions()) {
                        if (o != null && !o.trim().isEmpty()) opts.add(o.trim());
                    }
                }
                if (opts.isEmpty()) {
                    throw new IllegalArgumentException("Dropdown attribute '" + label + "' needs at least one option");
                }
                out.setOptions(opts);
            } else if (type.equals("number")) {
                String unit = d.getUnit() == null ? null : d.getUnit().trim();
                out.setUnit((unit == null || unit.isEmpty()) ? null : unit);
            }
            norm.add(out);
        }
        try {
            return objectMapper.writeValueAsString(norm);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize variant attributes");
        }
    }

    /**
     * Convert entity to map for API response
     */
    private Map<String, Object> convertToMap(BomItemsMasterEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("category", entity.getCategory());
        map.put("itemName", entity.getItemName());
        map.put("description", entity.getDescription());
        map.put("specification", entity.getSpecification());
        map.put("defaultUnit", entity.getDefaultUnit());
        map.put("defaultTaxPercent", entity.getDefaultTaxPercent());
        map.put("makeBrand", entity.getMakeBrand());
        map.put("hsnCode", entity.getHsnCode());
        map.put("variantAttributes", entity.getVariantAttributes());
        return map;
    }

    /** Admin map = the read shape plus is_active (which consumers of the GETs don't need). */
    private Map<String, Object> convertToAdminMap(BomItemsMasterEntity entity) {
        Map<String, Object> map = convertToMap(entity);
        map.put("isActive", entity.getIsActive());
        return map;
    }
}