package com.istlgroup.istl_group_crm_backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.util.CapacityUtil.CapacityInfo;

/** Pure tests — no Spring, no DB. */
class CapacityUtilTest {

    @Test
    void parsesNumberAndUnitFromCombinedStrings() {
        assertEquals(0, CapacityUtil.parseValueUnit("10kW").value().compareTo(BigDecimal.TEN));
        assertEquals("kW", CapacityUtil.parseValueUnit("10kW").unit());
        assertEquals(0, CapacityUtil.parseValueUnit("100 m").value().compareTo(new BigDecimal("100")));
        assertEquals("m", CapacityUtil.parseValueUnit("100 m").unit());
        assertEquals(0, CapacityUtil.parseValueUnit("5").value().compareTo(new BigDecimal("5")));
        assertEquals("", CapacityUtil.parseValueUnit("5").unit());
    }

    @Test
    void blankAndNonNumericHaveNullValue() {
        assertNull(CapacityUtil.parseValueUnit("").value());
        assertNull(CapacityUtil.parseValueUnit(null).value());
        assertNull(CapacityUtil.parseValueUnit("TBD").value());
    }

    @Test
    void unitColumnWinsAndConvertsToKw() {
        CapacityInfo kw = CapacityUtil.parse("5", "kW", "Solar_Rooftop");
        assertTrue(kw.isUsable());
        assertTrue(kw.powerBased());
        assertEquals(0, kw.scaleBase().compareTo(new BigDecimal("5")));

        // kWp normalises to kW, scale base unchanged
        CapacityInfo kwp = CapacityUtil.parse("100", "kWp", "Solar_Rooftop");
        assertEquals("kW", kwp.unit());
        assertEquals(0, kwp.scaleBase().compareTo(new BigDecimal("100")));

        // MW → ×1000 kW
        CapacityInfo mw = CapacityUtil.parse("1", "MW", "Solar_ground_mounted");
        assertEquals("MW", mw.unit());
        assertEquals(0, mw.scaleBase().compareTo(new BigDecimal("1000")));
    }

    @Test
    void unitInsideCapacityStringUsedWhenColumnBlank() {
        CapacityInfo c = CapacityUtil.parse("100 kWp", "", "Solar_Rooftop");
        assertEquals("kW", c.unit());
        assertEquals(0, c.scaleBase().compareTo(new BigDecimal("100")));
    }

    @Test
    void countBasedSubGroupsAreNotPowerBased() {
        CapacityInfo c = CapacityUtil.parse("500", "Units", "CCMS");
        assertFalse(c.powerBased());
        assertEquals("Units", c.unit());
        assertEquals(0, c.scaleBase().compareTo(new BigDecimal("500")));
    }

    @Test
    void unparseableCapacityIsNotUsable() {
        assertFalse(CapacityUtil.parse("", "kW", "Solar_Rooftop").isUsable());
        assertFalse(CapacityUtil.parse(null, null, "Solar_Rooftop").isUsable());
    }

    @Test
    void sameFamilyMatchesPowerWithPowerAndCountWithCount() {
        CapacityInfo kw = CapacityUtil.parse("5", "kW", "Solar_Rooftop");
        CapacityInfo mw = CapacityUtil.parse("1", "MW", "Solar_ground_mounted");
        CapacityInfo units = CapacityUtil.parse("500", "Units", "CCMS");
        assertTrue(CapacityUtil.sameFamily(kw, mw));
        assertFalse(CapacityUtil.sameFamily(kw, units));
    }
}
