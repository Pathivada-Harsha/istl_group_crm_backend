package com.istlgroup.istl_group_crm_backend.service.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadTemplateWrapper.TemplateScopeSubItemRequest;

/**
 * The scope-line sub-item breakdown shared by templates, leads and project phases:
 * its weight rules, its JSON round trip, and the re-suggest merge.
 *
 * No Mockito and no Spring — everything under test is pure, so the component is built
 * with a real ObjectMapper and nothing else. (Mockito's inline mock-maker self-attach is
 * flaky on this host, and a context load would need a database for what is arithmetic
 * and string handling.)
 */
class ScopeSubItemsTest {

    private final ScopeSubItems svc = new ScopeSubItems(new ObjectMapper());

    private static TemplateScopeSubItemRequest sub(String name, String weight, boolean manual) {
        TemplateScopeSubItemRequest s = new TemplateScopeSubItemRequest();
        s.setName(name);
        s.setWeightPct(weight == null ? null : new BigDecimal(weight));
        s.setWeightManual(manual);
        return s;
    }

    private static List<TemplateScopeSubItemRequest> list(TemplateScopeSubItemRequest... s) {
        return new ArrayList<>(List.of(s));
    }

    // ── Weights ──────────────────────────────────────────────────────────────

    @Test
    void noBreakdownIsLeftAlone() throws Exception {
        // Sub-items are opt-in, so a template where nothing is broken down must
        // still save — neither null nor empty is an error.
        svc.normaliseWeights("Civil Works", null);
        svc.normaliseWeights("Civil Works", new ArrayList<>());
    }

    @Test
    void weightlessSubItemsGetAnEvenSplitTotalling100() throws Exception {
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", null, false), sub("PCC", null, false), sub("Backfill", null, false));

        svc.normaliseWeights("Civil Works", subs);

        BigDecimal total = BigDecimal.ZERO;
        for (TemplateScopeSubItemRequest s : subs) total = total.add(s.getWeightPct());
        assertEquals(0, total.compareTo(new BigDecimal("100")),
                "an even split must land on exactly 100, not 99.999999");
        for (TemplateScopeSubItemRequest s : subs) assertEquals(Boolean.FALSE, s.getWeightManual());
    }

    @Test
    void subWeightsAreAShareOfTheParentNotOfTheTemplate() throws Exception {
        // The whole point of the second level: 100 here means "all of this
        // activity". A group summing to the parent's own weight would be wrong.
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", "60", true), sub("PCC", "40", true));
        svc.normaliseWeights("Civil Works", subs);
        assertEquals(0, subs.get(0).getWeightPct().compareTo(new BigDecimal("60")));
        assertEquals(0, subs.get(1).getWeightPct().compareTo(new BigDecimal("40")));
    }

    @Test
    void displayRoundingIsAbsorbedOntoTheLargest() throws Exception {
        // Retyping the values shown to two decimals enters slightly different
        // numbers; that much drift is the UI's fault, not the user's.
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", "33.33", true), sub("PCC", "33.33", true), sub("Backfill", "33.33", true));

        svc.normaliseWeights("Civil Works", subs);

        BigDecimal total = BigDecimal.ZERO;
        for (TemplateScopeSubItemRequest s : subs) total = total.add(s.getWeightPct());
        assertEquals(0, total.compareTo(new BigDecimal("100")));
    }

    @Test
    void aRealMistakeIsRejectedAndNamesTheActivity() {
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", "60", true), sub("PCC", "60", true));
        CustomException e = assertThrows(CustomException.class,
                () -> svc.normaliseWeights("Civil Works", subs));
        assertTrue(e.getMessage().contains("Civil Works"), e.getMessage());
        assertTrue(e.getMessage().contains("120"), e.getMessage());
    }

    @Test
    void aZeroWeightSubItemIsRejectedByName() {
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", "100", true), sub("PCC", "0", true));
        CustomException e = assertThrows(CustomException.class,
                () -> svc.normaliseWeights("Civil Works", subs));
        assertTrue(e.getMessage().contains("PCC"), e.getMessage());
    }

    @Test
    void anUnnamedSubItemIsRejected() {
        List<TemplateScopeSubItemRequest> subs = list(sub("  ", "100", true));
        assertThrows(CustomException.class, () -> svc.normaliseWeights("Civil Works", subs));
    }

    // ── JSON round trip ──────────────────────────────────────────────────────

    @Test
    void emptyBreakdownStoresNullNotAnEmptyArray() throws Exception {
        assertNull(svc.serialise(null));
        assertNull(svc.serialise(new ArrayList<>()));
    }

