package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Weight derivation for a scope imported from a lead.
 *
 * Lead scope lines carry no weight; project scope lines require one and the set must
 * total 100 or the save is refused outright. So the interesting cases are all about
 * what happens when the lead and the template don't line up — which is the normal
 * case, since the estimator adds lines the template never had.
 *
 * Every case asserts the sum is within what the project's own normalisation will
 * absorb (0.005 x rows, {@code ProjectDetailService.normalisePhaseWeights}). A set
 * that misses that window is rejected as HTTP 400 and the whole import fails.
 */
class ProjectLeadSeedWeightsTest {

    /** The tolerance normalisePhaseWeights will absorb onto the largest line. */
    private static BigDecimal toleranceFor(int rows) {
        return new BigDecimal("0.005").multiply(BigDecimal.valueOf(rows));
    }

    private static BigDecimal sum(List<BigDecimal> weights) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal w : weights) total = total.add(w);
        return total;
    }

    private static void assertNormalisable(List<BigDecimal> weights) {
        BigDecimal delta = new BigDecimal("100").subtract(sum(weights)).abs();
        assertTrue(delta.compareTo(toleranceFor(weights.size())) <= 0,
            "sum " + sum(weights).toPlainString() + " is outside what normalisePhaseWeights absorbs");
    }

    private static Map<String, BigDecimal> template(Object... activityWeightPairs) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < activityWeightPairs.length; i += 2) {
            m.put(ProjectLeadSeedService.activityKey((String) activityWeightPairs[i]),
                  new BigDecimal(String.valueOf(activityWeightPairs[i + 1])));
        }
        return m;
    }

    @Test
    void everyLineMatchesTheTemplateSoTemplateWeightsCarryThroughUntouched() {
        Map<String, BigDecimal> tpl = template("Design", "20", "Supply", "50", "Commissioning", "30");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Design", "Supply", "Commissioning"), tpl);

        assertEquals(0, w.get(0).compareTo(new BigDecimal("20")));
        assertEquals(0, w.get(1).compareTo(new BigDecimal("50")));
        assertEquals(0, w.get(2).compareTo(new BigDecimal("30")));
        assertNormalisable(w);
    }

    @Test
    void matchingIsTrimmedAndCaseInsensitive() {
        Map<String, BigDecimal> tpl = template("Design", "40", "Supply", "60");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("  design  ", "SUPPLY"), tpl);

        assertEquals(0, w.get(0).compareTo(new BigDecimal("40")));
        assertEquals(0, w.get(1).compareTo(new BigDecimal("60")));
    }

    @Test
    void unmatchedLinesTakeTheMeanOfTheMatchedOnesThenTheSetIsScaledTo100() {
        // Matched: 20 + 50 = 70 over 2 lines, mean 35. Raw total 70 + 35 = 105,
        // scaled to 100 -> the two matched lines keep their 20:50 ratio.
        Map<String, BigDecimal> tpl = template("Design", "20", "Supply", "50");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Design", "Supply", "Owner-supplied fencing"), tpl);

        assertNormalisable(w);
        // 35/105 of 100 for the unmatched line.
        assertEquals(0, w.get(2).setScale(3, RoundingMode.HALF_UP)
                          .compareTo(new BigDecimal("33.333")));
        assertTrue(w.get(1).compareTo(w.get(0)) > 0, "Supply outweighed Design in the template");
    }

    @Test
    void nothingMatchesSoTheSetSplitsEvenly() {
        Map<String, BigDecimal> tpl = template("Design", "50", "Supply", "50");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Site clearing", "Fencing", "Handover"), tpl);

        assertNormalisable(w);
        for (BigDecimal each : w) {
            assertEquals(0, each.setScale(4, RoundingMode.HALF_UP).compareTo(new BigDecimal("33.3333")));
        }
    }

    @Test
    void noTemplateAtAllStillProducesASavableSet() {
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("A", "B", "C", "D"), new LinkedHashMap<>());

        assertNormalisable(w);
        for (BigDecimal each : w) assertEquals(0, each.compareTo(new BigDecimal("25.000000")));
    }

    /**
     * The case that motivated pre-scaling. A lead with many more lines than the
     * template used to sum far past 100 and be rejected outright.
     */
    @Test
    void aLeadWithManyMoreLinesThanTheTemplateIsStillSavable() {
        Map<String, BigDecimal> tpl = template("Design", "20", "Supply", "50", "Commissioning", "30");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Design", "Supply", "Commissioning",
                          "Extra 1", "Extra 2", "Extra 3", "Extra 4", "Extra 5"), tpl);

        assertEquals(8, w.size());
        assertNormalisable(w);
    }

    @Test
    void templateLinesWithNoUsableWeightDoNotDragTheSetToZero() {
        // A zero/blank template weight is no weight at all: the line falls through to
        // the mean rather than pinning itself at 0 and starving the rest.
        Map<String, BigDecimal> tpl = template("Design", "60");   // "Supply" absent from the map
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Design", "Supply"), tpl);

        assertNormalisable(w);
        for (BigDecimal each : w) assertTrue(each.signum() > 0, "no imported line may be weightless");
    }

    @Test
    void aSingleLineTakesTheWholeHundred() {
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Turnkey EPC"), new LinkedHashMap<>());

        assertEquals(1, w.size());
        assertEquals(0, w.get(0).compareTo(new BigDecimal("100")));
    }

    @Test
    void noLinesIsNotAnError() {
        assertTrue(ProjectLeadSeedService.computeWeights(Arrays.asList(), new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void blankActivityTextNeverMatchesAndStillGetsAWeight() {
        Map<String, BigDecimal> tpl = template("Design", "100");
        List<BigDecimal> w = ProjectLeadSeedService.computeWeights(
            Arrays.asList("Design", "   ", null), tpl);

        assertNormalisable(w);
        for (BigDecimal each : w) assertTrue(each.signum() > 0);
    }
}
