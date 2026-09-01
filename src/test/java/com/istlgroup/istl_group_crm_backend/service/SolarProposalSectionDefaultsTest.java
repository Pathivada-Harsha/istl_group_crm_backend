package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.SolarProposalDocRequest;

/**
 * Which of the two optional sections a proposal carries.
 *
 * <p>The rules are deliberately independent, and this is the class that pins that
 * down:
 *
 * <ul>
 *   <li><b>Subsidy</b> follows the property type — the MNRE PM Surya Ghar rooftop
 *       scheme is residential-only, so every other type (and an unrecorded one)
 *       is ineligible.</li>
 *   <li><b>ROI</b> follows the data. {@code Roi.compute} is capacity × tariff ×
 *       generation over the system life with no property-type term anywhere in it,
 *       so a commercial buyer — who is making a capex decision and cares about
 *       payback more than a homeowner does — gets the table as soon as the two
 *       inputs exist.</li>
 * </ul>
 *
 * <p>Pure: no Spring, no DB. {@code renderInputs} only reads the request, so the
 * service can be constructed bare.
 */
class SolarProposalSectionDefaultsTest {

    private final SolarProposalDocService service = new SolarProposalDocService();

    // ── The two rules, in isolation ──────────────────────────────────────────

    @Test
    void onlyResidentialIsSubsidyEligible() {
        assertTrue(SolarProposalDocService.subsidyEligible("Residential"));
        assertTrue(SolarProposalDocService.subsidyEligible("  residential  "),
                "trimmed and case-insensitive, as the telecaller field is free-form");

        for (String t : new String[] {
                "Commercial", "Industrial", "Institutional", "Agricultural", "Government" }) {
            assertFalse(SolarProposalDocService.subsidyEligible(t),
                    t + " rooftops are not eligible for the MNRE residential subsidy");
        }
    }

    @Test
    void anUnrecordedPropertyTypeIsNotSubsidyEligible() {
        // Regression: isResidential(null) used to return true, so a commercial lead
        // whose tc_property_type had been cleared quoted a residential subsidy.
        assertFalse(SolarProposalDocService.subsidyEligible(null));
        assertFalse(SolarProposalDocService.subsidyEligible(""));
        assertFalse(SolarProposalDocService.subsidyEligible("   "));
    }

    @Test
    void roiNeedsBothCapacityAndTariffAndNothingElse() {
        assertTrue(SolarProposalDocService.roiAvailable(bd(50), bd(9)));

        assertFalse(SolarProposalDocService.roiAvailable(null, bd(9)));
        assertFalse(SolarProposalDocService.roiAvailable(bd(50), null));
        assertFalse(SolarProposalDocService.roiAvailable(BigDecimal.ZERO, bd(9)));
        assertFalse(SolarProposalDocService.roiAvailable(bd(50), BigDecimal.ZERO));
    }

    // ── What actually reaches the renderers ──────────────────────────────────

    @Test
    void commercialGetsRoiButNoSubsidy() {
        Map<String, Boolean> blocks = blocksFor(request("Commercial", bd(50), bd(9)));

        assertTrue(blocks.get("ROI"),
                "a commercial buyer evaluates solar on payback — the ROI table is the point");
        assertFalse(blocks.get("SUBSIDY"));
    }

    @Test
    void commercialWithoutATariffGetsNoRoi() {
        // No Site Visit means no ₹/unit, and there is deliberately no default
        // tariff: a made-up number would land in a client-facing quote.
        Map<String, Boolean> blocks = blocksFor(request("Commercial", bd(50), null));

        assertFalse(blocks.get("ROI"));
        assertFalse(blocks.get("SUBSIDY"));
    }

    @Test
    void residentialStillGetsBoth() {
        Map<String, Boolean> blocks = blocksFor(request("Residential", bd(4), bd(7)));

        assertTrue(blocks.get("ROI"));
        assertTrue(blocks.get("SUBSIDY"));
    }

