package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemVariantRepo;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseHintRow;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.service.BomPurchaseHistoryService.ItemVariantPair;

/**
 * The BOM "last procured cost" hint, one test per acceptance criterion.
 *
 * <p>No Spring context and no database — the service's only collaborators are two
 * repositories. Every test is fed rows for the SAME two catalog items, so no test
 * can pass because of something item-specific: the spec's "identical logic for every
 * item category" is checked by construction, not by a test that could go stale.
 *
 * <p>Rows are handed over in the order the query promises (newest first, then newest
 * PO, then last line), because that ordering IS the spec's tie-break and the service
 * is entitled to rely on it.
 *
 * <p><b>What is deliberately not here:</b> row selection and identity resolution —
 * 'Cancelled', WORK_ORDER, zero price/quantity, and the atomic choice between the PO
 * line's own catalog ids and those of the BOM line it consumes — live in the SQL, not
 * in Java. A unit test that mocked the repo could only assert its own fixture. Those
 * are verified by running the real query against the database.
 */
@ExtendWith(MockitoExtension.class)
class BomPurchaseHistoryServiceTest {

    private static final ZoneId  ZONE  = ZoneId.systemDefault();
    /** "Today" for every test. Fixed so staleness and ageDays are assertable. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    private static final long ITEM_MODULE   = 12L;   // has makes 45 (Waaree) and 46 (Adani)
    private static final long ITEM_INVERTER = 80L;   // has make 3 (Sungrow)

    @Mock private PurchaseOrderItemRepository poItemRepo;
    @Mock private BomItemVariantRepo          variantRepo;

    @InjectMocks private BomPurchaseHistoryService svc;

    @BeforeEach
    void setUp() {
        svc.clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        lenient().when(variantRepo.findAllById(any())).thenReturn(List.of(
                variant(45L, "Waaree", "550 Wp"),
                variant(46L, "Adani",  "545 Wp"),
                variant(3L,  "Sungrow", "SG110CX")));
    }

    // ── 1. Exact make is the headline ────────────────────────────────────────

    @Test
    void exactMakeWinsEvenWhenAnotherMakeWasBoughtMoreRecently() {
        rows(row(ITEM_MODULE, 46L, "24.00", days(1),  1L, "PO-1", "Adani supplier"),
             row(ITEM_MODULE, 45L, "22.50", days(30), 2L, "PO-2", "Waaree supplier"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        assertEquals("VARIANT", h.get("match"));
        assertEquals(new BigDecimal("22.50"), h.get("unitRate"));
        assertEquals("Waaree supplier", h.get("vendorName"));
        // The selected make needs no label — the row already shows it.
        assertNull(h.get("makeLabel"));
    }

    // ── 2. Different-make fallback, always named ─────────────────────────────

    @Test
    void fallsBackToAnotherMakeAndLabelsIt() {
        rows(row(ITEM_MODULE, 46L, "24.00", days(5), 1L, "PO-1", "Adani supplier"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));   // 45 never bought

        assertEquals("ITEM", h.get("match"));
        // Non-blank label is the whole point: an unnamed fallback would read as the
        // price for the make the estimator actually picked.
        assertEquals("Adani 545 Wp", h.get("makeLabel"));
        assertEquals(new BigDecimal("24.00"), h.get("unitRate"));
    }

    @Test
    void fallbackRowsThatCannotBeNamedAreSkipped() {
        // Uncatalogued make and no free-text make either — nothing truthful to call it.
        rows(row(ITEM_MODULE, null, "24.00", days(1), 1L, "PO-1", "V", null),
             row(ITEM_MODULE, 46L,  "23.00", days(9), 2L, "PO-2", "V"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        assertEquals("ITEM", h.get("match"));
        assertEquals("Adani 545 Wp", h.get("makeLabel"));
        assertEquals(new BigDecimal("23.00"), h.get("unitRate"));   // the nameless newer row lost
    }

    @Test
    void freeTextMakeNamesTheFallbackWhenTheCatalogRowIsGone() {
        rows(row(ITEM_MODULE, null, "21.00", days(2), 1L, "PO-1", "V", "Vikram Solar"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        assertEquals("ITEM", h.get("match"));
        assertEquals("Vikram Solar", h.get("makeLabel"));
    }

    // ── 3. No qualifying history means NO KEY ────────────────────────────────

    @Test
    void itemNeverPurchasedIsAbsentFromTheMapEntirely() {
        rows(row(ITEM_MODULE, 45L, "22.50", days(3), 1L, "PO-1", "V"));

        Map<String, Object> hints = hints(pair(ITEM_MODULE, 45L), pair(ITEM_INVERTER, 3L));

        assertTrue(hints.containsKey("12:45"));
        // Absent, not present-with-nulls. There is no object to render, so a blank
        // placeholder cannot be shown as if it were a price.
        assertFalse(hints.containsKey("80:3"));
    }

    @Test
    void emptyHistoryYieldsNoHintsAtAll() {
        rows();
        assertTrue(hints(pair(ITEM_MODULE, 45L)).isEmpty());
    }

    // ── 4. Deterministic tie-break ───────────────────────────────────────────

    @Test
    void sameDateTieResolvesToTheRowTheQueryOrderedFirst() {
        LocalDateTime sameDay = days(10);
        // Query order = order_date DESC, po_id DESC, line_no DESC, so PO 9 leads.
        rows(row(ITEM_MODULE, 45L, "26.00", sameDay, 9L, "PO-9", "Newer PO"),
             row(ITEM_MODULE, 45L, "25.00", sameDay, 8L, "PO-8", "Older PO"));

        assertEquals(new BigDecimal("26.00"), hint(pair(ITEM_MODULE, 45L)).get("unitRate"));
    }

    // ── 5. Staleness ─────────────────────────────────────────────────────────

    @Test
    void exactlyTheThresholdIsNotYetStaleButOneDayMoreIs() {
        rows(row(ITEM_MODULE, 45L, "22.50", days(90), 1L, "PO-1", "V"));
        Map<String, Object> fresh = hint(pair(ITEM_MODULE, 45L));
        assertEquals(90L, fresh.get("ageDays"));
        assertEquals(false, fresh.get("stale"));

        rows(row(ITEM_MODULE, 45L, "22.50", days(91), 1L, "PO-1", "V"));
        assertEquals(true, hint(pair(ITEM_MODULE, 45L)).get("stale"));
    }

    @Test
    void aFutureDatedPoReadsAsZeroDaysOldNotNegative() {
        rows(row(ITEM_MODULE, 45L, "22.50", NOW.plusDays(12), 1L, "PO-1", "V"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        assertEquals(0L, h.get("ageDays"));      // clamped, never "stale in -12 days"
        assertEquals(false, h.get("stale"));
    }

    // ── 6. Rounding to the column the value lands in ─────────────────────────

    @Test
    void rateIsRoundedToTheTwoDecimalsTheBomColumnHoldsAndKeepsTheSource() {
        rows(row(ITEM_MODULE, 45L, "1234.567890", days(4), 1L, "PO-1", "V"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        // Applying an unrounded 6dp value would silently change on the next save.
        assertEquals(new BigDecimal("1234.57"), h.get("unitRate"));
        assertEquals(new BigDecimal("1234.567890"), h.get("unitRateRaw"));
    }

    // ── 7. The history panel ─────────────────────────────────────────────────

    @Test
    void headlineIsAlwaysTheFirstHistoryRow() {
        rows(row(ITEM_MODULE, 45L, "22.50", days(3),  1L, "PO-1", "V1"),
             row(ITEM_MODULE, 45L, "21.00", days(40), 2L, "PO-2", "V2"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));
        Map<?, ?> first = history(h).get(0);

        assertEquals(h.get("unitRate"),   first.get("unitRate"));
        assertEquals(h.get("orderDate"),  first.get("orderDate"));
        assertEquals(h.get("vendorName"), first.get("vendorName"));
        assertEquals(h.get("poNo"),       first.get("poNo"));
    }

    @Test
    void oneMultiLinePoCountsAsOnePurchaseNotThree() {
        // Same PO split across three lines, then two genuinely earlier purchases.
        rows(row(ITEM_MODULE, 45L, "22.50", days(3),  1L, "PO-1", "V1"),
             row(ITEM_MODULE, 45L, "22.40", days(3),  1L, "PO-1", "V1"),
             row(ITEM_MODULE, 45L, "22.30", days(3),  1L, "PO-1", "V1"),
             row(ITEM_MODULE, 45L, "21.00", days(40), 2L, "PO-2", "V2"),
             row(ITEM_MODULE, 45L, "20.00", days(80), 3L, "PO-3", "V3"));

        List<Map<?, ?>> history = history(hint(pair(ITEM_MODULE, 45L)));

        assertEquals(3, history.size());
        assertEquals(List.of("PO-1", "PO-2", "PO-3"),
                     history.stream().map(r -> r.get("poNo")).toList());
    }

    @Test
    void historyIsCappedAtTheConfiguredSize() {
        svc.historySize = 2;
        rows(row(ITEM_MODULE, 45L, "22.50", days(3),  1L, "PO-1", "V1"),
             row(ITEM_MODULE, 45L, "21.00", days(40), 2L, "PO-2", "V2"),
             row(ITEM_MODULE, 45L, "20.00", days(80), 3L, "PO-3", "V3"));

        assertEquals(2, history(hint(pair(ITEM_MODULE, 45L))).size());
    }

    @Test
    void everyHistoryRowCarriesItsPoStatusBecauseDraftsAreIncluded() {
        rows(row(ITEM_MODULE, 45L, "22.50", days(3), 1L, "PO-1", "V", "Waaree", "Draft"));

        Map<String, Object> h = hint(pair(ITEM_MODULE, 45L));

        assertEquals("Draft", h.get("poStatus"));
        assertEquals("Draft", history(h).get(0).get("poStatus"));
    }

    // ── 8. Rate visibility ───────────────────────────────────────────────────

    @Test
    void aRoleThatMayNotSeeRatesGetsNoHintsAndCostsNoQuery() {
        Map<String, Object> data = svc.hints(List.of(pair(ITEM_MODULE, 45L)), "SITE_ENGINEER");

        assertTrue(asHints(data).isEmpty());
        verify(poItemRepo, never()).findPurchaseHistoryForItems(any(), anyInt());
    }

    @Test
    void disabledByConfigurationYieldsNoHintsAndCostsNoQuery() {
        svc.enabled = false;

        assertTrue(hints(pair(ITEM_MODULE, 45L)).isEmpty());
        verify(poItemRepo, never()).findPurchaseHistoryForItems(any(), anyInt());
    }

    @Test
    void noPairsMeansNoQuery() {
        assertTrue(asHints(svc.hints(List.of(), null)).isEmpty());
        verify(poItemRepo, never()).findPurchaseHistoryForItems(any(), anyInt());
    }

    // ── 9. Every category behaves identically ────────────────────────────────

    @Test
    void twoDifferentCatalogItemsGetTheSameTreatmentInOneCall() {
        rows(row(ITEM_INVERTER, 3L,  "45000.00", days(2),  1L, "PO-1", "Inv vendor"),
             row(ITEM_MODULE,   46L, "24.00",    days(6),  2L, "PO-2", "Mod vendor"));

        Map<String, Object> hints = hints(pair(ITEM_INVERTER, 3L), pair(ITEM_MODULE, 45L));

        // One exact hit, one different-make fallback — decided purely by the ids.
        assertEquals("VARIANT", ((Map<?, ?>) hints.get("80:3")).get("match"));
        assertEquals("ITEM",    ((Map<?, ?>) hints.get("12:45")).get("match"));
    }

    @Test
    void aDuplicatedPairIsAnsweredOnce() {
        rows(row(ITEM_MODULE, 45L, "22.50", days(3), 1L, "PO-1", "V"));

        assertEquals(1, hints(pair(ITEM_MODULE, 45L), pair(ITEM_MODULE, 45L)).size());
    }

    // ── 10. Request parsing ──────────────────────────────────────────────────

    @Test
    void parsesWellFormedPairsAndDropsEverythingElse() {
        List<ItemVariantPair> parsed = BomPurchaseHistoryService.parsePairs(
                " 12:45 , 80:3 , 12:45 , 99 , :7 , 5: , abc:1 , 1:xyz , ");

        assertEquals(List.of(new ItemVariantPair(12L, 45L), new ItemVariantPair(80L, 3L)), parsed);
    }

    @Test
    void parsesNullAndBlankToNothing() {
        assertTrue(BomPurchaseHistoryService.parsePairs(null).isEmpty());
        assertTrue(BomPurchaseHistoryService.parsePairs("   ").isEmpty());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static ItemVariantPair pair(long item, long variant) {
        return new ItemVariantPair(item, variant);
    }

    /** Days before the fixed "now". */
    private static LocalDateTime days(int ago) { return NOW.minusDays(ago); }

