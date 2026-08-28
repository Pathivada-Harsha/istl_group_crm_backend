package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.util.VariantAttributes;

/**
 * The gates on "this item's makes can drive a make-driven basis".
 *
 * Suggesting the wrong basis is worse than suggesting none — the admin would set
 * it, save, and get blank quantities from a line that used to at least be
 * honestly broken. So every gate is pinned here: schema type, unit, and the
 * requirement that EVERY selectable make carries the number, not just one.
 */
class BomCatalogueHealthServiceTest {

    private static final String MODULE_SCHEMA =
            "[{\"key\":\"wattage\",\"type\":\"number\",\"unit\":\"Wp\",\"label\":\"Wattage\"}]";
    private static final String INVERTER_SCHEMA =
            "[{\"key\":\"capacity\",\"type\":\"number\",\"unit\":\"kW\",\"label\":\"Capacity\"}]";

    private static BomItemVariantEntity make(String json) {
        BomItemVariantEntity v = new BomItemVariantEntity();
        v.setAttributeValues(json);
        return v;
    }

    private static final String W = VariantAttributes.ATTR_MODULE_WATTAGE;
    private static final String C = VariantAttributes.ATTR_INVERTER_KW;

    @Test
    void everyMakeCarryingTheNumberQualifies() {
        assertTrue(BomCatalogueHealthService.qualifies(MODULE_SCHEMA, List.of(
                make("{\"wattage\":\"590\"}"), make("{\"wattage\":\"600\"}")), W));
    }

    /** One gap is enough: switching the basis would leave that make unable to size. */
    @Test
    void oneMakeMissingTheNumberDisqualifies() {
        assertFalse(BomCatalogueHealthService.qualifies(MODULE_SCHEMA, List.of(
                make("{\"wattage\":\"590\"}"), make("{\"technology\":\"TOPCon\"}")), W));
    }

    @Test
    void aNonNumericOrZeroValueDisqualifies() {
        assertFalse(BomCatalogueHealthService.qualifies(MODULE_SCHEMA, List.of(make("{\"wattage\":\"n/a\"}")), W));
        assertFalse(BomCatalogueHealthService.qualifies(MODULE_SCHEMA, List.of(make("{\"wattage\":\"0\"}")), W));
    }

    /**
     * The unit gate — this is what stops a battery in Ah, a tank in L or a
     * transformer in kVA being read as an inverter because its field happens to
     * be called "capacity".
     */
    @Test
    void aCapacityInTheWrongUnitDisqualifies() {
        String batterySchema = "[{\"key\":\"capacity\",\"type\":\"number\",\"unit\":\"Ah\",\"label\":\"Capacity\"}]";
        assertFalse(BomCatalogueHealthService.qualifies(batterySchema, List.of(make("{\"capacity\":\"200\"}")), C));

        String kvaSchema = "[{\"key\":\"capacity\",\"type\":\"number\",\"unit\":\"kVA\",\"label\":\"Rating\"}]";
        assertFalse(BomCatalogueHealthService.qualifies(kvaSchema, List.of(make("{\"capacity\":\"100\"}")), C));
    }

    /** A bare number with no unit is not evidence of what it measures. */
    @Test
    void aMissingUnitDisqualifies() {
        String noUnit = "[{\"key\":\"capacity\",\"type\":\"number\",\"label\":\"Capacity\"}]";
        assertFalse(BomCatalogueHealthService.qualifies(noUnit, List.of(make("{\"capacity\":\"50\"}")), C));
    }

    /** A free-text field can hold "about 590 Wp" — not something to size a plant from. */
    @Test
    void aNonNumberFieldTypeDisqualifies() {
        String textSchema = "[{\"key\":\"wattage\",\"type\":\"text\",\"unit\":\"Wp\",\"label\":\"Wattage\"}]";
        assertFalse(BomCatalogueHealthService.qualifies(textSchema, List.of(make("{\"wattage\":\"590\"}")), W));
    }

    /** A value no schema declares can only ever be blank on the next make added. */
    @Test
    void anUndeclaredKeyDisqualifies() {
        String otherSchema = "[{\"key\":\"cell_type\",\"type\":\"dropdown\",\"label\":\"Cell Type\"}]";
        assertFalse(BomCatalogueHealthService.qualifies(otherSchema, List.of(make("{\"wattage\":\"590\"}")), W));
        assertFalse(BomCatalogueHealthService.qualifies(null, List.of(make("{\"wattage\":\"590\"}")), W));
    }

    @Test
    void bothKeysCanQualifyIndependently() {
        assertTrue(BomCatalogueHealthService.qualifies(INVERTER_SCHEMA, List.of(make("{\"capacity\":\"50\"}")), C));
        assertFalse(BomCatalogueHealthService.qualifies(INVERTER_SCHEMA, List.of(make("{\"capacity\":\"50\"}")), W));
    }
}
