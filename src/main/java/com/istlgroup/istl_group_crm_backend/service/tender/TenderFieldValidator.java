package com.istlgroup.istl_group_crm_backend.service.tender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 4 — check every value the extractor produced, and <b>throw away the
 * ones that fail</b>.
 *
 * <p>That is the whole point of this class. A blank field costs a user thirty
 * seconds of typing; a confidently wrong field costs them a tender. So nothing
 * here tries to repair a bad value — a value either satisfies its rule or it
 * does not get shown.
 *
 * <p>The same rules run over the AI's output as over the regex output, so
 * escalating to the LLM cannot smuggle in a shape the parser would have
 * rejected.
 */
public final class TenderFieldValidator {

    /** Verdict for one field; {@code reason} is for the log, not the user. */
    public record Verdict(boolean ok, String reason) {
        static final Verdict PASS = new Verdict(true, null);
        static Verdict fail(String why) { return new Verdict(false, why); }
    }

    /**
     * What the rules need to know about the rest of the document.
     *
     * @param tenderReference the tender number, for the financial-year check
     * @param minYear         earliest year the document itself states
     * @param maxYear         latest year the document itself states
     */
    public record Context(String tenderReference, int minYear, int maxYear) {
        public static Context empty() { return new Context(null, 0, 0); }
    }

    // ── issuing authority ────────────────────────────────────────────────────

