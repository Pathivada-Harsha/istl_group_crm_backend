package com.istlgroup.istl_group_crm_backend.service.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The scope breakdown as a TREE of arbitrary depth: node identity, per-parent weights at
 * every level, the recursive roll-up, and the per-parent re-suggest merge.
 *
 * <p>These are the guarantees the nesting work exists to provide, so each test is written
 * against the failure it prevents rather than against the implementation. The one that
 * matters most is {@link #sameNameInDifferentBranchesAreDifferentNodes} — a name-keyed
 * store would merge those two nodes' progress and money, which is the whole reason
 * identity moved off the name.
 *
 * <p>No Mockito and no Spring, matching {@link ScopeSubItemsTest}: everything here is pure.
 */
class ScopeTreeTest {

    private final ScopeSubItems svc = new ScopeSubItems(new ObjectMapper());

    // ── builders ─────────────────────────────────────────────────────────────

    private static TemplateScopeSubItemRequest node(String name, String weight,
                                                    TemplateScopeSubItemRequest... kids) {
        TemplateScopeSubItemRequest s = new TemplateScopeSubItemRequest();
        s.setName(name);
        s.setWeightPct(weight == null ? null : new BigDecimal(weight));
        s.setWeightManual(weight != null);
        if (kids.length > 0) s.setChildren(new ArrayList<>(List.of(kids)));
        return s;
    }

    private static List<TemplateScopeSubItemRequest> list(TemplateScopeSubItemRequest... s) {
        return new ArrayList<>(List.of(s));
    }

    /** A parsed-form node, as it comes back off the sub_items column. */
    private static Map<String, Object> m(String id, String name, Object... kv) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("name", name);
        for (int i = 0; i + 1 < kv.length; i += 2) n.put(String.valueOf(kv[i]), kv[i + 1]);
        return n;
    }

    @SafeVarargs
    private static List<Map<String, Object>> kids(Map<String, Object>... c) {
        return new ArrayList<>(List.of(c));
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    void everyNodeAtEveryDepthGetsAnId() {
        List<TemplateScopeSubItemRequest> tree = list(
                node("Electrical Works", null,
                        node("PV Module", null,
                                node("Purchase Order", null),
                                node("Receiving", null))),
                node("Civil Works", null));

        svc.ensureIds(tree);

        assertNotNull(tree.get(0).getId());
        assertNotNull(tree.get(1).getId());
        assertNotNull(tree.get(0).getChildren().get(0).getId());
        assertNotNull(tree.get(0).getChildren().get(0).getChildren().get(0).getId());
        assertNotNull(tree.get(0).getChildren().get(0).getChildren().get(1).getId());
    }

    @Test
    void ensureIdsIsIdempotentSoAnEditNeverRepointsHistory() {
        // The single most important property of the id: it is minted once and then never
        // reassigned. If a save re-minted ids, every progress row and budget on the node
        // would be orphaned — silently, because nothing holds a foreign key to it.
        List<TemplateScopeSubItemRequest> tree = list(node("PV Module", null, node("Payment", null)));
        svc.ensureIds(tree);
        String parentId = tree.get(0).getId();
        String childId = tree.get(0).getChildren().get(0).getId();

        svc.ensureIds(tree);
        svc.ensureIds(tree);

        assertEquals(parentId, tree.get(0).getId());
        assertEquals(childId, tree.get(0).getChildren().get(0).getId());
    }

    @Test
    void sameNameInDifferentBranchesAreDifferentNodes() {
        // The failure this whole change exists to prevent. A real schedule repeats
        // "Payment" under many parents; keyed by name, those two would share one progress
        // row and one budget, and money entered under Civil would appear under Electrical.
        List<TemplateScopeSubItemRequest> tree = list(
                node("Civil Works", null, node("Payment", null)),
                node("Electrical Works", null, node("Payment", null)));

        svc.ensureIds(tree);

        String a = tree.get(0).getChildren().get(0).getId();
        String b = tree.get(1).getChildren().get(0).getId();
        assertNotEquals(a, b, "two nodes that share a name must still be two nodes");
    }

    @Test
    void indexByIdFindsNodesAtEveryDepth() {
        List<Map<String, Object>> tree = kids(
                m("p1", "Electrical", "children", kids(
                        m("c1", "PV Module", "children", kids(
                                m("g1", "Purchase Order"))))));

        Map<String, Map<String, Object>> idx = svc.indexById(tree);

        assertEquals(3, idx.size());
        assertEquals("Purchase Order", idx.get("g1").get("name"));
    }

    // ── Weights, per parent, at every level ──────────────────────────────────

    @Test
    void eachParentIsItsOwnWeightGroupTotalling100() throws Exception {
        // A branch broken down further must not disturb any other branch: the children of
        // one parent share 100% OF THAT PARENT, not of the scope.
        List<TemplateScopeSubItemRequest> tree = list(
                node("Electrical Works", null,
                        node("PV Module", null), node("Inverter", null), node("Cabling", null)),
                node("Civil Works", null,
                        node("Excavation", null), node("PCC", null)));

        svc.normaliseWeights("Solar Plant", tree);

        assertEquals(0, sum(tree).compareTo(new BigDecimal("100")));
        assertEquals(0, sum(tree.get(0).getChildren()).compareTo(new BigDecimal("100")));
        assertEquals(0, sum(tree.get(1).getChildren()).compareTo(new BigDecimal("100")));
    }

    @Test
    void aBadWeightDeepInTheTreeNamesItsOwnBranch() {
        // The message has to point at the branch to go and fix. With several levels, "the
        // weights are wrong" is useless — the user cannot find which of thirty groups it is.
        List<TemplateScopeSubItemRequest> tree = list(
                node("Electrical Works", "100",
                        node("PV Module", "30"), node("Inverter", "30")));   // 60, not 100

        CustomException e = assertThrows(CustomException.class,
                () -> svc.normaliseWeights("Solar Plant", tree));

        assertTrue(e.getMessage().contains("Electrical Works"),
                "should name the offending parent, was: " + e.getMessage());
    }

    @Test
    void aDeeperBranchIsNamedByItsFullPath() {
        List<TemplateScopeSubItemRequest> tree = list(
                node("Electrical Works", "100",
                        node("PV Module", "100",
                                node("Purchase Order", "20"), node("Receiving", "20"))));

        CustomException e = assertThrows(CustomException.class,
                () -> svc.normaliseWeights("Solar", tree));

        assertTrue(e.getMessage().contains("PV Module"), e.getMessage());
        assertTrue(e.getMessage().contains("Electrical Works"),
                "the breadcrumb should locate the branch, was: " + e.getMessage());
    }

    // ── Recursive roll-up ────────────────────────────────────────────────────

    @Test
    void progressRollsUpThroughEveryLevel() {
        // Two levels below the phase. The mid node carries NO progress of its own — its
        // value is its children's weighted average, and a single-level read would have
        // reported this branch as 0% while its leaves were finished.
        List<Map<String, Object>> tree = kids(
                m("p1", "Electrical", "weightPct", 50, "children", kids(
                        m("c1", "PV Module", "weightPct", 50, "children", kids(
                                m("g1", "Purchase Order", "weightPct", 50, "progressPercent", 100),
                                m("g2", "Receiving", "weightPct", 50, "progressPercent", 0))),
                        m("c2", "Inverter", "weightPct", 50, "progressPercent", 100))),
                m("p2", "Civil", "weightPct", 50, "progressPercent", 0));

        // Electrical = (PV Module 50 × 50 + Inverter 100 × 50)/100 = 75
        // total      = (75 × 50 + 0 × 50)/100 = 37.5
        BigDecimal rolled = svc.rollUp(tree, "progressPercent");

        assertEquals(0, rolled.compareTo(new BigDecimal("37.5")), "was " + rolled);
    }

    @Test
    void aParentsOwnStaleProgressNeverOutranksItsChildren() {
        // A parent that once had a typed figure and later gained a breakdown must report
        // the breakdown, or the screen shows a number nobody can explain.
        List<Map<String, Object>> tree = kids(
                m("p1", "Electrical", "weightPct", 100, "progressPercent", 90, "children", kids(
                        m("c1", "A", "weightPct", 50, "progressPercent", 0),
                        m("c2", "B", "weightPct", 50, "progressPercent", 0))));

        assertEquals(0, svc.rollUp(tree, "progressPercent").compareTo(BigDecimal.ZERO));
    }

    @Test
    void budgetSumsUpTheTreeRatherThanAveraging() {
        // Money adds; percentages average. Getting these the same way round is the
        // difference between a project total and nonsense.
        List<Map<String, Object>> tree = kids(
                m("p1", "Electrical", "children", kids(
                        m("c1", "PV Module", "children", kids(
                                m("g1", "PO", "plannedBudget", 100),
                                m("g2", "Receiving", "plannedBudget", 50))),
                        m("c2", "Inverter", "plannedBudget", 25))),
                m("p2", "Civil", "plannedBudget", 25));

        assertEquals(0, svc.sumLeaves(tree, "plannedBudget").compareTo(new BigDecimal("200")));
    }

    // ── Re-suggest merge, per parent, down the tree ──────────────────────────

    @Test
    void mergeKeepsExecutionDataAtDepthAndMatchesWithinTheParent() {
        List<Map<String, Object>> existing = kids(
                m("p1", "Electrical", "children", kids(
                        m("c1", "PV Module", "children", kids(
                                m("g1", "Payment", "progressPercent", 80, "plannedBudget", 500))))),
                m("p2", "Civil", "children", kids(
                        m("g9", "Payment", "progressPercent", 10, "plannedBudget", 20))));

        // The template comes back with the same shape and no execution data.
        List<Map<String, Object>> incoming = kids(
                m("p1", "Electrical", "children", kids(
                        m("c1", "PV Module", "children", kids(m("g1", "Payment"))))),
                m("p2", "Civil", "children", kids(m("g9", "Payment"))));

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        Map<String, Map<String, Object>> idx = svc.indexById(merged);
        // Each "Payment" keeps its OWN numbers — they were never pooled by name.
        assertEquals(80, idx.get("g1").get("progressPercent"));
        assertEquals(500, idx.get("g1").get("plannedBudget"));
        assertEquals(10, idx.get("g9").get("progressPercent"));
        assertEquals(20, idx.get("g9").get("plannedBudget"));
    }

    @Test
    void aRenamedNodeKeepsItsIdentityAndItsHistory() {
        // Renaming used to delete a sub-item's progress outright. Matched by id, the new
        // label is simply adopted and the history rides along.
        List<Map<String, Object>> existing = kids(
                m("c1", "Payment", "progressPercent", 60));
        List<Map<String, Object>> incoming = kids(
                m("c1", "Advance Payment"));

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        assertEquals("c1", merged.get(0).get("id"));
        assertEquals(60, merged.get(0).get("progressPercent"));
    }

    @Test
    void aNodeWithNoIdStillMatchesByNameOnceSoLegacyDataSurvives() {
        // Pre-migration rows, and anything typed by hand on both sides, have no id to
        // match on. The name fallback carries them through their first merge; after it
        // they have an id and never rely on the name again.
        List<Map<String, Object>> existing = kids(m("old1", "Excavation", "progressPercent", 40));
        List<Map<String, Object>> incoming = new ArrayList<>();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("name", "excavation ");     // different case and spacing, same work
        incoming.add(in);

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        assertEquals("old1", merged.get(0).get("id"));
        assertEquals(40, merged.get(0).get("progressPercent"));
        assertEquals("Excavation", merged.get(0).get("name"), "the stored spelling wins");
    }

    @Test
    void twoIncomingNodesSharingANameDoNotBothClaimTheSameExisting() {
        // Without the claimed-set, the name fallback would hand the same existing node to
        // both, duplicating one node's progress onto two rows.
        List<Map<String, Object>> existing = kids(m("x1", "Payment", "progressPercent", 70));
        List<Map<String, Object>> incoming = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>(); a.put("name", "Payment");
        Map<String, Object> b = new LinkedHashMap<>(); b.put("name", "Payment");
        incoming.add(a); incoming.add(b);

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        assertEquals(2, merged.size());
        assertEquals("x1", merged.get(0).get("id"));
        assertEquals(70, merged.get(0).get("progressPercent"));
        assertNotEquals("x1", merged.get(1).get("id"), "the second must be a new node");
        assertTrue(merged.get(1).get("progressPercent") == null,
                "a new node starts with no progress, not a copy of someone else's");
    }

    @Test
    void aNodeDroppedFromTheTemplateTakesItsWholeSubtreeWithIt() {
        List<Map<String, Object>> existing = kids(
                m("keep", "PV Module"),
                m("drop", "Obsolete", "children", kids(m("dropkid", "Child"))));
        List<Map<String, Object>> incoming = kids(m("keep", "PV Module"));

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        assertEquals(1, merged.size());
        assertEquals("keep", merged.get(0).get("id"));
        assertTrue(svc.indexById(merged).get("dropkid") == null);
    }

    // ── JSON round trip ──────────────────────────────────────────────────────

    @Test
    void theTreeSurvivesSerialiseAndParseAtDepthWithIdsIntact() throws Exception {
        List<TemplateScopeSubItemRequest> tree = list(
                node("Electrical Works", null,
                        node("PV Module", null,
                                node("Purchase Order", null))));
        svc.normaliseWeights("Solar", tree);
        String json = svc.serialise(tree);

        List<Map<String, Object>> back = svc.parse(json);

        assertEquals(1, back.size());
        Map<String, Object> pv = ScopeSubItems.childrenOf(back.get(0)).get(0);
        Map<String, Object> po = ScopeSubItems.childrenOf(pv).get(0);
        assertEquals("Purchase Order", po.get("name"));
        assertEquals(tree.get(0).getChildren().get(0).getChildren().get(0).getId(),
                ScopeSubItems.idOf(po), "the id must survive the round trip — it is the identity");
        assertTrue(ScopeSubItems.isLeaf(po));
        assertTrue(!ScopeSubItems.isLeaf(pv));
    }

    @Test
    void aLeafSerialisesWithoutAChildrenKey() throws Exception {
        // So a leaf reads exactly as it did before nesting existed, and no consumer has to
        // tell an absent breakdown from an empty one.
        List<TemplateScopeSubItemRequest> tree = list(node("Excavation", null));
        svc.normaliseWeights("Civil", tree);

        assertTrue(!svc.serialise(tree).contains("children"));
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    @Test
    void aNodesScheduleSurvivesTheJsonRoundTrip() throws Exception {
        // The serialiser writes an EXPLICIT field list, so a field nobody added to it is
        // dropped silently on save. That is exactly how the schedule would have been lost
        // on the lead path, which persists through serialise().
        TemplateScopeSubItemRequest parent = node("Electrical Works", null,
                node("PV Module", null));
        parent.setPlannedStartDate("2026-04-01");
        parent.setPlannedEndDate("2026-06-30");
        parent.setPlanUnit("MONTH");
        TemplateScopeSubItemRequest child = parent.getChildren().get(0);
        child.setStartWeek(2);
        child.setEndWeek(3);

        List<TemplateScopeSubItemRequest> tree = list(parent);
        svc.normaliseWeights("Solar", tree);
        List<Map<String, Object>> back = svc.parse(svc.serialise(tree));

        assertEquals("2026-04-01", back.get(0).get("plannedStartDate"));
        assertEquals("2026-06-30", back.get(0).get("plannedEndDate"));
        assertEquals("MONTH", back.get(0).get("planUnit"));
        Map<String, Object> kid = ScopeSubItems.childrenOf(back.get(0)).get(0);
        assertEquals(2, kid.get("startWeek"));
        assertEquals(3, kid.get("endWeek"));
    }

    @Test
    void anUnscheduledNodeSerialisesWithNoScheduleKeysAtAll() throws Exception {
        // So a node nobody has planned keeps exactly the shape it had before
        // scheduling existed, and no consumer has to tell "" from null from absent.
        List<TemplateScopeSubItemRequest> tree = list(node("Excavation", null));
        svc.normaliseWeights("Civil", tree);
        String json = svc.serialise(tree);

        assertTrue(!json.contains("planUnit"), json);
        assertTrue(!json.contains("plannedStartDate"), json);
        assertTrue(!json.contains("startWeek"), json);
    }

    @Test
    void aReSuggestNeverOverwritesAScheduleSomeoneHasAlreadyPlanned() {
        // A template describes work, not a calendar. If the standard could reset dates,
        // re-suggesting it would silently move work that a PM has already scheduled.
        List<Map<String, Object>> existing = kids(
                m("c1", "PV Module",
                        "plannedStartDate", "2026-04-01", "plannedEndDate", "2026-06-30",
                        "planUnit", "MONTH", "startWeek", 2, "endWeek", 3));
        List<Map<String, Object>> incoming = kids(m("c1", "PV Module"));

        List<Map<String, Object>> merged = svc.mergePreservingExecutionData(existing, incoming);

        assertEquals("2026-04-01", merged.get(0).get("plannedStartDate"));
        assertEquals("2026-06-30", merged.get(0).get("plannedEndDate"));
        assertEquals("MONTH", merged.get(0).get("planUnit"));
        assertEquals(2, merged.get(0).get("startWeek"));
        assertEquals(3, merged.get(0).get("endWeek"));
    }

    private static BigDecimal sum(List<TemplateScopeSubItemRequest> l) {
        BigDecimal s = BigDecimal.ZERO;
        for (TemplateScopeSubItemRequest n : l) if (n.getWeightPct() != null) s = s.add(n.getWeightPct());
        return s;
    }
}
