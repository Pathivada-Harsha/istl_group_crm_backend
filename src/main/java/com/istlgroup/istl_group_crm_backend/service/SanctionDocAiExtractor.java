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
            "cin", "promoterName", "sponsorName", "guarantorName",
            "groupName", "borrowerCategory", "borrowerSubCategory", "state",
            // means of finance
            "debtAmount", "equityAmount", "debtPct", "equityPct",
            // rate build-up
            "baseRatePct", "spreadPct", "roiPct",
            // project details and product
            "technology", "instrument",
            // security
            "coObligators", "pledgeOfSharesPct",
            // financial covenants
            "minDscr", "avgDscr", "dsra", "isra", "cashSweep",
            // timeline
            "disbursementDate", "repaymentStartDate", "repaymentEndDate", "interestDuringMoratorium",
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
        sanityCheckMeansOfFinance(out);
        log.info("AI sanction parse extracted {} field(s)", out.size());
        return out;
    }

    /**
     * A last defence against the model rescaling a money figure it was told
     * not to touch. Seen in practice: a letter states debt as prose — "Rupee
     * Term Loan (Bank): Rs. 178.15 Crore (70%)" — and the model, instead of
     * copying "178.15 Crore" verbatim, "converts" it and returns
     * "17815000.00 Cr" (178.15 scaled by a lakh instead of left alone),
     * which then reads as ₹1,781,500 crore once parsed — a debt many
     * thousand times the project it's financing.
     *
     * <p>Debt or equity can never sanely exceed a couple of multiples of the
     * project cost, so a figure implying otherwise is plainly mis-scaled, not
     * an unusual-but-real deal. This class's own rule applies here too: a
     * wrong amount is worse than a blank the reviewer will notice and fill
     * in, so an implausible figure is dropped rather than shown — letting
     * the downstream gap-fill (which mirrors debt from sanctionedAmount, and
     * equity from cost minus debt) work it out correctly instead.
     */
    private static void sanityCheckMeansOfFinance(Map<String, Object> out) {
        java.math.BigDecimal cost = money(out.get("projectCost"));
        if (cost == null || cost.signum() <= 0) return;
        java.math.BigDecimal cap = cost.multiply(java.math.BigDecimal.valueOf(2));
        for (String key : new String[] {"debtAmount", "equityAmount"}) {
            java.math.BigDecimal v = money(out.get(key));
            if (v == null) continue;
            if (v.signum() < 0 || v.compareTo(cap) > 0) {
                log.warn("AI sanction parse: dropping implausible {} ({}) against project cost {}",
                        key, out.get(key), out.get("projectCost"));
                out.remove(key);
            }
        }
    }

    private static java.math.BigDecimal money(Object v) {
        return v == null ? null : SanctionValueParser.parseMoneyCrore(v.toString());
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
          borrowerName      the borrowing company's registered legal name
                            ONLY — e.g. "Kaveri Kurnool Wind-Solar Hybrid
                            Power Private Limited". Do NOT append a
                            description, e.g. "a Special Purpose Vehicle
                            incorporated under the Companies Act, 2013" —
                            copy the name up to and including its legal
                            suffix (Private Limited / Ltd / LLP) and stop
                            there, even if the letter's own sentence
                            continues past it.
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
          cin                 the borrower's Corporate Identification Number, e.g.
                               "U40106RJ2021PTC074829" — only if printed on the
                               letter itself; do not guess or construct one
          promoterName        the promoter behind the SPV
          sponsorName         the sponsor or parent, if named separately
          guarantorName       corporate or personal guarantor
          groupName           the promoter group the borrower belongs to
          borrowerCategory    the borrower's category ("Cat")
          borrowerSubCategory the borrower's sub-category ("Sub Cat")
          state               the state the project sits in

        Means of finance:
          debtAmount    the debt portion, EXACTLY as printed, including
                        whatever unit word (Crore/Lakh/Cr) sits right next to
                        that number. This may be phrased as prose rather than
                        a labelled row, e.g. "Rupee Term Loan (Bank): Rs.
                        178.15 Crore (70%)" — there, debtAmount is
                        "178.15 Crore" (or "Rs. 178.15 Crore"). Copy that
                        figure precisely; do NOT rescale it — the wrong
                        answer here is "17815000.00" or "17815000.00 Cr",
                        neither of which appears anywhere in the letter.
          equityAmount  the equity / promoter's contribution portion, same
                        rule: copied exactly as printed, unit included,
                        e.g. "Promoter's Equity: Rs. 76.35 Crore" → "76.35
                        Crore". If the letter doesn't state it separately,
                        OMIT the key — do not compute it as project cost
                        minus debt yourself.
          debtPct       debt as a percentage of project cost
          equityPct     equity as a percentage of project cost

        Rate build-up:
          baseRatePct  the base / reference rate, e.g. "7.25%". Also called
                       "MCLR", "benchmark rate" or "reference rate".
          spreadPct    the spread / markup over the base rate, e.g. "2.50%"
          roiPct       the all-in rate of interest, e.g. "9.75%". Many
                       letters only print this under the heading "Rate of
                       Interest" (not a separate "ROI" line) as a single
                       percentage — that number IS roiPct. Copy it here as
                       well as into interestRateText below; the two fields
                       are not exclusive of each other.

        Project and product:
          technology  e.g. "Solar PV", "Wind", "Hybrid"
          instrument  the facility type, e.g. "Term Loan", "NCD"

        Security:
          coObligators        co-obligators named in the letter
          pledgeOfSharesPct   percentage of borrower shares pledged

        Financial covenants:
          minDscr    minimum DSCR (the floor in any individual year), e.g. "1.12x".
                     If a sentence names both an average and a minimum DSCR, this
                     is the minimum one, not the average.
          avgDscr    average DSCR (over the loan tenor), only when the letter
                     explicitly says "average" or "avg" DSCR — leave unset if it
                     only states a minimum
          dsra       the debt service reserve requirement, as printed
          isra       the interest service reserve requirement, as printed
          cashSweep  the cash sweep mechanic, as printed

        Timeline:
          disbursementDate    date of first / scheduled disbursement
          repaymentStartDate  date the first instalment falls due
          repaymentEndDate    date the last instalment falls due
          interestDuringMoratorium  "SERVICED" if the letter states interest
                            during the moratorium is payable/serviced as it
                            accrues, or "CAPITALIZED" if it states interest
                            is added to (capitalized into) the principal.
                            Return EXACTLY one of those two words, in
                            capitals. If the letter does not address this
                            at all, OMIT the key — do not guess or default.

        Base case assumptions:
          plfPct         plant load factor or CUF, e.g. "24.5%"
          tariffPerUnit  the PPA / levellised tariff, as printed

        Rules:
        - Copy values verbatim from the document. Do NOT convert Crore or Lakh
          to digits, do not reformat dates, do not tidy wording.
        - NEVER do arithmetic on a money figure: no unit conversion, no
          rescaling, no rounding, no recomputing one field from another. If a
          number in the text already carries its own unit word (Crore, Lakh,
          Cr), transcribe that number and that word exactly as printed —
          never output a "converted" rupee figure in its place.
        - If a field is not stated in the document, OMIT the key entirely.
          Never guess, never infer from context, never carry a value over from
          a similar field. A missing field is correct; a wrong amount is not.
        - sanctionedAmount is the loan, projectCost is the whole project. They
          are different numbers and must not be swapped.
        - Percentages: copy the number WITH its "%" sign, e.g. "9.75%".
        - A value sitting under a heading that says "Rs. Cr's" is normally in
          crore — copy the number and append " Cr", e.g. "153.75 Cr". BUT if
          that figure is a lakh (100,000) or more, it was actually typed in
          rupees despite the heading (no real project costs a hundred
          thousand crore); copy it as-is with NO unit appended in that case.
        - dsra, isra and cashSweep are usually sentences, not numbers, e.g.
          "equivalent to one quarter's debt service". Copy the whole phrase.
        - Do NOT compute roiPct from baseRatePct + spreadPct. If the letter does
          not print the all-in rate, omit roiPct; it is worked out downstream.
        """;

    private static final String USER_PREFIX =
            "Extract the sanction letter below into the JSON object described.\n\n";
}