    private void rows(PurchaseHintRow... rows) {
        when(poItemRepo.findPurchaseHistoryForItems(any(), anyInt())).thenReturn(Arrays.asList(rows));
    }

    private Map<String, Object> hints(ItemVariantPair... pairs) {
        return asHints(svc.hints(Arrays.asList(pairs), null));
    }

    /** The single hint for one pair. Fails loudly (NPE/CCE) if it is absent. */
    private Map<String, Object> hint(ItemVariantPair p) {
        return asMap(hints(p).get(p.key()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asHints(Map<String, Object> data) {
        return (Map<String, Object>) data.get("hints");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> history(Map<String, Object> hint) {
        return new ArrayList<>((List<Map<?, ?>>) hint.get("history"));
    }

    private static BomItemVariantEntity variant(Long id, String make, String model) {
        BomItemVariantEntity v = new BomItemVariantEntity();
        v.setId(id);
        v.setMake(make);
        v.setModel(model);
        return v;
    }

    private static PurchaseHintRow row(Long itemId, Long variantId, String price,
                                       LocalDateTime orderDate, Long poId, String poNo,
                                       String vendor) {
        return row(itemId, variantId, price, orderDate, poId, poNo, vendor, "some make", "Approved");
    }

    private static PurchaseHintRow row(Long itemId, Long variantId, String price,
                                       LocalDateTime orderDate, Long poId, String poNo,
                                       String vendor, String makeText) {
        return row(itemId, variantId, price, orderDate, poId, poNo, vendor, makeText, "Approved");
    }

    private static PurchaseHintRow row(Long itemId, Long variantId, String price,
                                       LocalDateTime orderDate, Long poId, String poNo,
                                       String vendor, String makeText, String status) {
        return new Row(itemId, variantId, new BigDecimal(price), "Nos", new BigDecimal("10"),
                       new BigDecimal("18.00"), 1, makeText, poId, poNo, status, orderDate, vendor);
    }

    /** Stands in for the query projection. */
    private record Row(Long itemId, Long variantId, BigDecimal unitPrice, String unit,
                       BigDecimal quantity, BigDecimal taxPercent, Integer lineNo, String makeText,
                       Long poId, String poNo, String poStatus, LocalDateTime orderDate,
                       String vendorName) implements PurchaseHintRow {

        @Override public Long          getItemId()     { return itemId; }
        @Override public Long          getVariantId()  { return variantId; }
        @Override public BigDecimal    getUnitPrice()  { return unitPrice; }
        @Override public String        getUnit()       { return unit; }
        @Override public BigDecimal    getQuantity()   { return quantity; }
        @Override public BigDecimal    getTaxPercent() { return taxPercent; }
        @Override public Integer       getLineNo()     { return lineNo; }
        @Override public String        getMakeText()   { return makeText; }
        @Override public Long          getPoId()       { return poId; }
        @Override public String        getPoNo()       { return poNo; }
        @Override public String        getPoStatus()   { return poStatus; }
        @Override public LocalDateTime getOrderDate()  { return orderDate; }
        @Override public String        getVendorName() { return vendorName; }
    }

}