    @Test
    void roundTripKeepsNameWeightAndOrder() throws Exception {
        List<TemplateScopeSubItemRequest> subs =
                list(sub("Excavation", "60", true), sub("PCC", "40", false));
        subs.get(0).setUnit("Lot");
        subs.get(0).setDescription("To 1.5 m");

        List<Map<String, Object>> back = svc.parse(svc.serialise(subs));

        assertEquals(2, back.size());
        assertEquals("Excavation", back.get(0).get("name"));
        assertEquals("Lot", back.get(0).get("unit"));
        assertEquals("To 1.5 m", back.get(0).get("description"));
        assertEquals(Boolean.TRUE, back.get(0).get("weightManual"));
        assertEquals(0, new BigDecimal(String.valueOf(back.get(0).get("weightPct"))).compareTo(new BigDecimal("60")));
        assertEquals("PCC", back.get(1).get("name"), "order is the reading order and must survive");
        assertEquals(Boolean.FALSE, back.get(1).get("weightManual"));
    }

    // ── Re-suggest merge ─────────────────────────────────────────────────────

    private static Map<String, Object> stored(String name, Object budget, Object progress) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("weightPct", new BigDecimal("50"));
        if (budget != null) m.put("plannedBudget", budget);
        if (progress != null) m.put("progressPercent", progress);
        return m;
    }

    private static Map<String, Object> incoming(String name, String weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("weightPct", new BigDecimal(weight));
        m.put("description", "from the template");
        return m;
    }

    @Test
    void mergeKeepsBudgetAndProgressOfAMatchedSubItem() {
        // The whole point: re-suggesting must not wipe what the job has accumulated.
        List<Map<String, Object>> out = svc.mergePreservingExecutionData(
                List.of(stored("Excavation", "1200", 40)),
                List.of(incoming("Excavation", "70")));

        assertEquals(1, out.size());
        assertEquals("1200", String.valueOf(out.get(0).get("plannedBudget")));
        assertEquals(40, out.get(0).get("progressPercent"));
        // …while the template still gets to redefine the parts it owns.
        assertEquals(0, new BigDecimal(String.valueOf(out.get(0).get("weightPct"))).compareTo(new BigDecimal("70")));
        assertEquals("from the template", out.get(0).get("description"));
    }

    @Test
    void mergeMatchesCaseAndWhitespaceInsensitivelyButKeepsTheStoredName() {
        // Name IS the identity for project_progress_periods.sub_item_key and the
        // planned-budget merge, which compare with exact String.equals. So a match
        // must keep the STORED spelling byte-for-byte, or those links break.
        List<Map<String, Object>> out = svc.mergePreservingExecutionData(
                List.of(stored("Excavation", "1200", 40)),
                List.of(incoming("  excavation  ", "70")));

        assertEquals(1, out.size());
        assertEquals("Excavation", out.get(0).get("name"));
        assertEquals("1200", String.valueOf(out.get(0).get("plannedBudget")));
    }

    @Test
    void mergeAddsNewNamesAndDropsAbsentOnes() {
        List<Map<String, Object>> out = svc.mergePreservingExecutionData(
                List.of(stored("Excavation", "1200", 40), stored("Retired Item", "999", 10)),
                List.of(incoming("Excavation", "50"), incoming("Shuttering", "50")));

        assertEquals(2, out.size());
        assertEquals("Excavation", out.get(0).get("name"));
        assertEquals("Shuttering", out.get(1).get("name"), "a genuinely new sub-item is added");
        assertNull(out.get(1).get("plannedBudget"), "a new sub-item starts with no budget");
        for (Map<String, Object> si : out) {
            assertTrue(!"Retired Item".equals(si.get("name")), "a dropped sub-item does not survive");
        }
    }

    @Test
    void mergeFollowsTheIncomingOrder() {
        List<Map<String, Object>> out = svc.mergePreservingExecutionData(
                List.of(stored("B", null, null), stored("A", null, null)),
                List.of(incoming("A", "50"), incoming("B", "50")));
        assertEquals("A", out.get(0).get("name"));
        assertEquals("B", out.get(1).get("name"));
    }

    @Test
    void mergeWithNothingIncomingClearsTheBreakdown() {
        // "The template no longer breaks this activity down" is a real answer, and
        // the confirm dialog already told the user the scope is being replaced.
        assertEquals(List.of(), svc.mergePreservingExecutionData(List.of(stored("Excavation", "1", 1)), List.of()));
        assertEquals(List.of(), svc.mergePreservingExecutionData(List.of(stored("Excavation", "1", 1)), null));
    }

    @Test
    void mergeOntoNothingStoredIsJustTheIncomingSet() {
        List<Map<String, Object>> out =
                svc.mergePreservingExecutionData(null, List.of(incoming("Excavation", "100")));
        assertEquals(1, out.size());
        assertEquals("Excavation", out.get(0).get("name"));
    }

    @Test
    void unreadableStoredJsonDoesNotStopTheTemplateLoading() {
        // The column was dormant storage before this editor existed, so whatever
        // is already in it is not guaranteed to parse — and one bad row must not
        // take the whole template down.
        assertEquals(List.of(), svc.parse("not json at all"));
        assertEquals(List.of(), svc.parse(""));
        assertEquals(List.of(), svc.parse(null));
    }
}
