package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.istlgroup.istl_group_crm_backend.service.tender.ExtractedField;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderFieldValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderParseGate;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;

/**
 * The extraction corpus: every PDF in {@code src/test/resources/tender-samples}
 * is parsed and checked against a hand-written expected-value file next to it.
 *
 * <p><b>Adding a template needs no test code.</b> Drop {@code foo.pdf} and
 * {@code foo.properties} into that folder and this test picks them up. More
 * templates are expected, and a corpus that requires a code change per document
 * is a corpus that stops growing.
 *
 * <p>Accuracy is measured only over fields the source document actually
 * contains. A key with an <em>empty</em> value asserts the opposite and matters
 * just as much: the field must come back blank. Blank is a pass; a confidently
 * wrong value is the defect this corpus exists to catch.
 *
 * <p>Recognised keys:
 * <pre>
 *   field            = exact value
 *   field.startsWith = prefix
 *   field.contains   = substring
 *   field.page       = 1-based page the value must be sourced from
 *   field            =            (empty: the field must be absent)
 *   pages / complete / summary.from / summary.to    document-level expectations
 * </pre>
 */
class TenderPdfCorpusTest {

    private static final Path SAMPLES = Paths.get("src", "test", "resources", "tender-samples");

    private final TenderPdfExtractor extractor = new TenderPdfExtractor();

