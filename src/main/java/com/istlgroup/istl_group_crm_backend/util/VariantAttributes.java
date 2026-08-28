package com.istlgroup.istl_group_crm_backend.util;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;

/**
 * The one place that knows how a make-driven basis reaches into a catalogue
 * make's stored attributes.
 *
 * A make's numbers live as strings in {@code bom_item_variants.attribute_values}
 * (a JSON object keyed by the parent item's {@code variant_attributes} schema
 * keys), so "does this make carry a wattage?" is a JSON lookup plus a numeric
 * parse — asked by the suggestion path, by template validation, and by the
 * catalogue health check. Keeping the key mapping and the parse here is what
 * lets those three agree on when a line can be sized.
 */
public final class VariantAttributes {

    /** Schema key a PER_WATT_PEAK line reads (module wattage, Wp). */
    public static final String ATTR_MODULE_WATTAGE = "wattage";
    /** Schema key a PER_INVERTER_KW line reads (inverter rating, kW). */
    public static final String ATTR_INVERTER_KW = "capacity";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariantAttributes() { }

    /** The attribute key a basis needs from the selected make; null when it needs none. */
    public static String attrKeyForBasis(String basis) {
        if (LeadBomTemplateItemEntity.BASIS_PER_WATT_PEAK.equals(basis)) return ATTR_MODULE_WATTAGE;
        if (LeadBomTemplateItemEntity.BASIS_PER_INVERTER_KW.equals(basis)) return ATTR_INVERTER_KW;
        return null;
    }

    /** Parse an {@code attribute_values} JSON object to a flat map; null on blank/malformed. */
    public static Map<String, Object> parseValues(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse a {@code variant_attributes} schema array; empty list on blank/malformed. */
    public static List<Map<String, Object>> parseSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Numeric value of one attribute on a make; null when absent, blank or non-numeric. */
    public static BigDecimal numeric(String attributeValuesJson, String key) {
        if (key == null) return null;
        Map<String, Object> attrs = parseValues(attributeValuesJson);
        if (attrs == null) return null;
        Object raw = attrs.get(key);
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when this make carries a usable (positive numeric) value for the key. */
    public static boolean hasUsableNumeric(String attributeValuesJson, String key) {
        BigDecimal v = numeric(attributeValuesJson, key);
        return v != null && v.signum() > 0;
    }

    /**
     * How to name an attribute to a user: the item schema's own label when the
     * catalogue defines one, else the raw key. Used so a "this make has no X"
     * message says what the estimator actually sees in the catalogue.
     */
    public static String labelForKey(String schemaJson, String key) {
        if (key == null) return null;
        for (Map<String, Object> f : parseSchema(schemaJson)) {
            if (!key.equals(String.valueOf(f.get("key")))) continue;
            Object label = f.get("label");
            String l = label == null ? "" : String.valueOf(label).trim();
            Object unit = f.get("unit");
            String u = unit == null ? "" : String.valueOf(unit).trim();
            if (!l.isEmpty()) return u.isEmpty() ? l : l + " (" + u + ")";
            break;
        }
        return key;
    }
}
