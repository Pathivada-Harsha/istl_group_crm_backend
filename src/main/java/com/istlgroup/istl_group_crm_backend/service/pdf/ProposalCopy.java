package com.istlgroup.istl_group_crm_backend.service.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The fixed boilerplate of the Solar proposal — the ~68% of the document that is
 * the same for every client (company overview, expertise, EPC capabilities, R&D,
 * global presence, safety, the numbered commercial terms, table labels).
 *
 * <p>The text is NOT authored here. It is generated out of the Word skeleton
 * ({@code proposal-templates/solar-proposal-template.docx}) so the PDF and the
 * .docx say exactly the same thing, and
 * {@code SolarProposalPdfTest#copyMatchesTheWordSkeleton} keeps them in step —
 * if sales rebuilds the skeleton and the wording moves, the build fails.
 *
 * <p>Sales owns this wording, so it lives in a .properties file a non-developer
 * can diff rather than in Java string constants.
 */
@Component
@Slf4j
public class ProposalCopy {

    private static final String RESOURCE = "proposal-copy/solar-proposal.properties";

    private final Properties copy = new Properties();

    public ProposalCopy() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        try (InputStream in = res.getInputStream();
             // Properties.load(InputStream) decodes ISO-8859-1, which mangles the
             // en dash in "Executive Manager – Procurement". Force UTF-8.
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            copy.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load " + RESOURCE, e);
        }
        log.info("[SOLAR-PDF] loaded {} copy keys from {}", copy.size(), RESOURCE);
    }

    /**
     * @throws IllegalStateException if the key is missing. Deliberate: a silently
     *         blank paragraph in a document that goes to a client is the worst
     *         failure mode available here, so fail loudly at render time instead.
     */
    public String get(String key) {
        String v = copy.getProperty(key);
        if (v == null) throw new IllegalStateException("Missing proposal copy key: " + key);
        return v;
    }

    /**
     * {@code get} with a single {@code {0}} placeholder substituted. Plain string
     * replacement rather than MessageFormat, which would also swallow the single
     * quotes that appear in ordinary prose.
     */
    public String get(String key, String arg0) {
        return get(key).replace("{0}", arg0 == null ? "" : arg0);
    }
}