    @Test
    void theOtherPropertyTypesFollowTheSameSplit() {
        for (String t : new String[] { "Industrial", "Institutional", "Agricultural", "Government" }) {
            Map<String, Boolean> blocks = blocksFor(request(t, bd(100), bd(8)));
            assertTrue(blocks.get("ROI"), t + " gets ROI once capacity and tariff are known");
            assertFalse(blocks.get("SUBSIDY"), t + " is not subsidy-eligible");
        }
    }

    @Test
    void anUnrecordedTypeStillGetsRoiIfTheNumbersAreThere() {
        // ROI does not consult the property type at all, so an unknown type is no
        // reason to withhold it — only the missing subsidy eligibility is decided.
        Map<String, Boolean> blocks = blocksFor(request(null, bd(20), bd(8)));

        assertTrue(blocks.get("ROI"));
        assertFalse(blocks.get("SUBSIDY"));
    }

    @Test
    void anExplicitFlagAlwaysBeatsTheDefault() {
        SolarProposalDocRequest off = request("Residential", bd(4), bd(7));
        off.setIncludeRoi(false);
        off.setIncludeSubsidy(false);
        assertFalse(blocksFor(off).get("ROI"), "the preparer can untick a defaulted-on section");
        assertFalse(blocksFor(off).get("SUBSIDY"));

        SolarProposalDocRequest on = request("Commercial", bd(50), bd(9));
        on.setIncludeSubsidy(true);
        assertTrue(blocksFor(on).get("SUBSIDY"), "and can tick a defaulted-off one");
    }

    // ── Tokens follow the blocks ─────────────────────────────────────────────

    @Test
    void roiTokensAreEmittedOnlyWhenTheSectionIsKept() {
        Map<String, String> withRoi = service.renderInputs(request("Commercial", bd(50), bd(9))).tokens();
        assertTrue(withRoi.containsKey("ROI_NET_BENEFIT"));
        assertEquals("50 kWp", withRoi.get("ROI_CAPACITY"));
        assertTrue(withRoi.get("ROI_TARIFF").contains("9"), "the commercial tariff carries into the table");

        Map<String, String> withoutRoi = service.renderInputs(request("Commercial", bd(50), null)).tokens();
        assertTrue(withoutRoi.keySet().stream().noneMatch(k -> k.startsWith("ROI_")),
                "no stray ROI_* tokens when the block is deleted");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Boolean> blocksFor(SolarProposalDocRequest req) {
        return service.renderInputs(req).blocks();
    }

    /** A request with everything ROI/subsidy-relevant set and the flags left to default. */
    private static SolarProposalDocRequest request(String propertyType,
                                                   BigDecimal capacityKw,
                                                   BigDecimal tariffPerUnit) {
        SolarProposalDocRequest r = new SolarProposalDocRequest();
        r.setLeadId(1L);
        r.setPropertyType(propertyType);
        r.setClientName("Client");
        r.setCapacityLabel(capacityKw + " kWp");
        r.setBasePrice(new BigDecimal("2500000"));
        r.setGstPercent(new BigDecimal("8.9"));
        r.setQuoteValidDays(10);
        r.setQuoteValidFrom("2026-08-12");
        r.setCapacityKw(capacityKw);
        r.setTariffPerUnit(tariffPerUnit);
        r.setSpecificGeneration(new BigDecimal("1460"));
        r.setSystemLifeYears(25);
        r.setSubsidyAmount(new BigDecimal("78000"));
        r.setBomRows(List.of(bomRow()));
        return r;
    }

    private static SolarProposalDocRequest.BomRow bomRow() {
        SolarProposalDocRequest.BomRow b = new SolarProposalDocRequest.BomRow();
        b.setComponent("Solar Modules");
        b.setSpecification("545 Wp Mono PERC");
        b.setMake("Waaree");
        b.setQuantity("92");
        b.setUnit("Nos");
        return b;
    }

    private static BigDecimal bd(int v) { return BigDecimal.valueOf(v); }
}
