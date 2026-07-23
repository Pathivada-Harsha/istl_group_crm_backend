package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Template-agnostic tender extractor: feeds the PDF text to the app's Groq LLM
 * (the same client that powers the CRM assistant) and asks it to return a strict
 * JSON object keyed to the frontend tenderData.js field names.
 *
 * <p>Unlike the regex parser ({@link TenderPdfExtractor}), which is tuned to one
 * document's exact wording, the LLM copes with arbitrary layouts and — critically
 * for Indian government tenders — normalises money written in <b>Lakh/Crore</b>
 * to plain rupees ("Rs.2017.56Lakhs" → 201756000, "Rs.26.90Crore" → 269000000).
 *
 * <p>The returned map matches the same contract as the regex extractor: scalar
 * field keys plus optional {@code boqItems} / {@code eligibilityCriteria} arrays.
 * Any failure (Groq unavailable, non-JSON reply) throws, letting the caller fall
 * back to the regex parser.
 */
@Component
public class TenderPdfAiExtractor {

    private static final Logger log = LoggerFactory.getLogger(TenderPdfAiExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The headline fields (NIT summary / IFT) sit at the front of these documents,
    // which can run to hundreds of pages. Cap the text we send (~28k chars ≈ 7k
    // tokens) to stay well inside Groq's per-request limits.
    private static final int MAX_CHARS = 28000;

    private static final Set<String> SCALAR_KEYS = Set.of(
            "tenderNumber", "tenderName", "issuingAuthority",
            "clientCompany", "clientType", "clientGstin", "clientPan", "clientCin",
            "clientContactPerson", "clientContactEmail", "clientContactPhone",
            "clientAddress", "clientCity", "clientState",
            "sector", "tenderType", "source", "portalLink",
            "location", "district", "state", "financialYear",
            "estimatedValue", "emdAmount", "performanceSecurityPct",
            "submissionDeadline", "technicalOpeningDate", "financialOpeningDate");

    @Autowired
    private GroqClient groqClient;

    /** Extract a partial field-map from already-extracted PDF text via the LLM. */
    public Map<String, Object> extractFromText(String text) {
        if (text == null || text.isBlank()) return Map.of();
        String clipped = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;

        String raw = groqClient.complete(SYSTEM_PROMPT, USER_PREFIX + clipped, 2000, 0.0);
        JsonNode root = parseJson(raw);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("LLM did not return a JSON object");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : SCALAR_KEYS) {
            putText(out, key, root.get(key));
        }

        List<Map<String, Object>> boq = readBoq(root.get("boqItems"));
        if (!boq.isEmpty()) out.put("boqItems", boq);

        List<Map<String, Object>> elig = readEligibility(root.get("eligibilityCriteria"));
        if (!elig.isEmpty()) out.put("eligibilityCriteria", elig);

        log.info("AI tender parse extracted {} field(s)", out.size());
        return out;
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /** Pull the JSON object out of the reply, tolerating ```json fences / prose. */
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

    private static List<Map<String, Object>> readBoq(JsonNode arr) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (arr == null || !arr.isArray()) return rows;
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            putText(r, "itemNo", n.get("itemNo"));
            putText(r, "scope", n.get("scope"));
            putText(r, "description", n.get("description"));
            putText(r, "unit", n.get("unit"));
            putText(r, "quantity", n.get("quantity"));
            if (r.containsKey("description")) rows.add(r);
            if (rows.size() >= 200) break;
        }
        return rows;
    }

    private static List<Map<String, Object>> readEligibility(JsonNode arr) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (arr == null || !arr.isArray()) return rows;
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            putText(r, "category", n.get("category"));
            putText(r, "criterionName", n.get("criterionName"));
            putText(r, "requiredValue", n.get("requiredValue"));
            r.put("ourValue", "");
            putText(r, "operator", n.get("operator"));
            if (r.containsKey("criterionName")) rows.add(r);
            if (rows.size() >= 50) break;
        }
        return rows;
    }

    private static void putText(Map<String, Object> m, String key, JsonNode v) {
        if (v == null || v.isNull()) return;
        String s = v.asText("").trim();
        if (!s.isEmpty() && !s.equalsIgnoreCase("null")) m.put(key, s);
    }

    // ── prompt ────────────────────────────────────────────────────────────────

    private static final String USER_PREFIX = "Tender document text:\n\n";

    private static final String SYSTEM_PROMPT =
            "You are a precise data-extraction engine for Indian government tender / NIT documents. "
          + "From the tender text, extract the fields below and return ONLY a single JSON object — "
          + "no prose, no markdown, no code fences.\n\n"
          + "Rules:\n"
          + "- Include a field ONLY if it is present in the text. Omit fields you cannot determine. "
          + "Never guess or invent values.\n"
          + "- MONEY (estimatedValue, emdAmount): output plain Indian rupees as a whole number with NO "
          + "symbols, commas, or words. Convert units: 1 Lakh = 100000, 1 Crore = 10000000. Examples: "
          + "\"Rs.2017.56Lakhs\" -> 201756000 ; \"Rs.20.18Lakhs\" -> 2018000 ; "
          + "\"Rs.26.90Crore\" -> 269000000 ; \"₹11,28,82,269\" -> 112882269.\n"
          + "- DATES (submissionDeadline, technicalOpeningDate, financialOpeningDate): output as "
          + "yyyy-MM-dd. If the document only gives a duration or says \"refer portal\", omit the field.\n"
          + "- performanceSecurityPct: numeric only, no % sign (e.g. 5 or 5.0).\n"
          + "- tenderType: one of Open, Limited, EOI, RFP, RFQ, Reverse Auction, Nomination.\n"
          + "- source: one of GeM, CPPP, State Portal (infer from the portal/website).\n"
          + "- financialYear: Indian format like \"2026-27\" (often embedded in the tender reference).\n"
          + "- issuingAuthority = the tender inviting authority / procurement entity; "
          + "clientCompany = the owning organisation.\n\n"
          + "Fields (all optional strings unless noted): tenderNumber, tenderName, issuingAuthority, "
          + "clientCompany, clientType, clientGstin, clientPan, clientCin, clientContactPerson, "
          + "clientContactEmail, clientContactPhone, clientAddress, clientCity, clientState, sector, "
          + "tenderType, source, portalLink, location, district, state, financialYear, estimatedValue, "
          + "emdAmount, performanceSecurityPct, submissionDeadline, technicalOpeningDate, "
          + "financialOpeningDate.\n\n"
          + "Also, best-effort, ONLY if clearly present:\n"
          + "- boqItems: array of { itemNo, scope, description, unit, quantity } — the main Bill of "
          + "Quantities / scope-of-work line items.\n"
          + "- eligibilityCriteria: array of { category, criterionName, requiredValue, operator }, "
          + "where category is one of Financial/Technical/Legal and operator is one of "
          + "gte, lte, contains, boolean, eq.\n\n"
          + "Return the JSON object only.";
}
