package com.istlgroup.istl_group_crm_backend.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.util.GroqClient;

/**
 * Template-agnostic fallback for sanction letters whose layout the
 * {@link SanctionDocExtractor} label dictionary doesn't recognise. Feeds the
 * document text to the same Groq client that powers the CRM assistant and asks
 * for a strict JSON object keyed to the wrapper's field names.
 *
 * <p>Deliberately conservative: the prompt forbids inference. A sanction letter
 * is a financial instrument, so a plausible-looking guess at an amount is worse
 * than a blank the user will notice and fill in.
 *
 * <p>Any failure throws, letting the caller fall back to whatever the
 * deterministic pass managed to find.
 */
@Component
public class SanctionDocAiExtractor {

    private static final Logger log = LoggerFactory.getLogger(SanctionDocAiExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sanction letters are short; this covers a long one comfortably. */
    private static final int MAX_CHARS = 24000;

    private static final Set<String> KEYS = Set.of(
            "refNo", "sanctionDate", "lenderName", "borrowerName",
            "projectName", "category", "location",
            "projectCost", "sanctionedAmount", "debtEquityRatio",
            "interestRateText", "tenorText", "scheduledCod");

    @Autowired
    private GroqClient groqClient;

    public Map<String, Object> extractFromText(String text) {
        if (text == null || text.isBlank()) return Map.of();
        String clipped = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;

        String raw = groqClient.complete(SYSTEM_PROMPT, USER_PREFIX + clipped, 1500, 0.0);
        JsonNode root = parseJson(raw);
        if (root == null || !root.isObject()) {
            String preview = raw == null ? "null"
                    : raw.substring(0, Math.min(240, raw.length())).replaceAll("\\s+", " ");
            log.warn("AI sanction parse: non-JSON reply (first 240 chars): {}", preview);
            throw new IllegalStateException("the AI did not return usable JSON for this document");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            JsonNode n = root.get(key);
            if (n == null || n.isNull()) continue;
            String v = SanctionValueParser.clean(n.asText());
            if (v != null && !"null".equalsIgnoreCase(v)) out.put(key, v);
        }
        log.info("AI sanction parse extracted {} field(s)", out.size());
        return out;
    }

    private static JsonNode parseJson(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return MAPPER.readTree(raw.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static final String SYSTEM_PROMPT = """
        You read Indian project-finance sanction letters and return structured JSON.

        Return ONE JSON object and nothing else. No prose, no markdown fences.

        Keys, all strings, all optional:
          refNo             the lender's reference number, e.g. "VIFL/PF/2025/1007"
          sanctionDate      date of the letter, as printed
          lenderName        the lending institution, from the letterhead
          borrowerName      the borrowing company, in full
          projectName       description of the project being financed
          category          e.g. "Utility-Scale Solar", "Wind", "Wind-Solar Hybrid"
          location          site location as printed
          projectCost       total project cost, EXACTLY as printed including units
          sanctionedAmount  the sanctioned term loan amount, EXACTLY as printed
          debtEquityRatio   e.g. "75:25"
          interestRateText  the full rate phrase including the floating basis
          tenorText         the full tenor phrase including any moratorium
          scheduledCod      scheduled commercial operation date, as printed

        Rules:
        - Copy values verbatim from the document. Do NOT convert Crore or Lakh
          to digits, do not reformat dates, do not tidy wording.
        - If a field is not stated in the document, OMIT the key entirely.
          Never guess, never infer from context, never carry a value over from
          a similar field. A missing field is correct; a wrong amount is not.
        - sanctionedAmount is the loan, projectCost is the whole project. They
          are different numbers and must not be swapped.
        """;

    private static final String USER_PREFIX =
            "Extract the sanction letter below into the JSON object described.\n\n";
}
