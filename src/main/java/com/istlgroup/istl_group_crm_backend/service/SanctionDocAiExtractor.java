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

    /**
     * The allow-list. Anything the model returns outside this set is dropped,
     * and a key it omits stays omitted — that absence is how "not stated in the
     * document" travels all the way to a blank cell in the registry.
     */
    private static final Set<String> KEYS = Set.of(
            // core letter
            "refNo", "sanctionDate", "lenderName", "borrowerName",
            "projectName", "category", "location",
            "projectCost", "sanctionedAmount", "debtEquityRatio",
            "interestRateText", "tenorText", "scheduledCod",
            // borrower-level, routed to /borrower/resolve by the review screen
            "promoterName", "sponsorName", "guarantorName",
            "groupName", "borrowerCategory", "borrowerSubCategory", "state",
            // means of finance
            "debtAmount", "equityAmount", "debtPct", "equityPct",
            // rate build-up
            "baseRatePct", "spreadPct", "roiPct",
            // project details and product
            "technology", "village", "district", "instrument",
            // security
            "coObligators", "pledgeOfSharesPct",
            // financial covenants
            "minDscr", "dsra", "isra", "cashSweep",
            // timeline
            "disbursementDate", "repaymentStartDate", "repaymentEndDate",
            // base case assumptions
            "plfPct", "tariffPerUnit");

    @Autowired
    private GroqClient groqClient;

    public Map<String, Object> extractFromText(String text) {
        if (text == null || text.isBlank()) return Map.of();
        String clipped = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;

        // 2500, not 1500: forty-odd string keys will not fit a 1500-token
        // completion, and a reply truncated mid-JSON parses to null — losing
        // the entire AI tier silently rather than loudly.
        String raw = groqClient.complete(SYSTEM_PROMPT, USER_PREFIX + clipped, 2500, 0.0);
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
          refNo             the reference number, e.g. "VIFL/PF/2025/1007". Some
                            letters label it "SL Ref. No" — same field.
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

        Borrower and parties:
          promoterName        the promoter behind the SPV
          sponsorName         the sponsor or parent, if named separately
          guarantorName       corporate or personal guarantor
          groupName           the promoter group the borrower belongs to
          borrowerCategory    the borrower's category ("Cat")
          borrowerSubCategory the borrower's sub-category ("Sub Cat")
          state               the state the project sits in

        Means of finance:
          debtAmount    the debt portion, as printed
          equityAmount  the equity portion, as printed
          debtPct       debt as a percentage of project cost
          equityPct     equity as a percentage of project cost

        Rate build-up:
          baseRatePct  the base / reference rate, e.g. "7.25%"
          spreadPct    the spread over the base rate, e.g. "2.50%"
          roiPct       the all-in rate of interest, e.g. "9.75%"

        Project and product:
          technology  e.g. "Solar PV", "Wind", "Hybrid"
          village     village the site sits in
          district    district the site sits in
          instrument  the facility type, e.g. "Rupee Term Loan", "NCD"

        Security:
          coObligators        co-obligators named in the letter
          pledgeOfSharesPct   percentage of borrower shares pledged

        Financial covenants:
          minDscr    minimum DSCR, e.g. "1.12x"
          dsra       the debt service reserve requirement, as printed
          isra       the interest service reserve requirement, as printed
          cashSweep  the cash sweep mechanic, as printed

        Timeline:
          disbursementDate    date of first / scheduled disbursement
          repaymentStartDate  date the first instalment falls due
          repaymentEndDate    date the last instalment falls due

        Base case assumptions:
          plfPct         plant load factor or CUF, e.g. "24.5%"
          tariffPerUnit  the PPA / levellised tariff, as printed

        Rules:
        - Copy values verbatim from the document. Do NOT convert Crore or Lakh
          to digits, do not reformat dates, do not tidy wording.
        - If a field is not stated in the document, OMIT the key entirely.
          Never guess, never infer from context, never carry a value over from
          a similar field. A missing field is correct; a wrong amount is not.
        - sanctionedAmount is the loan, projectCost is the whole project. They
          are different numbers and must not be swapped.
        - Percentages: copy the number WITH its "%" sign, e.g. "9.75%".
        - A value sitting under a heading that says "Rs. Cr's" is in crore.
          Copy the number and append " Cr", e.g. "153.75 Cr".
        - dsra, isra and cashSweep are usually sentences, not numbers, e.g.
          "equivalent to one quarter's debt service". Copy the whole phrase.
        - Do NOT compute roiPct from baseRatePct + spreadPct. If the letter does
          not print the all-in rate, omit roiPct; it is worked out downstream.
        """;

    private static final String USER_PREFIX =
            "Extract the sanction letter below into the JSON object described.\n\n";
}
