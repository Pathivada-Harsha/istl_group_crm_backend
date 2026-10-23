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
 * Headings and the two fixed sentences of the standard (non-solar) proposal.
 *
 * <p>The same shape as {@link ProposalCopy}, against its own resource file. It is a
 * separate class rather than a second resource on that one because the solar copy is
 * generated from the Word skeleton and locked to it by
 * {@code SolarProposalPdfTest#copyMatchesTheWordSkeleton} — this file is authored, has
 * no skeleton behind it, and must not be dragged into that check.
 *
 * <p>Nothing in the resource may name a company, an industry or a product: the
 * document is rendered for every organization on the platform and branded from that
 * organization's own settings.
 */
@Component
@Slf4j
public class GenericProposalCopy {

    private static final String RESOURCE = "proposal-copy/generic-proposal.properties";

    private final Properties copy = new Properties();

    public GenericProposalCopy() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        try (InputStream in = res.getInputStream();
             // Properties.load(InputStream) decodes ISO-8859-1, which mangles the
             // ampersand-free en dashes and the "&" heading. Force UTF-8.
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            copy.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load " + RESOURCE, e);
        }
        log.info("[PROPOSAL-PDF] loaded {} generic copy keys from {}", copy.size(), RESOURCE);
    }

    /**
     * @throws IllegalStateException if the key is missing. Deliberate: a silently
     *         blank heading in a document that goes to a client is the worst failure
     *         mode available here, so fail loudly at render time instead.
     */
    public String get(String key) {
        String v = copy.getProperty(key);
        if (v == null) throw new IllegalStateException("Missing generic proposal copy key: " + key);
        return v;
    }

    /** {@code get} with {@code {0}} and {@code {1}} substituted by plain replacement. */
    public String get(String key, String arg0, String arg1) {
        return get(key).replace("{0}", arg0 == null ? "" : arg0)
                       .replace("{1}", arg1 == null ? "" : arg1);
    }

    public String get(String key, String arg0) {
        return get(key, arg0, "");
    }
}
