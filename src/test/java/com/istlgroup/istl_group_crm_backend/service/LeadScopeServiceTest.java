package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.LeadsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.BomSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ExtraLineRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ExtrasSaveRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadScopeWrapper.ScopeItemsBulkRequest;

/**
 * Covers the Scope → BOM → Budget Estimation chain.
 *
 * @Transactional rolls every test back, so this runs against the configured dev
 * database (ddl-auto=validate — there is no in-memory schema to fall back on)
 * without leaving rows behind. Acts as SUPERADMIN so LeadScopeService#authorize
 * takes its level<=2 bypass rather than needing a lead_access grant.
 */
@SpringBootTest
@Transactional
class LeadScopeServiceTest {

    private static final Long USER_ID = 1L;
    private static final String ROLE = "SUPERADMIN";

    @Autowired
    private LeadScopeService leadScopeService;

    @Autowired
    private LeadsRepo leadsRepo;

    private Long leadId;

    @BeforeEach
    void pickLead() {
        List<LeadsEntity> leads = leadsRepo.findAll();
        assertTrue(!leads.isEmpty(), "needs at least one lead in the dev database");
        leadId = leads.get(0).getId();
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }

    private static ScopeItemRequest scopeItem(String activity, String qty, String unit) {
        ScopeItemRequest r = new ScopeItemRequest();
        r.setActivity(activity);
        r.setQuantity(qty == null ? null : new BigDecimal(qty));
        r.setUnit(unit);
        return r;
    }

    private static BomLineRequest bomLine(String name, String qty, String rate) {
        BomLineRequest l = new BomLineRequest();
        l.setItemName(name);
        l.setQuantity(new BigDecimal(qty));
        l.setUnitRate(new BigDecimal(rate));
        return l;
    }

    private static ExtraLineRequest extra(String name, String basis, String rate) {
        ExtraLineRequest e = new ExtraLineRequest();
        e.setName(name);
        e.setBasis(basis);
        e.setRateValue(new BigDecimal(rate));
        return e;
    }

    private BomSaveRequest bom(BomLineRequest... lines) {
        BomSaveRequest b = new BomSaveRequest();
        b.setLines(List.of(lines));
        return b;
    }

    @Test
    void scopeItemsBulkSaveReplacesTheList() throws Exception {
        ScopeItemsBulkRequest req = new ScopeItemsBulkRequest();
        req.setItems(List.of(scopeItem("Site Survey", "1", "Lot"), scopeItem("Procurement", "100", "Nos")));
        List<Map<String, Object>> saved = leadScopeService.saveScopeItems(leadId, req, USER_ID, ROLE);

        assertEquals(2, saved.size());
        assertEquals("Site Survey", saved.get(0).get("activity"));
        // seqNo is assigned by position, not trusted from the client.
        assertEquals(1, saved.get(0).get("seqNo"));
        assertEquals(2, saved.get(1).get("seqNo"));

        // Dropping a line soft-deletes it rather than leaving an orphan.
        ScopeItemsBulkRequest shrink = new ScopeItemsBulkRequest();
        ScopeItemRequest keep = scopeItem("Site Survey", "1", "Lot");
        keep.setId((Long) saved.get(0).get("id"));
        shrink.setItems(List.of(keep));
        leadScopeService.saveScopeItems(leadId, shrink, USER_ID, ROLE);

        List<?> remaining = (List<?>) leadScopeService.getScope(leadId).get("items");
        assertEquals(1, remaining.size());
    }

    @Test
    void bomAmountIsDerivedFromQuantityTimesRate() throws Exception {
        Map<String, Object> data = leadScopeService.saveBom(
                leadId, bom(bomLine("Solar Module", "10", "5000.50")), USER_ID, ROLE);

        List<?> lines = (List<?>) data.get("lines");
        assertEquals(1, lines.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) lines.get(0);
        assertEquals(0, num(line.get("amount")).compareTo(new BigDecimal("50005.00")));
        assertEquals(0, num(data.get("totalAmount")).compareTo(new BigDecimal("50005.00")));
    }

    @Test
    void lumpSumBomLineHonoursAnExplicitAmount() throws Exception {
        BomLineRequest lump = new BomLineRequest();
        lump.setItemName("Civil works (lump sum)");
        lump.setAmount(new BigDecimal("75000"));
        // No quantity or rate — the amount must survive rather than derive to zero.

        Map<String, Object> data = leadScopeService.saveBom(leadId, bom(lump), USER_ID, ROLE);
        assertEquals(0, num(data.get("totalAmount")).compareTo(new BigDecimal("75000.00")));
    }