    /**
     * What makes a string the name of a body rather than a clause. Officer
     * designations are in here alongside the organisation nouns because an NIT
     * routinely names the inviting officer ("Chief Engineer, …, KPTCL") as the
     * authority — but no boilerplate sentence contains one either.
     */
    private static final Pattern ORG_TOKEN = Pattern.compile(
            "\\b(?:Agency|Agencies|Corporation|Corpn|Department|Dept|Board|Nigam|Ltd|Limited"
          + "|Railways?|Rlys?|Authority|Ministry|Directorate|Council|Commission|Municipality"
          + "|Municipal|Corporations?|Institute|University|Society|Undertaking|Company"
          + "|Engineer|Director|Commissioner|Secretary|Collector|Superintendent"
          + "|Panchayat|Nagar|Zilla|Division|Divisional)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Words no name begins with — a value opening on one of these is a fragment. */
    private static final Pattern MID_SENTENCE_START = Pattern.compile(
            "^(?:the|a|an|and|or|in|on|at|of|for|to|with|as|by|from|that|which|who|whose"
          + "|shall|will|is|are|was|were|has|have|had|been|being|this|these|those|such"
          + "|said|any|all|it|its|he|she|they|we|there|under|upon|after|before|during"
          + "|not|no|but|if|when|where|while|so|also|however|hereby|herein)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── address ──────────────────────────────────────────────────────────────

    /** "a) … b) … c)" — a blank form's field list, not somebody's address. */
    private static final Pattern ENUMERATION_MARKER =
            Pattern.compile("\\(?\\b[a-z]\\)|\\(?\\b[ivx]{1,4}\\)", Pattern.CASE_INSENSITIVE);

    /** Field captions that, without values between them, mean this is a form. */
    private static final Pattern FORM_CAPTION = Pattern.compile(
            "\\b(?:Name\\s+of|Mobile\\s*No|Phone\\s*No|Fax\\s*No|Email|e-mail|PAN|GST(?:IN)?"
          + "|Contact\\s*(?:No|Person)|Designation|Signature)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL_SHAPE =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    /** The prefix PDF extraction glues onto an address: {@code email-someone@x.com}. */
    private static final Pattern EMAIL_PREFIX =
            Pattern.compile("^e-?mail\\s*[-:]?\\s*", Pattern.CASE_INSENSITIVE);

    // ── cross-field bands ────────────────────────────────────────────────────

    /**
     * EMD as a share of the estimated value. Provisional: the reference
     * documents sit at 0.77% (both IREPS) and 2.50% (TREDA). Widen this as more
     * templates arrive rather than discarding values that fall outside it.
     */
    public static final BigDecimal EMD_MIN_SHARE = new BigDecimal("0.005");
    public static final BigDecimal EMD_MAX_SHARE = new BigDecimal("0.05");

    /** Dates run in this order wherever more than one of them is present. */
    private static final List<String> DATE_ORDER = List.of(
            TenderLabels.PUBLISH, TenderLabels.CLARIFICATION, TenderLabels.PRE_BID,
            "submissionDeadline", "technicalOpeningDate", "financialOpeningDate");

    private TenderFieldValidator() {}

    // ── per-field ────────────────────────────────────────────────────────────

    public static Verdict check(ExtractedField f, Context ctx) {
        if (f == null || TenderValues.isBlank(f.value())) return Verdict.fail("empty");
        String v = f.value().strip();

        switch (f.field()) {
            case "tenderNumber":          return checkTenderNumber(v);
            case "issuingAuthority":      return checkAuthority(v);
            case "clientContactEmail":    return checkEmail(v);
            case "clientAddress":         return checkAddress(v);
            case "financialYear":         return checkFinancialYear(v, ctx);
            case "estimatedValue":
            case "emdAmount":             return checkMoney(v, f.sourceText());
            case "performanceSecurityPct": return checkPercent(v);
            case "submissionDeadline":
            case "technicalOpeningDate":
            case "financialOpeningDate":  return checkDate(v, ctx);
            default:                      return Verdict.PASS;
        }
    }

    /** A reference is not a date. TREDA's ", dated 12/06/2026" used to be one. */
    static Verdict checkTenderNumber(String v) {
        if (v.length() < 4 || v.length() > 90) return Verdict.fail("implausible length");
        if (!v.matches(".*\\d.*")) return Verdict.fail("no digit");
        if (TenderValues.toIso(v) != null) return Verdict.fail("is a date");
        String stripped = TenderValues.DATE_PATTERN.matcher(v).replaceAll(" ");
        if (!stripped.matches(".*[A-Za-z0-9].*")) return Verdict.fail("only a date");
        if (stripped.matches("^[\\s\\d./\\-]*$") && !v.matches(".*[A-Za-z].*")) {
            return Verdict.fail("no non-date component");
        }
        return Verdict.PASS;
    }

    /**
     * The authority must read as the name of a body. Both reference templates
     * use "Tendering Authority" / "Tender Inviting Authority" inside unrelated
     * prose — a bank-guarantee clause in one, a closing GFR sentence in the
     * other — and the sentence that follows is what used to be captured.
     */
    static Verdict checkAuthority(String v) {
        if (v.length() < 4 || v.length() > 160) return Verdict.fail("implausible length");
        if (Character.isLowerCase(v.charAt(0))) return Verdict.fail("begins mid-sentence");
        if (MID_SENTENCE_START.matcher(v).find()) return Verdict.fail("begins mid-sentence");
        if (!ORG_TOKEN.matcher(v).find()) return Verdict.fail("no organisation token");
        // Prose runs on past a full stop; a name does not.
        if (v.matches(".*\\.\\s+[A-Za-z].*")) return Verdict.fail("spans sentences");
        if (v.split("\\s+").length > 22) return Verdict.fail("reads as a sentence");
        return Verdict.PASS;
    }

    /** {@code email-x@y.com} and {@code e-mail-x@y.com} lose the prefix first. */
    static Verdict checkEmail(String v) {
        return EMAIL_SHAPE.matcher(stripEmailPrefix(v)).matches()
                ? Verdict.PASS : Verdict.fail("not an email");
    }

    public static String stripEmailPrefix(String v) {
        return v == null ? null : EMAIL_PREFIX.matcher(v.strip()).replaceFirst("").strip();
    }

    /** A blank form's field list is not a postal address. */
    static Verdict checkAddress(String v) {
        if (v.length() < 10 || v.length() > 240) return Verdict.fail("implausible length");
        if (countMatches(ENUMERATION_MARKER, v) >= 2) return Verdict.fail("form-field enumeration");
        if (countMatches(FORM_CAPTION, v) >= 2 && !v.matches(".*\\d{3}.*")) {
            return Verdict.fail("run of field labels with no values");
        }
        if (!v.contains(",") && !v.matches(".*\\d{6}.*")) return Verdict.fail("not a postal address");
        return Verdict.PASS;
    }

    /**
     * The financial year comes from the tender reference. TREDA's reference says
     * 2025-26 while the document was published in June 2026, so a year derived
     * from the publication date disagrees with the tender's own reference.
     */
    static Verdict checkFinancialYear(String v, Context ctx) {
        if (!v.matches("^20\\d{2}-\\d{2}$")) return Verdict.fail("not an Indian FY");
        String fromRef = TenderValues.fyFromReference(ctx == null ? null : ctx.tenderReference());
        if (fromRef != null && !fromRef.equals(v)) {
            return Verdict.fail("disagrees with the tender reference (" + fromRef + ")");
        }
        return Verdict.PASS;
    }

    static Verdict checkMoney(String v, String sourceText) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(v);
        } catch (RuntimeException e) {
            return Verdict.fail("not a number");
        }
        if (amount.signum() <= 0) return Verdict.fail("not positive");
        if (TenderValues.hasStrayScaleWord(sourceText)) return Verdict.fail("stray scale word");
        return Verdict.PASS;
    }

    static Verdict checkPercent(String v) {
        try {
            BigDecimal p = new BigDecimal(v);
            if (p.signum() <= 0 || p.compareTo(new BigDecimal("100")) > 0) {
                return Verdict.fail("out of range");
            }
        } catch (RuntimeException e) {
            return Verdict.fail("not a number");
        }
        return Verdict.PASS;
    }

    /** A real calendar date, in the same era as the dates the document states. */
    static Verdict checkDate(String v, Context ctx) {
        String iso = TenderValues.toIso(v);
        if (iso == null || !iso.equals(v)) return Verdict.fail("not a calendar date");
        if (ctx == null || ctx.minYear() == 0) return Verdict.PASS;
        int year = Integer.parseInt(v.substring(0, 4));
        if (year < ctx.minYear() - 1 || year > ctx.maxYear() + 2) {
            return Verdict.fail("outside the document's own date window");
        }
        return Verdict.PASS;
    }

    // ── whole-map ────────────────────────────────────────────────────────────

    /**
     * Run every rule, then the cross-field checks, dropping whatever fails.
     * Rejections are reported through {@code onDrop} so the caller can log them.
     */
    public static Map<String, ExtractedField> validate(Map<String, ExtractedField> fields,
                                                       Context ctx,
                                                       java.util.function.BiConsumer<String, String> onDrop) {
        Map<String, ExtractedField> kept = new LinkedHashMap<>();
        for (Map.Entry<String, ExtractedField> e : fields.entrySet()) {
            Verdict verdict = check(e.getValue(), ctx);
            if (verdict.ok()) {
                kept.put(e.getKey(), e.getValue());
            } else if (onDrop != null) {
                onDrop.accept(e.getKey(), verdict.reason());
            }
        }
        crossCheck(kept, onDrop);
        return kept;
    }

    /**
     * Checks that only make sense once several fields are in hand: EMD against
     * the estimated value, and the order the key dates must fall in.
     */
    static void crossCheck(Map<String, ExtractedField> kept,
                           java.util.function.BiConsumer<String, String> onDrop) {
        checkEmdShare(kept, onDrop);
        checkDateOrder(kept, onDrop);
    }

    private static void checkEmdShare(Map<String, ExtractedField> kept,
                                      java.util.function.BiConsumer<String, String> onDrop) {
        ExtractedField emd = kept.get("emdAmount");
        ExtractedField est = kept.get("estimatedValue");
        if (emd == null || est == null) return;
        BigDecimal e;
        BigDecimal v;
        try {
            e = new BigDecimal(emd.value());
            v = new BigDecimal(est.value());
        } catch (RuntimeException ex) {
            return;
        }
        if (v.signum() <= 0) return;
        BigDecimal share = e.divide(v, 6, java.math.RoundingMode.HALF_UP);
        if (share.compareTo(EMD_MIN_SHARE) < 0 || share.compareTo(EMD_MAX_SHARE) > 0) {
            // One of the pair is wrong and there is no way to tell which, so the
            // EMD goes — it is the cheaper of the two for a user to re-enter.
            kept.remove("emdAmount");
            if (onDrop != null) {
                onDrop.accept("emdAmount", "EMD is " + share.movePointRight(2).setScale(2,
                        java.math.RoundingMode.HALF_UP) + "% of the estimated value");
            }
        }
    }

    private static void checkDateOrder(Map<String, ExtractedField> kept,
                                       java.util.function.BiConsumer<String, String> onDrop) {
        String previousKey = null;
        String previous = null;
        for (String key : DATE_ORDER) {
            ExtractedField f = kept.get(key);
            if (f == null) continue;
            if (previous != null && f.value().compareTo(previous) < 0) {
                kept.remove(key);
                if (onDrop != null) {
                    onDrop.accept(key, "falls before " + previousKey + " (" + previous + ")");
                }
                continue;
            }
            previousKey = key;
            previous = f.value();
        }
    }

    // ── context ──────────────────────────────────────────────────────────────

    /** Years the document itself states, used as the plausible-date window. */
    public static Context contextFor(String tenderReference, String text) {
        List<Integer> years = new ArrayList<>();
        Matcher m = TenderValues.DATE_PATTERN.matcher(text == null ? "" : text);
        while (m.find()) {
            String iso = TenderValues.toIso(m.group(1));
            if (iso != null) years.add(Integer.parseInt(iso.substring(0, 4)));
        }
        if (years.isEmpty()) return new Context(tenderReference, 0, 0);
        int min = years.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = years.stream().mapToInt(Integer::intValue).max().orElse(0);
        return new Context(tenderReference, min, max);
    }

    private static int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Case-folded contains, used by the extractor's context rejections. */
    public static boolean mentions(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