    static Stream<String> samples() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".pdf"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void matchesItsExpectedValues(String pdfName) throws IOException {
        Sample sample = parse(pdfName);
        Properties expected = expectations(pdfName);

        expectValue(expected, "pages", String.valueOf(sample.pageCount), pdfName);
        expectValue(expected, "complete", String.valueOf(sample.complete), pdfName);
        expectValue(expected, "summary.from", str(sample.summaryFrom), pdfName);
        expectValue(expected, "summary.to", str(sample.summaryTo), pdfName);

        List<String> failures = new ArrayList<>();
        for (String key : expected.stringPropertyNames()) {
            if (DOCUMENT_KEYS.contains(key)) continue;
            String want = expected.getProperty(key).strip();

            String field = key;
            String rule = "equals";
            int dot = key.lastIndexOf('.');
            if (dot > 0 && SUFFIXES.contains(key.substring(dot + 1))) {
                field = key.substring(0, dot);
                rule = key.substring(dot + 1);
            }

            ExtractedField got = sample.fields.get(field);
            switch (rule) {
                case "page" -> {
                    if (got == null) failures.add(field + ": expected a value on page " + want + ", got nothing");
                    else if (!want.equals(String.valueOf(got.page()))) {
                        failures.add(field + ": expected page " + want + ", was page " + got.page());
                    }
                }
                case "startsWith" -> {
                    if (got == null) failures.add(field + ": expected to start \"" + want + "\", got nothing");
                    else if (!got.value().startsWith(want)) {
                        failures.add(field + ": expected to start \"" + want + "\", was \"" + got.value() + "\"");
                    }
                }
                case "contains" -> {
                    if (got == null) failures.add(field + ": expected to contain \"" + want + "\", got nothing");
                    else if (!got.value().contains(want)) {
                        failures.add(field + ": expected to contain \"" + want + "\", was \"" + got.value() + "\"");
                    }
                }
                default -> {
                    if (want.isEmpty()) {
                        // The document does not state this. Anything here is a guess.
                        if (got != null) failures.add(field + ": must stay blank, was \"" + got.value() + "\"");
                    } else if (got == null) {
                        failures.add(field + ": expected \"" + want + "\", got nothing");
                    } else if (!want.equals(got.value())) {
                        failures.add(field + ": expected \"" + want + "\", was \"" + got.value() + "\"");
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(pdfName + " — " + failures.size() + " mismatch(es):\n  " + String.join("\n  ", failures));
        }
    }

    /** Every value must say where it came from, or the review modal is useless. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void everyFieldCarriesItsProvenance(String pdfName) throws IOException {
        Sample sample = parse(pdfName);
        for (ExtractedField f : sample.fields.values()) {
            assertTrue(f.page() >= 1 && f.page() <= sample.pageCount,
                    f.field() + " cites page " + f.page() + " of " + sample.pageCount);
            assertFalse(f.sourceText() == null || f.sourceText().isBlank(),
                    f.field() + " has no source text");
            assertEquals(ExtractedField.REGEX, f.origin(), f.field() + " origin");
        }
    }

    /**
     * EMD as a share of the estimated value. The reference documents sit at
     * 0.77% (both IREPS) and 2.50% (TREDA); the band is provisional and is meant
     * to be widened as templates arrive, not to start discarding real values.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void emdFallsInsideTheProvisionalBand(String pdfName) throws IOException {
        Sample sample = parse(pdfName);
        ExtractedField emd = sample.fields.get("emdAmount");
        ExtractedField estimate = sample.fields.get("estimatedValue");
        if (emd == null || estimate == null) return;

        BigDecimal share = new BigDecimal(emd.value())
                .divide(new BigDecimal(estimate.value()), 6, RoundingMode.HALF_UP);
        assertTrue(share.compareTo(TenderFieldValidator.EMD_MIN_SHARE) >= 0
                        && share.compareTo(TenderFieldValidator.EMD_MAX_SHARE) <= 0,
                pdfName + ": EMD is " + share.movePointRight(2) + "% of the estimated value");
    }

    /** Publish → pre-bid → submission → technical → financial, wherever present. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void keyDatesRunInOrder(String pdfName) throws IOException {
        Sample sample = parse(pdfName);
        String previous = null;
        String previousKey = null;
        for (String key : List.of("submissionDeadline", "technicalOpeningDate", "financialOpeningDate")) {
            ExtractedField f = sample.fields.get(key);
            if (f == null) continue;
            if (previous != null) {
                assertTrue(f.value().compareTo(previous) >= 0,
                        pdfName + ": " + key + " (" + f.value() + ") falls before "
                                + previousKey + " (" + previous + ")");
            }
            previous = f.value();
            previousKey = key;
        }
    }

    /** Every fixture must bring its expected-value file, or it silently proves nothing. */
    @Test
    void everySampleHasExpectations() throws IOException {
        List<String> orphans = samples()
                .filter(n -> !Files.exists(SAMPLES.resolve(n.replace(".pdf", ".properties"))))
                .toList();
        assertTrue(orphans.isEmpty(), "PDFs with no .properties file: " + orphans);
    }

    // ── running one sample ───────────────────────────────────────────────────

    private record Sample(Map<String, ExtractedField> fields, boolean complete,
                          int pageCount, Integer summaryFrom, Integer summaryTo) {}

    /**
     * Stages 1–5 exactly as the service runs them, minus Spring and minus the
     * LLM: the corpus measures the free deterministic path, which is the path
     * that runs on every import.
     */
    private Sample parse(String pdfName) throws IOException {
        byte[] bytes = Files.readAllBytes(SAMPLES.resolve(pdfName));
        TenderText document = TenderText.fromPdf(bytes);
        TenderPdfExtractor.Extraction extraction = extractor.extract(document);

        Map<String, ExtractedField> raw = new LinkedHashMap<>(extraction.fields());
        ExtractedField ref = raw.get("tenderNumber");
        Map<String, ExtractedField> kept = TenderFieldValidator.validate(raw,
                TenderFieldValidator.contextFor(ref == null ? null : ref.value(),
                        extraction.summaryText().flat()),
                (field, why) -> { });
        TenderPdfExtractor.SUPPORTING_DATES.forEach(kept::remove);

        return new Sample(kept, TenderParseGate.isComplete(kept.keySet()), document.pageCount(),
                extraction.summary() == null ? null : extraction.summary().fromPage(),
                extraction.summary() == null ? null : extraction.summary().toPage());
    }

    private static Properties expectations(String pdfName) {
        Properties p = new Properties();
        Path file = SAMPLES.resolve(pdfName.replace(".pdf", ".properties"));
        try (InputStream in = Files.newInputStream(file)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("no expectations for " + pdfName, e);
        }
        assertNotNull(p);
        return p;
    }

    private static void expectValue(Properties expected, String key, String actual, String pdfName) {
        String want = expected.getProperty(key);
        if (want == null) return;
        assertEquals(want.strip(), actual, pdfName + " — " + key);
    }

    private static String str(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static final List<String> DOCUMENT_KEYS =
            List.of("pages", "complete", "summary.from", "summary.to");
    private static final List<String> SUFFIXES =
            List.of("startsWith", "contains", "page");
}