    /**
     * A line that could not be sized is saved blank and comes back blank. Coercing
     * it to zero on save is what turned "this could not be calculated, here's why"
     * into a quantity that reads as a real answer the moment the BOM was saved.
     */
    @Test
    void anUnsizeableLineSurvivesTheRoundTripAsBlank() throws Exception {
        BomLineRequest unsized = new BomLineRequest();
        unsized.setItemName("PV Modules");
        unsized.setBasis("PER_WATT_PEAK");
        unsized.setUnitRate(new BigDecimal("10000"));
        // No quantity: the make carries no wattage, so nothing could be derived.

        leadScopeService.saveBom(leadId, bom(unsized), USER_ID, ROLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> line = ((List<Map<String, Object>>) leadScopeService.getBom(leadId).get("lines")).get(0);
        assertNull(line.get("quantity"), "a blank quantity must not come back as zero");
    }

    /** Provenance is what lets a lead notice its BOM predates the template. */
    @Test
    void savedLinesKeepTheTemplateVersionTheyWereBuiltFrom() throws Exception {
        BomLineRequest l = bomLine("Solar Module", "10", "5000");
        l.setSourceTemplateId(4242L);
        l.setTemplateVersion(7);

        leadScopeService.saveBom(leadId, bom(l), USER_ID, ROLE);

        Map<String, Object> data = leadScopeService.getBom(leadId);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = ((List<Map<String, Object>>) data.get("lines")).get(0);
        assertEquals(4242L, saved.get("sourceTemplateId"));
        assertEquals(7, saved.get("templateVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) data.get("templateStatus");
        assertNotNull(status, "the tab needs a verdict on whether this BOM is current");
        assertEquals(4242L, status.get("savedTemplateId"));
    }

    /**
     * A hand-built or Excel-imported BOM carries no basis on any line, and must
     * never be told it might be running on stale template rules — there is no
     * template involved. `basisLineCount == 0` is the load-bearing test for that.
     */
    @Test
    void aHandBuiltBomIsNeverNaggedAboutTemplates() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Some material", "3", "100")), USER_ID, ROLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) leadScopeService.getBom(leadId).get("templateStatus");
        assertEquals(0, status.get("basisLineCount"));
        assertEquals("NONE", status.get("reviewHint"));
    }

    /** A line saved with provenance is not a candidate for the "unknown" notice. */
    @Test
    void aStampedLineIsNotCountedAsUnstamped() throws Exception {
        BomLineRequest l = bomLine("Solar Module", "10", "5000");
        l.setBasis("PER_KW");
        l.setBasisValue(new BigDecimal("1.7"));
        l.setSourceTemplateId(4242L);
        l.setTemplateVersion(7);
        leadScopeService.saveBom(leadId, bom(l), USER_ID, ROLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) leadScopeService.getBom(leadId).get("templateStatus");
        assertEquals(1, status.get("basisLineCount"));
        assertEquals(0, status.get("unstampedLineCount"));
    }

    /**
     * The population this feature exists for: a pre-migration line whose template
     * rule carried no value, so it stored a 0 that was never really calculated.
     * A stored zero counts as un-sized — otherwise it reads as a real answer.
     */
    @Test
    void aStoredZeroFromAnUnconfiguredBasisCountsAsUnsized() throws Exception {
        BomLineRequest broken = new BomLineRequest();
        broken.setItemName("PV Modules");
        broken.setBasis("PER_KW");
        broken.setQuantity(BigDecimal.ZERO);   // what the old code wrote
        broken.setBasisValue(null);            // …because the template had no factor

        leadScopeService.saveBom(leadId, bom(broken), USER_ID, ROLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) leadScopeService.getBom(leadId).get("templateStatus");
        assertEquals(1, status.get("basisLineCount"));
        assertEquals(1, status.get("unstampedLineCount"));
        assertEquals(1, status.get("unsizedLineCount"));
    }

    /** A zero from a line whose factor IS set is a real answer, not a failure. */
    @Test
    void aStoredZeroFromAConfiguredBasisIsNotUnsized() throws Exception {
        BomLineRequest genuine = new BomLineRequest();
        genuine.setItemName("Spare Fuses");
        genuine.setBasis("FIXED");
        genuine.setBasisValue(new BigDecimal("5"));
        genuine.setQuantity(BigDecimal.ZERO);

        leadScopeService.saveBom(leadId, bom(genuine), USER_ID, ROLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) leadScopeService.getBom(leadId).get("templateStatus");
        assertEquals(0, status.get("unsizedLineCount"));
    }

    @Test
    void fixedExtraUsesItsRateAndPercentExtraTracksTheBom() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "10", "10000")), USER_ID, ROLE); // BOM = 100000

        ExtrasSaveRequest req = new ExtrasSaveRequest();
        req.setLines(List.of(extra("Freight & Logistics", "FIXED", "45000"),
                             extra("Contingency", "PERCENT", "5")));
        Map<String, Object> data = leadScopeService.saveExtras(leadId, req, USER_ID, ROLE);

        assertEquals(0, num(data.get("bomSubtotal")).compareTo(new BigDecimal("100000.00")));
        // 45000 fixed + 5% of 100000 = 5000  → 50000
        assertEquals(0, num(data.get("extrasTotal")).compareTo(new BigDecimal("50000.00")));
        assertEquals(0, num(data.get("totalBudget")).compareTo(new BigDecimal("150000.00")));
    }

    /** The cross-tab behaviour: a rate change in the BOM must move percent extras. */
    @Test
    void changingTheBomRecomputesPercentExtras() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "10", "10000")), USER_ID, ROLE); // 100000

        ExtrasSaveRequest req = new ExtrasSaveRequest();
        req.setLines(List.of(extra("Contingency", "PERCENT", "10"),
                             extra("Insurance", "FIXED", "2000")));
        leadScopeService.saveExtras(leadId, req, USER_ID, ROLE);

        // Double the BOM by editing the rate, exactly as the BOM tab would.
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "10", "20000")), USER_ID, ROLE); // 200000

        Map<String, Object> after = leadScopeService.getExtras(leadId);
        assertEquals(0, num(after.get("bomSubtotal")).compareTo(new BigDecimal("200000.00")));
        // Contingency follows the BOM (10% of 200000 = 20000); Insurance stays flat.
        assertEquals(0, num(after.get("extrasTotal")).compareTo(new BigDecimal("22000.00")));
        assertEquals(0, num(after.get("totalBudget")).compareTo(new BigDecimal("222000.00")));
    }

    @Test
    void marginMarkupDrivesProposalPrice() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "1", "80000")), USER_ID, ROLE);
        ExtrasSaveRequest req = new ExtrasSaveRequest();
        req.setLines(List.of(extra("Contingency", "PERCENT", "25"))); // 20000 → cost 100000
        leadScopeService.saveExtras(leadId, req, USER_ID, ROLE);

        // 21% markup on 100000 cost → 121000 price, 21000 profit (the user's example).
        leadScopeService.updateMargin(leadId, new BigDecimal("21"), USER_ID, ROLE);

        Map<String, Object> s = leadScopeService.getEstimationSummary(leadId);
        assertEquals(0, num(s.get("totalCost")).compareTo(new BigDecimal("100000.00")));
        assertEquals(0, num(s.get("marginPercent")).compareTo(new BigDecimal("21.00")));
        assertEquals(0, num(s.get("proposalPrice")).compareTo(new BigDecimal("121000.00")));
        assertEquals(0, num(s.get("profitValue")).compareTo(new BigDecimal("21000.00")));
    }

    @Test
    void loweringMarginLowersPriceButKeepsCost() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "1", "100000")), USER_ID, ROLE);
        leadScopeService.updateMargin(leadId, new BigDecimal("21"), USER_ID, ROLE);
        // Negotiate the markup down to 15%.
        leadScopeService.updateMargin(leadId, new BigDecimal("15"), USER_ID, ROLE);

        Map<String, Object> s = leadScopeService.getEstimationSummary(leadId);
        assertEquals(0, num(s.get("totalCost")).compareTo(new BigDecimal("100000.00"))); // unchanged
        assertEquals(0, num(s.get("proposalPrice")).compareTo(new BigDecimal("115000.00")));
    }

    @Test
    void settingATargetPriceBackComputesTheMarkup() throws Exception {
        leadScopeService.saveBom(leadId, bom(bomLine("Modules", "1", "100000")), USER_ID, ROLE);
        // Customer agrees on a round 115000; markup back-computes to 15%.
        leadScopeService.updateSellingPrice(leadId, new BigDecimal("115000"), USER_ID, ROLE);

        Map<String, Object> s = leadScopeService.getEstimationSummary(leadId);
        assertEquals(0, num(s.get("marginPercent")).compareTo(new BigDecimal("15.00")));
        assertEquals(0, num(s.get("proposalPrice")).compareTo(new BigDecimal("115000.00")));
    }

    /** Zero cost must not blow up the price back-compute divide. */
    @Test
    void zeroCostIsGuarded() throws Exception {
        leadScopeService.updateSellingPrice(leadId, new BigDecimal("5000"), USER_ID, ROLE); // no BOM → cost 0
        Map<String, Object> s = leadScopeService.getEstimationSummary(leadId);
        assertEquals(0, num(s.get("marginPercent")).compareTo(BigDecimal.ZERO));
    }
}
