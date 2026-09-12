package com.istlgroup.istl_group_crm_backend.service.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real extractor + parser against the actual Gazette notification
 * PDFs (fetched from https://www.pppinindia.gov.in/circulars_and_orders) so
 * these tests exercise the real column-splitting/page-break risk described
 * in {@link InfrastructureMasterListPdfExtractor}'s Javadoc, not a
 * hand-crafted fixture that assumes the answer.
 */
class InfrastructureMasterListParserTest {

    private final InfrastructureMasterListPdfExtractor extractor = new InfrastructureMasterListPdfExtractor();
    private final InfrastructureMasterListParser parser = new InfrastructureMasterListParser();

    @Test
    void parses2025NoticeIntoFiveCategoriesWithLargeShips() throws IOException {
        List<ParsedCategory> categories = parseFixture("hml-2025-19-09.pdf");

        assertEquals(5, categories.size(), "expected 5 top-level categories: " + names(categories));
        // The source PDF's own glyph stream embeds a stray inter-word space
        // here (confirmed independently with `pdftotext -layout`, so it's a
        // property of the government's typesetting, not of this parser —
        // see InfrastructureMasterListParser#cleanName's Javadoc for why a
        // general-purpose fix was reverted as unsafe) — assert space-
        // insensitively rather than encode that one artifact as "correct".
        assertEquals("TransportandLogistics", categories.get(0).name().replace(" ", ""));
        assertEquals("Energy", categories.get(1).name());
        assertEquals("Water and Sanitation", categories.get(2).name());
        assertEquals("Communication", categories.get(3).name());
        assertTrue(categories.get(4).name().contains("Social") && categories.get(4).name().contains("Commercial"),
                "5th category should be Social and Commercial Infrastructure, was: " + categories.get(4).name());

        List<String> transportSubs = subNames(categories.get(0));
        assertTrue(transportSubs.stream().anyMatch(s -> s.equals("Large Ships")),
                "2025 list must include 'Large Ships': " + transportSubs);
        assertTrue(transportSubs.stream().anyMatch(s -> s.startsWith("Ports")) || transportSubs.contains("Ports"),
                "footnote digit should be stripped from 'Ports': " + transportSubs);
        assertFalse(transportSubs.stream().anyMatch(s -> s.matches(".*\\d$") && !s.matches(".*[A-Za-z]\\s\\d+.*")),
                "no sub-sector name should retain a trailing footnote digit: " + transportSubs);

        List<String> socialSubs = subNames(categories.get(4));
        assertTrue(socialSubs.stream().anyMatch(s -> s.startsWith("Cold Chain")), socialSubs.toString());
        assertTrue(socialSubs.stream().anyMatch(s -> s.startsWith("Affordable Housing")), socialSubs.toString());
    }

    @Test
    void parses2022NoticeWithoutLargeShips() throws IOException {
        List<ParsedCategory> categories = parseFixture("hml-2022-11-10.pdf");

        assertFalse(categories.isEmpty(), "2022 notice should still yield categories");
        List<String> transportSubs = subNames(categories.get(0));
        assertFalse(transportSubs.contains("Large Ships"),
                "2022 list predates 'Large Ships' and must not contain it: " + transportSubs);
    }

    @Test
    void twoRealDocumentVersionsProduceDifferentParsedContent() throws IOException {
        List<ParsedCategory> v2025 = parseFixture("hml-2025-19-09.pdf");
        List<ParsedCategory> v2022 = parseFixture("hml-2022-11-10.pdf");

        assertFalse(v2025.equals(v2022), "the two real government versions must not parse identically");
    }

    private List<ParsedCategory> parseFixture(String resourceName) throws IOException {
        byte[] bytes = readResource(resourceName);
        InfrastructureMasterListPdfExtractor.ExtractionResult extraction = extractor.extract(bytes);
        return parser.parse(extraction.runs());
    }

    private byte[] readResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/infrastructure/" + name)) {
            if (in == null) throw new IOException("Missing test fixture: " + name);
            return in.readAllBytes();
        }
    }

    private static List<String> subNames(ParsedCategory category) {
        return category.subCategories().stream().map(ParsedSubCategory::name).toList();
    }

    private static List<String> names(List<ParsedCategory> categories) {
        return categories.stream().map(ParsedCategory::name).toList();
    }
}
