package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;

/**
 * Slice 2 — a variant fills the item's schema. Pure unit tests: they call the
 * service's value-normalize method directly (no repos, no Spring context, no
 * Mockito). Proves values round-trip against the schema and that bad values are
 * rejected.
 */
class BomItemVariantAttributeValuesTest {

    private static final String SCHEMA = "["
            + "{\"key\":\"wattage\",\"label\":\"Wattage\",\"type\":\"number\",\"unit\":\"Wp\",\"required\":true},"
            + "{\"key\":\"cell_type\",\"label\":\"Cell Type\",\"type\":\"dropdown\",\"options\":[\"Mono PERC\",\"TOPCon\"],\"required\":false},"
            + "{\"key\":\"notes\",\"label\":\"Notes\",\"type\":\"text\",\"required\":false}"
            + "]";

    private final ObjectMapper mapper = new ObjectMapper();
    // normalizeAttributeValues never touches the repos, so nulls are safe here.
    private final BomItemVariantService svc = new BomItemVariantService(null, null, mapper);

    private BomItemsMasterEntity item(String schemaJson) {
        BomItemsMasterEntity e = new BomItemsMasterEntity();
        e.setId(1L);
        e.setVariantAttributes(schemaJson);
        return e;
    }

    @Test
    void valuesRoundTrip() throws Exception {
        String values = "{\"wattage\":\"540\",\"cell_type\":\"Mono PERC\",\"notes\":\"tier-1\"}";
        String stored = svc.normalizeAttributeValues(item(SCHEMA), values);

        Map<String, Object> back = mapper.readValue(stored, new TypeReference<Map<String, Object>>() {});
        assertEquals("540", back.get("wattage"));
        assertEquals("Mono PERC", back.get("cell_type"));
        assertEquals("tier-1", back.get("notes"));

        // Byte-stable: re-normalizing the stored output yields the identical string.
        assertEquals(stored, svc.normalizeAttributeValues(item(SCHEMA), stored));
    }

    @Test
    void blankOptionalValuesAreOmitted() throws Exception {
        String stored = svc.normalizeAttributeValues(item(SCHEMA), "{\"wattage\":\"500\",\"cell_type\":\"\"}");
        Map<String, Object> back = mapper.readValue(stored, new TypeReference<Map<String, Object>>() {});
        assertEquals(1, back.size());
        assertEquals("500", back.get("wattage"));
    }

    @Test
    void noSchemaBecomesNull() {
        assertNull(svc.normalizeAttributeValues(item(null), "{\"wattage\":\"540\"}"));
        assertNull(svc.normalizeAttributeValues(null, "{\"wattage\":\"540\"}"));
    }

    @Test
    void unknownKeyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.normalizeAttributeValues(item(SCHEMA), "{\"wattage\":\"540\",\"colour\":\"blue\"}"));
    }

    @Test
    void missingRequiredRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.normalizeAttributeValues(item(SCHEMA), "{\"cell_type\":\"TOPCon\"}"));
    }

    @Test
    void nonNumericNumberRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.normalizeAttributeValues(item(SCHEMA), "{\"wattage\":\"lots\"}"));
    }

    @Test
    void offListDropdownRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.normalizeAttributeValues(item(SCHEMA), "{\"wattage\":\"540\",\"cell_type\":\"Polycrystalline\"}"));
    }

    @Test
    void malformedValuesJsonRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.normalizeAttributeValues(item(SCHEMA), "not-json"));
    }
}
