package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.istlgroup.istl_group_crm_backend.customException.BomEnforcementException;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectBomRepo;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.BomAvailability;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.CheckResult;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.DocLine;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.Match;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.Mode;
import com.istlgroup.istl_group_crm_backend.service.BomProcurementGuard.Violation;

/**
 * Pure unit tests for the BOM quantity arithmetic and the new-vs-existing PO rules.
 *
 * <p>No Spring context and no database: the guard's only collaborators are three
 * repositories, so mocking them keeps this fast and lets each acceptance check be
 * stated directly. The numbered comments map to the spec's acceptance checks.
 */
@ExtendWith(MockitoExtension.class)
class BomProcurementGuardTest {

    private static final String PUID = "PRJ-000123";
    private static final Long   PID  = 42L;

    @Mock private ProjectBomRepo              bomRepo;
    @Mock private DropdownProjectRepository   projectRepo;
    @Mock private PurchaseOrderItemRepository poItemRepo;

    @InjectMocks private BomProcurementGuard guard;

    @BeforeEach
    void projectResolves() {
        DropdownProjectEntity p = new DropdownProjectEntity();
        p.setId(PID);
        p.setProjectUniqueId(PUID);
        lenient().when(projectRepo.findByProjectUniqueId(PUID)).thenReturn(Optional.of(p));
        lenient().when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
                 .thenReturn(List.of());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ProjectBomEntity bomLine(long id, String name, String qty) {
        ProjectBomEntity b = new ProjectBomEntity();
        b.setId(id);
        b.setProjectId(PID);
        b.setSeqNo((int) id);
        b.setItemName(name);
        b.setUnit("Nos");
        b.setQuantity(new BigDecimal(qty));
        b.setUnitRate(new BigDecimal("100.00"));
        b.setAmount(new BigDecimal("100.00"));
        return b;
    }

    private static DocLine line(int no, String name, String qty) {
        return new DocLine(no, name, null, "Nos", new BigDecimal(qty), null, null, null);
    }

    private static DocLine linked(int no, String name, String qty, Long bomLineId) {
        return new DocLine(no, name, null, "Nos", new BigDecimal(qty), bomLineId, null, null);
    }

    private void bomIs(ProjectBomEntity... lines) {
        when(bomRepo.findByProjectIdAndDeletedAtIsNullOrderBySeqNo(PID)).thenReturn(List.of(lines));
    }

    /** A live PO on this project that already consumed {@code qty} of a BOM line. */
    private void alreadyOrdered(long bomLineId, String qty) {
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(bomLineId, "Prior Item", qty, "ID")));
    }

    /**
     * One row of {@code findLivePoLinesForProject}, in its column order:
     * poId, poNo, poRefId, vendorId, vendorName, orderDate, status, lineNo, itemName,
     * make, unit, quantity, unitPrice, taxPercent, bomLineId, bomItemId, variantId, bomMatch.
     */
    private static Object[] poRow(Long bomLineId, String itemName, String qty, String bomMatch) {
        return new Object[] {
            9001L, "PO-9001", "PO/REF/9001", 5L, "Some Vendor",
            java.sql.Timestamp.valueOf("2026-01-01 00:00:00"), "Ordered",
            1, itemName, null, "Nos",
            new BigDecimal(qty), new BigDecimal("100"), new BigDecimal("18"),
            bomLineId, null, null, bomMatch };
    }

    private static Violation only(CheckResult r) {
        assertEquals(1, r.violations().size(), () -> "expected exactly one violation, got " + r.violations());
        return r.violations().get(0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rollout safety — the guard must never block when it has nothing to say
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void emptyBomIsANoOpRatherThanBlockingEveryLine() {
        bomIs(); // project exists but its BOM has not been filled in
        CheckResult r = guard.check(PUID, List.of(line(1, "Anything", "5")), null, Mode.BLOCK);
        assertTrue(r.ok(), "a project with no BOM lines must not block procurement");
    }

    @Test
    void unknownOrMissingProjectIsANoOp() {
        when(projectRepo.findByProjectUniqueId("NOPE")).thenReturn(Optional.empty());
        assertTrue(guard.check("NOPE", List.of(line(1, "X", "5")), null, Mode.BLOCK).ok());
        assertTrue(guard.check(null,   List.of(line(1, "X", "5")), null, Mode.BLOCK).ok());
        assertTrue(guard.check("  ",   List.of(line(1, "X", "5")), null, Mode.BLOCK).ok());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §6.1 off-BOM  (acceptance check 4)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void newPoWithAnOffBomLineIsBlockedAndNamesTheLine() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        CheckResult r = guard.check(PUID, List.of(line(1, "Earthing Strip 25x3", "30")), null, Mode.BLOCK);

        Violation v = only(r);
        assertEquals("NOT_IN_BOM", v.code());
        assertEquals("Earthing Strip 25x3", v.itemName());
        assertTrue(v.message().contains("Earthing Strip 25x3"));
        assertEquals(List.of(1), v.lineNos());
    }

    @Test
    void enforceThrowsCarryingEveryOffendingLine() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        BomEnforcementException ex = assertThrows(BomEnforcementException.class,
                () -> guard.enforce(PUID, List.of(line(1, "Widget A", "1"), line(2, "Widget B", "1")), null));

        assertEquals(2, ex.getViolations().size(), "both offending lines must be reported, not just the first");
        assertEquals(PUID, ex.getProjectUniqueId());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §6.2 quantity  (acceptance checks 5, 6, 7)
    // ═════════════════════════════════════════════════════════════════════════

    /** Check 5 — BOM 20, PO-1 already ordered 20, PO-2 for one more is blocked at 21. */
    @Test
    void quantityBeyondTheBomIsBlockedCountingOtherLivePos() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        alreadyOrdered(1L, "20");

        Violation v = only(guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "1", 1L)), null, Mode.BLOCK));

        assertEquals("EXCEEDS_BOM", v.code());
        assertEquals(0, new BigDecimal("20").compareTo(v.bomQty()));
        assertEquals(0, new BigDecimal("20").compareTo(v.alreadyOrdered()));
        assertEquals(0, new BigDecimal("1").compareTo(v.requested()));
        assertEquals(0, new BigDecimal("1").compareTo(v.excess()));
    }

    /** Exactly reaching the BOM quantity is allowed — only going above it is not. */
    @Test
    void orderingExactlyTheRemainingQuantityIsAllowed() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        alreadyOrdered(1L, "15");
        assertTrue(guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "5", 1L)), null, Mode.BLOCK).ok());
    }

    /**
     * Check 6 — cancelled POs do not contribute. The exclusion lives in the SQL
     * predicate, so what this asserts is that with no live rows returned the line
     * passes; the predicate itself is covered by the query text.
     */
    @Test
    void cancelledPosDoNotContributeToTheTotal() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        // findLivePoLinesForProject returns nothing: the only prior PO was cancelled.
        assertTrue(guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "20", 1L)), null, Mode.BLOCK).ok());
    }

    /** Check 7 — quotation quantities never enter the total: no quotation repo is consulted. */
    @Test
    void quotationQuantitiesAreNeverCounted() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "20", 1L)), null, Mode.BLOCK);

        verify(poItemRepo).findLivePoLinesForProject(eq(PUID), anyLong());
        // Only purchase-order aggregates are read — there is no quotation source to double-count.
        verify(poItemRepo, never()).sumOrderedQtyByItemForQuotation(anyLong());
    }

    /** Two lines of the same BOM line must be summed, and reported once naming both. */
    @Test
    void duplicateLinesWithinOnePoAreAggregatedAgainstOneBomLine() {
        bomIs(bomLine(1, "PV Module 540Wp", "120"));
        CheckResult r = guard.check(PUID,
                List.of(linked(1, "PV Module 540Wp", "70", 1L), linked(2, "PV Module 540Wp", "70", 1L)),
                null, Mode.BLOCK);

        Violation v = only(r);
        assertEquals("EXCEEDS_BOM", v.code());
        assertEquals(0, new BigDecimal("140").compareTo(v.requested()));
        assertEquals(0, new BigDecimal("20").compareTo(v.excess()));
        assertEquals(List.of(1, 2), v.lineNos(), "the message must name every contributing line");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §6.4 self-exclusion on edit  (acceptance check 12)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void editingAPoExcludesThatWholePoFromTheAlreadyOrderedTotal() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        PurchaseOrderItemEntity prior = new PurchaseOrderItemEntity();
        prior.setLineNo(1);
        prior.setItemName("PV Module 540Wp");
        prior.setQuantity(new BigDecimal("20"));
        prior.setBomMatch("ID");
        prior.setBomLineId(1L);
        when(poItemRepo.findByPurchaseOrderId(77L)).thenReturn(List.of(prior));

        CheckResult r = guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "20", 1L)), 77L, Mode.BLOCK);

        assertTrue(r.ok(), "a PO's own previously ordered quantity must not count against itself");

        ArgumentCaptor<Long> excl = ArgumentCaptor.forClass(Long.class);
        verify(poItemRepo).findLivePoLinesForProject(eq(PUID), excl.capture());
        assertEquals(77L, excl.getValue());
    }

    @Test
    void creatingPassesTheNoExclusionSentinelRatherThanNull() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "1", 1L)), null, Mode.BLOCK);

        ArgumentCaptor<Long> excl = ArgumentCaptor.forClass(Long.class);
        verify(poItemRepo).findLivePoLinesForProject(eq(PUID), excl.capture());
        assertEquals(-1L, excl.getValue(), "an untyped NULL bind compares unreliably in native SQL");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §7 grandfathering  (acceptance check 13)
    // ═════════════════════════════════════════════════════════════════════════

    /** Check 13 — an old PO whose lines predate BOM linking opens and saves unchanged. */
    @Test
    void existingPoWithNoBomLinksSavesUnchanged() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        PurchaseOrderItemEntity legacy = new PurchaseOrderItemEntity();
        legacy.setLineNo(1);
        legacy.setItemName("Obsolete Bracket");
        legacy.setQuantity(new BigDecimal("9"));
        legacy.setBomMatch(null);                       // written before enforcement existed
        when(poItemRepo.findByPurchaseOrderId(77L)).thenReturn(List.of(legacy));

        CheckResult r = guard.check(PUID, List.of(line(1, "Obsolete Bracket", "9")), 77L, Mode.BLOCK);

        assertTrue(r.ok());
        assertEquals(Match.LEGACY, r.matchByLine().get(1));
        assertEquals(null, r.matchToPersist(1), "a grandfathered row keeps its NULL bom_match");
    }

    /** The same PO on a CREATE has no leniency — this is the §7 hole being closed. */
    @Test
    void aNewPoNeverInheritsLeniency() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        CheckResult r = guard.check(PUID, List.of(line(1, "Obsolete Bracket", "9")), null, Mode.BLOCK);
        assertFalse(r.ok(), "leniency applies only to purchase orders that already exist");
        assertEquals("NOT_IN_BOM", only(r).code());
    }

    /** Decision 5 — raising a legacy line is a new order and is checked strictly. */
    @Test
    void raisingTheQuantityOnALegacyLineIsBlocked() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        PurchaseOrderItemEntity legacy = new PurchaseOrderItemEntity();
        legacy.setLineNo(1);
        legacy.setItemName("Obsolete Bracket");
        legacy.setQuantity(new BigDecimal("9"));
        legacy.setBomMatch(null);
        when(poItemRepo.findByPurchaseOrderId(77L)).thenReturn(List.of(legacy));

        CheckResult r = guard.check(PUID, List.of(line(1, "Obsolete Bracket", "50")), 77L, Mode.BLOCK);

        assertEquals("LEGACY_INCREASE", only(r).code());
    }

    /**
     * The anti-shuffle guard: PO items merge positionally, so a brand-new line that
     * lands on a legacy line's position must NOT inherit its leniency.
     */
    @Test
    void aDifferentItemAtALegacyLinePositionIsNotGrandfathered() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        PurchaseOrderItemEntity legacy = new PurchaseOrderItemEntity();
        legacy.setLineNo(1);
        legacy.setItemName("Obsolete Bracket");
        legacy.setQuantity(new BigDecimal("9"));
        legacy.setBomMatch(null);
        when(poItemRepo.findByPurchaseOrderId(77L)).thenReturn(List.of(legacy));

        CheckResult r = guard.check(PUID, List.of(line(1, "Something Entirely New", "9")), 77L, Mode.BLOCK);

        assertEquals("NOT_IN_BOM", only(r).code());
    }

    /** A row stamped by this feature is not legacy, so it gets no leniency on edit. */
    @Test
    void aStampedRowIsNotGrandfathered() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        PurchaseOrderItemEntity stamped = new PurchaseOrderItemEntity();
        stamped.setLineNo(1);
        stamped.setItemName("Gone From Bom");
        stamped.setQuantity(new BigDecimal("5"));
        stamped.setBomMatch("NAME");                    // written after enforcement shipped
        when(poItemRepo.findByPurchaseOrderId(77L)).thenReturn(List.of(stamped));

        CheckResult r = guard.check(PUID, List.of(line(1, "Gone From Bom", "5")), 77L, Mode.BLOCK);
        assertEquals("NOT_IN_BOM", only(r).code());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Matching  (decision 2)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void matchesOnCatalogueItemAndVariantBeforeName() {
        ProjectBomEntity a = bomLine(1, "Inverter", "5");
        a.setBomItemId(900L); a.setVariantId(11L);
        ProjectBomEntity b = bomLine(2, "Inverter", "7");
        b.setBomItemId(900L); b.setVariantId(22L);
        bomIs(a, b);

        CheckResult r = guard.check(PUID,
                List.of(new DocLine(1, "Inverter", null, "Nos", new BigDecimal("7"), null, 900L, 22L)), null, Mode.BLOCK);

        assertTrue(r.ok(), "must consume the qty-7 line for variant 22, not the qty-5 line for variant 11");
        assertEquals(2L, r.bomLineIdByLine().get(1));
        assertEquals(Match.VARIANT, r.matchByLine().get(1));
    }

    @Test
    void fallsBackToCaseInsensitiveTrimmedName() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        CheckResult r = guard.check(PUID, List.of(line(1, "  pv MODULE 540wp  ", "20")), null, Mode.BLOCK);

        assertTrue(r.ok());
        assertEquals(1L, r.bomLineIdByLine().get(1));
        assertEquals(Match.NAME, r.matchByLine().get(1));
    }

    @Test
    void duplicateNamesResolveDeterministicallyToTheLowestSeqNo() {
        ProjectBomEntity later = bomLine(9, "Cable", "50");
        ProjectBomEntity first = bomLine(2, "Cable", "10");
        bomIs(later, first);   // deliberately out of order

        CheckResult r = guard.check(PUID, List.of(line(1, "Cable", "10")), null, Mode.BLOCK);
        assertEquals(2L, r.bomLineIdByLine().get(1), "lowest seqNo wins, so consumption and checking agree");
    }

    /** A stored link to a BOM line that has since been soft-deleted must not pass silently. */
    @Test
    void aStoredLinkToADeletedBomLineIsReported() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        CheckResult r = guard.check(PUID, List.of(linked(1, "Vanished Item", "3", 999L)), null, Mode.BLOCK);
        assertEquals("BOM_LINE_GONE", only(r).code());
    }

    /** Unlinked legacy PO rows still consume budget, matched onto BOM lines by name. */
    @Test
    void unlinkedLegacyQuantitiesStillConsumeBomBudget() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(null, "  pv module 540wp ", "20", null)));

        Violation v = only(guard.check(PUID, List.of(linked(1, "PV Module 540Wp", "1", 1L)), null, Mode.BLOCK));
        assertEquals("EXCEEDS_BOM", v.code());
        assertEquals(0, new BigDecimal("20").compareTo(v.alreadyOrdered()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Picker feed  (acceptance check 2 and §7 rate gating)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void availabilityReportsBomOrderedAndRemaining() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        alreadyOrdered(1L, "12");

        BomAvailability a = guard.availability(PUID, null, true).get(0);
        assertEquals(0, new BigDecimal("20").compareTo(a.bomQty()));
        assertEquals(0, new BigDecimal("12").compareTo(a.alreadyOrdered()));
        assertEquals(0, new BigDecimal("8").compareTo(a.remaining()));
    }

    @Test
    void remainingIsClampedAtZeroWhenAlreadyOverOrdered() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        alreadyOrdered(1L, "25");
        assertEquals(0, BigDecimal.ZERO.compareTo(guard.availability(PUID, null, true).get(0).remaining()));
    }

    @Test
    void ratesAreOmittedForGatedRoles() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        BomAvailability gated = guard.availability(PUID, null, false).get(0);
        assertEquals(null, gated.unitRate(), "a role that cannot see rates on the BOM tab must not see them here");
        assertEquals(null, gated.amount());

        BomAvailability open = guard.availability(PUID, null, true).get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(open.unitRate()));
    }

    @Test
    void availabilityIsEmptyForAnUnknownProject() {
        when(projectRepo.findByProjectUniqueId("NOPE")).thenReturn(Optional.empty());
        assertTrue(guard.availability("NOPE", null, true).isEmpty());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Adapters
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void poItemMapsBecomeOneBasedDocLines() {
        List<DocLine> lines = BomProcurementGuard.fromPoItemMaps(List.of(
                java.util.Map.of("itemName", "A", "quantity", "5", "bomLineId", 7),
                java.util.Map.of("itemName", "B", "quantity", 2.5)));

        assertEquals(2, lines.size());
        assertEquals(1, lines.get(0).lineNo());
        assertEquals(7L, lines.get(0).bomLineId());
        assertEquals(0, new BigDecimal("5").compareTo(lines.get(0).quantity()));
        assertEquals(2, lines.get(1).lineNo());
        assertEquals(null, lines.get(1).bomLineId());
        assertEquals(0, new BigDecimal("2.5").compareTo(lines.get(1).quantity()));
    }

    @Test
    void warnModeReportsWithoutThrowing() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        CheckResult r = guard.warn(PUID, List.of(line(1, "Off Bom Item", "3")));
        assertFalse(r.ok(), "warnings are still reported");
        // no exception — quotations must save regardless
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §A1 — a lenient line still CONSUMES BOM quantity
    //
    //  Leniency means "this line cannot be blocked". It has never meant "this
    //  line's quantity does not exist". Acceptance checks 1-3 and 6.
    // ═════════════════════════════════════════════════════════════════════════

    /** A PO line written before enforcement existed, sitting at line_no 1. */
    private void legacyLineOnPo(long poId, String itemName, String qty) {
        PurchaseOrderItemEntity legacy = new PurchaseOrderItemEntity();
        legacy.setLineNo(1);
        legacy.setItemName(itemName);
        legacy.setQuantity(new BigDecimal(qty));
        legacy.setBomMatch(null);           // predates enforcement
        legacy.setBomLineId(null);          // and was never linked
        when(poItemRepo.findByPurchaseOrderId(poId)).thenReturn(List.of(legacy));
    }

    /** Check 1 — the legacy line fills the BOM, so a further line cannot be added. */
    @Test
    void aLegacyLineFillingTheBomBlocksAddingAnotherLineForTheSameItem() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        legacyLineOnPo(77L, "PV Module 540Wp", "20");

        CheckResult r = guard.check(PUID, List.of(
                line(1, "PV Module 540Wp", "20"),                    // untouched legacy line
                linked(2, "PV Module 540Wp", "5", 1L)),              // newly picked from the BOM
                77L, Mode.BLOCK);

        Violation v = only(r);
        assertEquals("EXCEEDS_BOM", v.code());
        assertEquals(0, new BigDecimal("25").compareTo(v.requested()),
                "the legacy line's 20 must be counted alongside the new 5");
        assertEquals(0, new BigDecimal("5").compareTo(v.excess()));
        assertEquals(List.of(2), v.lineNos(),
                "only the non-lenient line may be blamed; the legacy line is not blockable");
    }

    /** Check 2 — the same PO still saves when the legacy line is merely left alone. */
    @Test
    void aPoWhoseLegacyLineIsLeftUntouchedStillSaves() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        legacyLineOnPo(77L, "PV Module 540Wp", "20");

        CheckResult r = guard.check(PUID, List.of(line(1, "PV Module 540Wp", "20")), 77L, Mode.BLOCK);

        assertTrue(r.ok(), "an untouched pre-enforcement line is never the one blamed");
        assertEquals(Match.LEGACY, r.matchByLine().get(1));
        assertEquals(null, r.matchToPersist(1), "its NULL bom_match must survive the save");
    }

    /**
     * Check 2, the case that matters most in practice: the legacy line is ALREADY over
     * the BOM (it predates enforcement, or the BOM was cut afterwards). Counting its
     * quantity must not turn "leave it alone" into a block.
     */
    @Test
    void anUntouchedLegacyLineAlreadyOverTheBomStillSaves() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        legacyLineOnPo(77L, "PV Module 540Wp", "25");

        assertTrue(guard.check(PUID, List.of(line(1, "PV Module 540Wp", "25")), 77L, Mode.BLOCK).ok());
    }

    /** Check 3 — reducing a legacy line's quantity is still permitted. */
    @Test
    void reducingALegacyLineQuantityStillSaves() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        legacyLineOnPo(77L, "PV Module 540Wp", "25");

        CheckResult r = guard.check(PUID, List.of(line(1, "PV Module 540Wp", "15")), 77L, Mode.BLOCK);
        assertTrue(r.ok());
        assertEquals(Match.LEGACY, r.matchByLine().get(1));
    }

    /**
     * Check 6 — attribution and resolution agree. The legacy line on ANOTHER live PO is
     * folded onto the same BOM line the incoming line resolves to, so the two views of
     * "which line does this consume?" cannot diverge.
     */
    @Test
    void legacyAttributionAndLineResolutionAgreeOnTheSameBomLine() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        // A different, live PO holds an unlinked legacy row for 18.
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(null, "PV Module 540Wp", "18", null)));

        Violation v = only(guard.check(PUID, List.of(line(1, "PV Module 540Wp", "5")), null, Mode.BLOCK));
        assertEquals("EXCEEDS_BOM", v.code());
        assertEquals(1L, v.bomLineId());
        assertEquals(0, new BigDecimal("18").compareTo(v.alreadyOrdered()));
        assertEquals(0, new BigDecimal("3").compareTo(v.excess()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  §A2 — the fallback match compares name AND make AND unit
    // ═════════════════════════════════════════════════════════════════════════

    private static ProjectBomEntity bomLineWithMake(long id, String name, String make, String qty) {
        ProjectBomEntity b = bomLine(id, name, qty);
        b.setMake(make);
        return b;
    }

    private static DocLine typed(int no, String name, String make, String unit, String qty) {
        return new DocLine(no, name, make, unit, new BigDecimal(qty), null, null, null);
    }

    /**
     * Check 4 — two BOM lines share an item name but differ in make, so each keeps its
     * own budget. Name-only matching would have sent both to the lowest seqNo line,
     * over-consuming one scope while the other still looked untouched.
     */
    @Test
    void bomLinesSharingANameButDifferingInMakeEachKeepTheirOwnBudget() {
        bomIs(bomLineWithMake(1, "DC Cable 4sqmm", "Polycab", "500"),
              bomLineWithMake(2, "DC Cable 4sqmm", "Havells", "300"));

        CheckResult r = guard.check(PUID,
                List.of(typed(1, "DC Cable 4sqmm", "Havells", "Nos", "300")), null, Mode.BLOCK);

        assertTrue(r.ok());
        assertEquals(2L, r.bomLineIdByLine().get(1),
                "the Havells line must be consumed, not the first DC Cable line on the BOM");
    }

    /** The converse: a hand-typed line for one make must not eat the other's budget. */
    @Test
    void aHandTypedLineDoesNotConsumeTheOtherMakesBudget() {
        bomIs(bomLineWithMake(1, "DC Cable 4sqmm", "Polycab", "500"),
              bomLineWithMake(2, "DC Cable 4sqmm", "Havells", "300"));
        // Someone already ordered the whole Polycab line.
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(1L, "DC Cable 4sqmm", "500", "ID")));

        assertTrue(guard.check(PUID, List.of(typed(1, "DC Cable 4sqmm", "Havells", "Nos", "300")),
                               null, Mode.BLOCK).ok(),
                "the Havells budget is untouched by what was ordered against Polycab");
    }

    /** Unit disagreement is enough to refuse the fallback: m and Nos are not the same line. */
    @Test
    void aDifferentUnitIsNotTheSameBomLine() {
        ProjectBomEntity b = bomLine(1, "DC Cable 4sqmm", "500");
        b.setUnit("m");
        bomIs(b);

        CheckResult r = guard.check(PUID,
                List.of(typed(1, "DC Cable 4sqmm", null, "Nos", "10")), null, Mode.BLOCK);
        assertEquals("NOT_IN_BOM", only(r).code());
    }

    /**
     * A blank make or unit on either side matches anything — older BOM lines and legacy
     * PO rows routinely recorded neither, and refusing those would strand exactly the
     * rows the fallback exists for.
     */
    @Test
    void aBlankMakeOrUnitMatchesAnything() {
        bomIs(bomLineWithMake(1, "PV Module 540Wp", "Adani", "20"));

        CheckResult r = guard.check(PUID,
                List.of(typed(1, "PV Module 540Wp", null, null, "20")), null, Mode.BLOCK);
        assertTrue(r.ok());
        assertEquals(1L, r.bomLineIdByLine().get(1));
    }

    /**
     * Check 5 — a line resolved by the fallback is REPORTED with the BOM line it
     * matched, so the user can confirm or correct it before saving. A picked line is
     * not reported: there is nothing to confirm about a catalogue reference.
     */
    @Test
    void aFallbackResolvedLineIsReportedWithTheBomLineItMatched() {
        bomIs(bomLineWithMake(1, "DC Cable 4sqmm", "Polycab", "500"));
        alreadyOrderedAgainst(1L, "200");

        CheckResult r = guard.check(PUID,
                List.of(typed(1, "DC Cable 4sqmm", "Polycab", "Nos", "50"),
                        linked(2, "DC Cable 4sqmm", "10", 1L)),
                null, Mode.BLOCK);

        assertEquals(1, r.fallbackMatches().size(), "only the inferred match needs confirming");
        BomProcurementGuard.FallbackMatch f = r.fallbackMatches().get(0);
        assertEquals(1, f.lineNo());
        assertEquals(1L, f.bomLineId());
        assertEquals("DC Cable 4sqmm", f.bomItemName());
        assertEquals("Polycab", f.bomMake());
        assertEquals(0, new BigDecimal("500").compareTo(f.bomQty()));
        assertEquals(0, new BigDecimal("200").compareTo(f.alreadyOrdered()));
        assertEquals(0, new BigDecimal("300").compareTo(f.remaining()));
    }

    /** A PO row that consumed a BOM line by an explicit link, on some other live PO. */
    private void alreadyOrderedAgainst(long bomLineId, String qty) {
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(bomLineId, "Prior Item", qty, "ID")));
    }

    /** A correction the user makes (re-picking the BOM line) resolves as a picked line. */
    @Test
    void aCorrectedMatchArrivesAsAPickedLineAndIsNotReportedAgain() {
        bomIs(bomLineWithMake(1, "DC Cable 4sqmm", "Polycab", "500"),
              bomLineWithMake(2, "DC Cable 4sqmm", "Havells", "300"));

        CheckResult r = guard.check(PUID,
                List.of(new DocLine(1, "DC Cable 4sqmm", "Polycab", "Nos", new BigDecimal("50"), 2L, null, null)),
                null, Mode.BLOCK);

        assertTrue(r.fallbackMatches().isEmpty());
        assertEquals(2L, r.bomLineIdByLine().get(1), "the user's correction wins over the inference");
        assertEquals(Match.ID, r.matchByLine().get(1));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Attribution — the figure the planned-vs-actual screen reports
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Check 20 — the procured quantity the screen shows for a BOM line IS the
     * already-ordered figure the guard enforces, because both read one attribution.
     */
    @Test
    void attributionMatchesTheAlreadyOrderedFigureTheGuardEnforces() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(1L, "PV Module 540Wp", "8", "ID"),
                                          poRow(null, "PV Module 540Wp", "4", null)));

        BigDecimal attributed = guard.attributionFor(PUID, null).orderedByBomLine().get(1L);
        BigDecimal enforced   = guard.availability(PUID, null, true).get(0).alreadyOrdered();

        assertEquals(0, new BigDecimal("12").compareTo(attributed), "8 linked + 4 folded on by name");
        assertEquals(0, attributed.compareTo(enforced), "the two must never disagree");
    }

    /** A PO line matching nothing on the BOM is reported as unattributed, not dropped. */
    @Test
    void anUnattributablePoLineIsReportedRatherThanDropped() {
        bomIs(bomLine(1, "PV Module 540Wp", "20"));
        when(poItemRepo.findLivePoLinesForProject(anyString(), anyLong()))
            .thenReturn(List.<Object[]>of(poRow(null, "Site Office Rent", "1", null)));

        BomProcurementGuard.Attribution a = guard.attributionFor(PUID, null);
        assertEquals(1, a.lines().size());
        assertEquals(null, a.lines().get(0).bomLineId());
        assertTrue(a.orderedByBomLine().isEmpty());
    }
}
