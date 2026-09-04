package com.istlgroup.istl_group_crm_backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the 2026-09-02 fuzzy-matching fix: two SPVs from
 * different sponsors that merely happen to share a project location and the
 * standard "Wind-Solar Hybrid Power Private Limited..." boilerplate must not
 * score as a fuzzy match, while a genuine near-duplicate name still does.
 */
class CompanyNameMatcherTest {

    private static final String SUFFIX =
            " Wind-Solar Hybrid Power Private Limited, a Special Purpose Vehicle incorporated under the Companies Act, 2013";

    // A small stand-in for a live borrower portfolio: several sponsors, each
    // with more than one SPV, some sharing a project location with a
    // DIFFERENT sponsor — exactly the shape that produced the false positive.
    private static final List<String> CORPUS = List.of(
            "Kaveri Kurnool" + SUFFIX,
            "Kaveri Anantapur" + SUFFIX,
            "Kaveri Koppal" + SUFFIX,
            "Vayugrid Anantapur" + SUFFIX,
            "Vayugrid Jodhpur" + SUFFIX,
            "Marutha Dhule" + SUFFIX,
            "Suryakiran Chitradurga" + SUFFIX
    );

    @Test
    void differentSponsorsSameLocationDoNotFuzzyMatch() {
        Map<String, Double> idf = CompanyNameMatcher.buildBigramIdf(CORPUS);
        double score = CompanyNameMatcher.similarity(
                "Vayugrid Anantapur" + SUFFIX, "Kaveri Anantapur" + SUFFIX, idf);
        System.out.println("Vayugrid Anantapur vs Kaveri Anantapur (weighted): " + score);
        assertTrue(score < 0.55, "Different sponsors sharing only location/boilerplate must stay below the fuzzy floor, was " + score);
    }

    @Test
    void sameSponsorDifferentProjectStillSurfacesAsFuzzy() {
        // Deliberately still ALLOWED to clear the floor: two different SPVs
        // under the same sponsor (same leading token, "kaveri") are exactly
        // the case CompanyMatchModal's "Add as a new company under Parent
        // Group: Kaveri" suggestion is built for — the fuzzy candidate is
        // never auto-attached, and picking that group option creates a
        // genuinely new borrower, never merging into the fuzzy match. Only
        // an UNRELATED sponsor's company (different leading token, see
        // above) is what this fix suppresses.
        Map<String, Double> idf = CompanyNameMatcher.buildBigramIdf(CORPUS);
        double score = CompanyNameMatcher.similarity(
                "Kaveri Kurnool" + SUFFIX, "Kaveri Anantapur" + SUFFIX, idf);
        System.out.println("Kaveri Kurnool vs Kaveri Anantapur (weighted): " + score);
        assertTrue(score >= 0.55, "Same-sponsor SPVs should still surface as a fuzzy suggestion, was " + score);
    }

    @Test
    void nearDuplicateNameStillMatches() {
        Map<String, Double> idf = CompanyNameMatcher.buildBigramIdf(CORPUS);
        // A typo'd re-transcription of an existing company's own name — the
        // whole point the fuzzy tier exists for.
        double score = CompanyNameMatcher.similarity(
                "Kaveri Kurnool Wind-Solar Hybrid Power Pvt. Ltd.",
                "Kaveri Kurnool" + SUFFIX, idf);
        System.out.println("Near-duplicate Kaveri Kurnool spelling (weighted): " + score);
        assertTrue(score >= 0.55, "A genuine near-duplicate of the same company's own name must still clear the fuzzy floor, was " + score);
    }
}
