package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slice 1 — variant-attribute schema. Pure unit tests: they call the service's
 * normalize method directly (no repos, no Spring context, no Mockito), so they
 * exercise the real parse → validate → normalize → serialize path. Proves the
 * schema round-trips on save (the stop condition guarding Slice 2) and that bad
 * field defs are rejected.
 */
class BomItemsMasterVariantAttributesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // normalizeVariantAttributes never touches the repos, so nulls are safe here.
    private final BomItemsMasterService svc = new BomItemsMasterService(null, null, null, mapper);

    @Test
    void schemaRoundTrips() throws Exception {
        String schema = "["
                + "{\"key\":\"wattage\",\"label\":\"Wattage\",\"type\":\"number\",\"unit\":\"Wp\",\"required\":true},"
                + "{\"key\":\"cell_type\",\"label\":\"Cell Type\",\"type\":\"dropdown\",\"options\":[\"Mono PERC\",\"TOPCon\"],\"required\":false},"
                + "{\"key\":\"notes\",\"label\":\"Notes\",\"type\":\"text\",\"required\":false}"
                + "]";

        String stored = svc.normalizeVariantAttributes(schema);
        assertNotNull(stored);

        List<Map<String, Object>> defs = mapper.readValue(stored, new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(3, defs.size());
        assertEquals("wattage", defs.get(0).get("key"));
        assertEquals("number", defs.get(0).get("type"));
        assertEquals("Wp", defs.get(0).get("unit"));            // unit kept on number
        assertEquals(Boolean.TRUE, defs.get(0).get("required"));
        assertEquals(List.of("Mono PERC", "TOPCon"), defs.get(1).get("options")); // options kept on dropdown
        assertFalse(defs.get(2).containsKey("options"));         // text carries no facets
        assertFalse(defs.get(2).containsKey("unit"));

        // Byte-stable: re-normalizing the stored output yields the identical string.
        assertEquals(stored, svc.normalizeVariantAttributes(stored));
    }

    @Test
    void unitStrippedFromNonNumberAndOptionsFromNonDropdown() throws Exception {
        String schema = "[{\"key\":\"make\",\"label\":\"Make\",\"type\":\"text\",\"unit\":\"kg\",\"options\":[\"A\"],\"required\":false}]";
        String stored = svc.normalizeVariantAttributes(schema);
        List<Map<String, Object>> defs = mapper.readValue(stored, new TypeReference<List<Map<String, Object>>>() {});
        assertFalse(defs.get(0).containsKey("unit"));
        assertFalse(defs.get(0).containsKey("options"));
    }

    @Test
    void emptyOrBlankSchemaBecomesNull() {
        assertNull(svc.normalizeVariantAttributes(null));
        assertNull(svc.normalizeVariantAttributes("   "));
        assertNull(svc.normalizeVariantAttributes("[]"));
    }

    @Test
    void dropdownWithoutOptionsRejected() {
        String bad = "[{\"key\":\"face\",\"label\":\"Face\",\"type\":\"dropdown\",\"options\":[]}]";
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeVariantAttributes(bad));
    }

    @Test
    void duplicateKeysRejected() {
        String bad = "[{\"key\":\"w\",\"label\":\"W1\",\"type\":\"text\"},{\"key\":\"w\",\"label\":\"W2\",\"type\":\"text\"}]";
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeVariantAttributes(bad));
    }

    @Test
    void blankLabelRejected() {
        String bad = "[{\"key\":\"w\",\"label\":\"  \",\"type\":\"text\"}]";
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeVariantAttributes(bad));
    }

    @Test
    void invalidTypeRejected() {
        String bad = "[{\"key\":\"w\",\"label\":\"W\",\"type\":\"color\"}]";
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeVariantAttributes(bad));
    }

    @Test
    void malformedJsonRejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.normalizeVariantAttributes("not json"));
    }
}
