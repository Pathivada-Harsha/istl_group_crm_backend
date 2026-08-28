package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.service.tender.ExtractedField;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderFieldValidator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderLabels;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderRecords;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderSummaryLocator;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderTextCleaner;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderValues;

/**
 * Stage 3 — the deterministic extractor. Runs first on every document, costs
 * nothing, and on a template it recognises it is as good as the LLM.
 *
 * <p>It reads label/value {@link TenderRecords records} out of the block that
 * {@link TenderSummaryLocator} says holds the summary, falling back to the
 * opening pages and only then to the whole document. That ordering is what
 * keeps a phrase buried in an annexure from outranking the real value on page
 * one — and every value it produces carries the page and the line it came from,
 * so a reviewer never has to reopen a 105-page PDF to check one field.
 *
 * <p>Nothing here decides whether a value is <em>good</em>. That is
 * {@link TenderFieldValidator}'s job, and a field that fails is dropped rather
 * than shown.
 */
@Component
public class TenderPdfExtractor {

    /** Fields that are never taken from the whole document, only from the summary. */
    private static final boolean SUMMARY_ONLY = true;
    private static final boolean ANYWHERE = false;

    /**
     * Dates the tender states but the CRM does not store. They are extracted
     * anyway — the ordering check (publish → clarification → pre-bid →
     * submission → technical opening → financial opening) is only worth
     * anything with them in hand — and dropped once validation has run.
     */
    public static final List<String> SUPPORTING_DATES =
            List.of(TenderLabels.PUBLISH, TenderLabels.CLARIFICATION, TenderLabels.PRE_BID);

    /** Everything the extractor found in one document. */
    public record Extraction(Map<String, ExtractedField> fields,
                             List<Map<String, Object>> boqItems,
                             List<Map<String, Object>> eligibilityCriteria,
                             TenderText cleaned,
                             TenderSummaryLocator.Block summary) {

        /** The block the LLM should be given, when it is asked to have a go. */
        public TenderText summaryText() {
            return summary == null ? cleaned : cleaned.slice(summary.fromPage(), summary.toPage());
        }
    }

    // ── entry points ─────────────────────────────────────────────────────────

    /** Load the raw text of a PDF — shared with the AI path so it never re-parses. */
    public static String loadText(byte[] pdfBytes) throws IOException {
        return TenderText.fromPdf(pdfBytes).asText();
    }

    /** Page-aware load: the shape stages 1–3 actually work on. */
    public static TenderText load(byte[] pdfBytes) throws IOException {
        return TenderText.fromPdf(pdfBytes);
    }

    public Extraction extract(byte[] pdfBytes) throws IOException {
        return extract(TenderText.fromPdf(pdfBytes));
    }

    /**
     * Legacy flat-text entry point, kept because the value grammars are easiest
     * to assert on a hand-written page of text. Returns the plain field map the
     * old contract used.
     */
    public Map<String, Object> extractFromText(String rawText) {
        Extraction e = extract(TenderText.fromPlainText(rawText));
        Map<String, Object> out = new LinkedHashMap<>();
        e.fields().forEach((k, v) -> out.put(k, v.value()));
        if (!e.boqItems().isEmpty()) out.put("boqItems", e.boqItems());
        if (!e.eligibilityCriteria().isEmpty()) out.put("eligibilityCriteria", e.eligibilityCriteria());
        return out;
    }

    /** Stages 1 → 2 → 3 over an already-loaded document. */
    public Extraction extract(TenderText raw) {
        TenderText cleaned = TenderTextCleaner.clean(raw);
        List<TenderSummaryLocator.Block> blocks = TenderSummaryLocator.rank(cleaned);
        return new Pass(raw, cleaned, blocks).run();
    }

    // ── one document's worth of state ────────────────────────────────────────

    /**
     * A single extraction. Holds the per-block records so each field can be
     * looked for in the summary first, and the growing field map so later rules
     * (financial year, client type, sector) can lean on earlier ones.
     */
    private static final class Pass {

        private final TenderText raw;
        private final TenderText cleaned;
        private final List<TenderSummaryLocator.Block> blocks;
        private final List<List<TenderRecords.Record>> recordsByBlock = new ArrayList<>();
        private final Map<String, ExtractedField> fields = new LinkedHashMap<>();

        /** Text of the best block plus the opening pages — the "trusted" region. */
        private final String summaryFlat;
        private final TenderSummaryLocator.Block summary;
        private TenderFieldValidator.Context ctx;

        Pass(TenderText raw, TenderText cleaned, List<TenderSummaryLocator.Block> blocks) {
            this.raw = raw;
            this.cleaned = cleaned;
            this.blocks = blocks;
            this.summary = blocks.isEmpty() ? null : blocks.get(0);
            for (TenderSummaryLocator.Block b : blocks) {
                recordsByBlock.add(TenderRecords.build(cleaned, b.fromPage(), b.toPage()));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                if (isWholeDocument(blocks.get(i))) continue;
                sb.append(cleaned.slice(blocks.get(i).fromPage(), blocks.get(i).toPage()).flat())
                  .append(' ');
            }
            this.summaryFlat = sb.toString().strip();
            this.ctx = TenderFieldValidator.contextFor(null, summaryFlat);
        }

        private boolean isWholeDocument(TenderSummaryLocator.Block b) {
            return b.fromPage() == 1 && b.toPage() == cleaned.pageCount() && cleaned.pageCount() > 3;
        }

        Extraction run() {
            identification();
            parties();
            classification();
            location();
            financials();
            dates();
            derived();
            return new Extraction(fields,
                    TenderBoqScanner.boq(cleaned),
                    TenderBoqScanner.eligibility(cleaned.flat()),
                    cleaned, summary);
        }

        // ── identification ───────────────────────────────────────────────────

        private void identification() {
            put(pick("tenderNumber", candidates(TenderLabels.TENDER_NO, ANYWHERE),
                    r -> reference(r.value()), r -> true, true));
            // Everything else that reads the reference needs it in the context.
            ExtractedField ref = fields.get("tenderNumber");
            ctx = TenderFieldValidator.contextFor(ref == null ? null : ref.value(), summaryFlat);

            put(pick("tenderName", candidates(TenderLabels.TENDER_NAME, ANYWHERE),
                    r -> {
                        String t = tidyTenderName(r.value());
                        return t != null && t.length() >= 15 ? t : null;
                    },
                    r -> true, true));
        }

        // ── parties ──────────────────────────────────────────────────────────

        private void parties() {
            // Labelled first, but only from a grid cell inside the summary — the
            // same label appears in a bank-guarantee clause and in a closing GFR
            // sentence, and both templates leave the real authority unlabelled.
            ExtractedField authority = pick("issuingAuthority",
                    candidates(TenderLabels.AUTHORITY, SUMMARY_ONLY),
                    r -> TenderValues.tidy(r.value()),
                    TenderRecords.Record::atLineStart, true);
            if (authority == null) authority = authorityByPosition();
            put(authority);

            ExtractedField client = pick("clientCompany", candidates(TenderLabels.CLIENT, SUMMARY_ONLY),
                    r -> TenderValues.tidy(r.value()), r -> r.atLineStart(), true);
            if (client == null && authority != null && authority.value().contains(",")) {
                String tail = authority.value()
                        .substring(authority.value().lastIndexOf(',') + 1).strip();
                if (tail.length() >= 3) {
                    client = new ExtractedField("clientCompany", "from the inviting authority",
                            tail, authority.page(), authority.sourceText(),
                            ExtractedField.REGEX, false);
                }
            }
            put(client);

            ExtractedField address = pick("clientAddress", candidates(TenderLabels.ADDRESS, SUMMARY_ONLY),
                    r -> TenderValues.tidy(r.value()), r -> r.atLineStart(), true);
            if (address == null) address = addressBelowTheLetterhead(authority);
            put(address);

            ExtractedField email = pick("clientContactEmail", candidates(TenderLabels.EMAIL, SUMMARY_ONLY),
                    r -> TenderFieldValidator.stripEmailPrefix(firstGroup(r.value(), EMAIL_ANY)),
                    r -> true, true);
            if (email == null) {
                // Unlabelled: the source writes "email-someone@x.com" with no
                // space, so the prefix has to come off before it is an address.
                String bare = firstGroup(summaryFlat, EMAIL_ANY);
                email = field("clientContactEmail", "in the letterhead",
                        TenderFieldValidator.stripEmailPrefix(bare), pageOf(bare), bare, false);
            }
            put(email);

            put(pick("clientContactPhone", candidates(TenderLabels.PHONE, SUMMARY_ONLY),
                    r -> firstGroup(r.value(), MOBILE), r -> true, true));

            // Registration numbers: only ever from the summary, and PAN only when
            // labelled. An NIT names the procuring agency; it does not publish
            // that agency's registrations, so a hit anywhere else is a coincidence.
            put(field("clientGstin", "in the summary", firstGroup(summaryFlat, GSTIN), 0, null, true));
            put(field("clientPan", "in the summary", firstGroup(summaryFlat, LABELLED_PAN), 0, null, true));
            put(field("clientCin", "in the summary", firstGroup(summaryFlat, CIN), 0, null, true));
        }

        /**
         * The authority as a standalone heading on page one. Both reference
         * templates print it there and label it nowhere: "TRIPURA RENEWABLE
         * ENERGY DEVELOPMENT AGENCY", "SR-CONST-HQ-ELECTRICAL/SOUTHERN RLY".
         *
         * <p>Deliberately reads the <em>uncleaned</em> page one, so a short
         * document whose letterhead repeats on every page keeps it.
         */
        private ExtractedField authorityByPosition() {
            List<String> page1 = raw.page(1);
            for (int i = 0; i < page1.size() && i < POSITIONAL_SCAN_LINES; i++) {
                String line = TenderTextCleaner.squeeze(page1.get(i));
                if (line.length() < 6 || line.length() > 120) continue;
                if (!TenderLabels.hasNoLabel(line)) continue;
                if (TenderFieldValidator.check(
                        new ExtractedField("issuingAuthority", "", line, 1, line,
                                ExtractedField.REGEX, false), ctx).ok()) {
                    return new ExtractedField("issuingAuthority", "page 1 letterhead",
                            line, 1, line, ExtractedField.REGEX, true);
                }
            }
            return null;
        }

        /** The postal line printed under the letterhead, when there is one. */
        private ExtractedField addressBelowTheLetterhead(ExtractedField authority) {
            if (authority == null || authority.page() != 1) return null;
            List<String> page1 = raw.page(1);
            int start = -1;
            for (int i = 0; i < page1.size(); i++) {
                if (TenderTextCleaner.squeeze(page1.get(i)).equals(authority.value())) { start = i; break; }
            }
            if (start < 0) return null;
            for (int i = start + 1; i < Math.min(page1.size(), start + 4); i++) {
                String line = TenderTextCleaner.squeeze(page1.get(i));
                if (line.startsWith("(") || line.contains("@") || line.matches("(?i).*https?://.*")) continue;
                if (countChar(line, ',') < 2) continue;
                if (TenderFieldValidator.check(
                        new ExtractedField("clientAddress", "", line, 1, line,
                                ExtractedField.REGEX, false), ctx).ok()) {
                    return new ExtractedField("clientAddress", "under the letterhead",
                            line, 1, line, ExtractedField.REGEX, false);
                }
            }
            return null;
        }

        // ── classification ───────────────────────────────────────────────────

        private void classification() {
            ExtractedField type = pick("tenderType", candidates(TenderLabels.TENDER_TYPE, SUMMARY_ONLY),
                    r -> mapTenderType(r.value()), r -> true, true);
            if (type == null) {
                type = field("tenderType", "from the invitation wording",
                        inferTenderType(summaryFlat), 0, null, false);
            }
            put(type);

            ExtractedField portal = pick("portalLink", candidates(TenderLabels.PORTAL, SUMMARY_ONLY),
                    r -> trimUrl(firstGroup(r.value(), URL_ANY)), r -> true, true);
            if (portal == null) {
                // A portal-shaped address first; failing that, any government
                // domain in the summary. State portals are often named after the
                // department rather than after tendering (kptcl.karnataka.gov.in).
                portal = field("portalLink", "in the summary",
                        trimUrl(TenderValues.firstNonBlank(
                                firstGroup(summaryFlat, PORTAL_URL),
                                firstGroup(summaryFlat, GOV_URL))), 0, null, false);
            }
            put(portal);

            put(field("source", "from the portal",
                    mapSource(portal == null ? null : portal.value(), summaryFlat), 0, null, false));
        }

        // ── location ─────────────────────────────────────────────────────────

        private void location() {
            ExtractedField loc = pick("location", candidates(TenderLabels.LOCATION, SUMMARY_ONLY),
                    r -> TenderValues.clip(TenderValues.tidy(r.value()), 120), r -> true, true);
            if (loc == null) {
                loc = field("location", "in the summary",
                        cleanPlace(firstGroup(summaryFlat, TALUK)), 0, null, false);
            }
            put(loc);

            // Scoped to the summary on purpose: a state name mentioned once in an
            // annexure is not this tender's state, and Template B never names one.
            put(field("state", "in the summary",
                    canonicalState(firstGroup(summaryFlat, STATE)), 0, null, false));
            put(field("district", "in the summary",
                    cleanPlace(firstGroup(summaryFlat, DISTRICT)), 0, null, false));

            ExtractedField address = fields.get("clientAddress");
            put(field("clientCity", "from the address",
                    cleanPlace(firstGroup(address != null ? address.value() : summaryFlat, CITY_PIN)),
                    0, null, false));
        }

        // ── money ────────────────────────────────────────────────────────────

        private void financials() {
            put(pick("estimatedValue", candidates(TenderLabels.ESTIMATED, ANYWHERE),
                    r -> TenderValues.money(r.value()), r -> true, true));
            put(pick("emdAmount", candidates(TenderLabels.EMD, ANYWHERE),
                    r -> TenderValues.money(r.value()), r -> true, true));
            put(performanceSecurity());
        }

        /**
         * The performance-security rate, which is stated in prose rather than in
         * the summary grid. Two guards keep the wrong percentage out: the figure
         * must sit close behind a performance-security phrase, and it must not be
         * qualified by a comparative — TREDA's "(Only for tenders less than 10% of
         * the estimated cost…)" is a threshold for a different guarantee, and the
         * rate the bidder owes is the 5% further down the same clause.
         */
        private ExtractedField performanceSecurity() {
            String flat = cleaned.flat();
            Matcher m = PCT_OF.matcher(flat);
            while (m.find()) {
                String before = flat.substring(Math.max(0, m.start() - 120), m.start());
                if (!SECURITY_ANCHOR.matcher(before).find()) continue;
                String immediately = before.length() > 40
                        ? before.substring(before.length() - 40) : before;
                if (COMPARATIVE.matcher(immediately).find()) continue;
                if (TenderFieldValidator.mentions(immediately, "difference")) continue;
                String snippet = flat.substring(Math.max(0, m.start() - 60),
                        Math.min(flat.length(), m.end() + 20));
                return field("performanceSecurityPct", "performance security clause",
                        m.group(1), cleaned.pageAtFlatOffset(m.start()), snippet, false);
            }
            return null;
        }

        // ── dates ────────────────────────────────────────────────────────────

        private void dates() {
            // Resolved first and kept in the map so the ordering cross-check has
            // something to order against; the service drops them before the
            // result reaches the form.
            for (String key : SUPPORTING_DATES) {
                put(pick(key, candidates(key, ANYWHERE),
                        r -> TenderValues.date(r.value()), r -> true, false));
            }

            put(pick("submissionDeadline", candidates(TenderLabels.SUBMISSION, ANYWHERE),
                    r -> TenderValues.date(r.value()),
                    r -> !mentionsAny(r.full(), SUBMISSION_DISQUALIFIERS), true));

            ExtractedField technical = pick("technicalOpeningDate",
                    candidates(TenderLabels.TECH_OPENING, ANYWHERE),
                    r -> TenderValues.date(r.value()), r -> true, true);
            if (technical == null) {
                // An unqualified "Bid Opening Date" is the technical opening —
                // the price bid is always opened later and always says so.
                technical = pick("technicalOpeningDate", candidates(TenderLabels.BID_OPENING, ANYWHERE),
                        r -> TenderValues.date(r.value()),
                        r -> !mentionsAny(r.full(), PRICE_WORDS), true);
            }
            put(technical);

            ExtractedField financial = pick("financialOpeningDate",
                    candidates(TenderLabels.FIN_OPENING, ANYWHERE),
                    r -> TenderValues.date(r.value()), r -> true, true);
            if (financial == null) {
                financial = pick("financialOpeningDate", candidates(TenderLabels.BID_OPENING, ANYWHERE),
                        r -> TenderValues.date(r.value()),
                        r -> mentionsAny(r.full(), PRICE_WORDS), true);
            }
            put(financial);
        }

        // ── derived ──────────────────────────────────────────────────────────

        private void derived() {
            ExtractedField ref = fields.get("tenderNumber");
            ExtractedField submission = fields.get("submissionDeadline");
            // The reference is the authority on the financial year: TREDA's says
            // 2025-26 while the document itself was published in June 2026.
            String fromRef = TenderValues.fyFromReference(ref == null ? null : ref.value());
            if (fromRef != null) {
                put(new ExtractedField("financialYear", "from the tender reference", fromRef,
                        ref.page(), ref.sourceText(), ExtractedField.REGEX, true));
            } else if (submission != null) {
                put(field("financialYear", "from the submission deadline",
                        TenderValues.fyFromDate(submission.value()),
                        submission.page(), submission.sourceText(), false));
            }

            ExtractedField name = fields.get("tenderName");
            put(field("sector", "from the work description",
                    inferSector((name == null ? "" : name.value()) + " " + summaryFlat),
                    0, null, false));

            ExtractedField client = fields.get("clientCompany");
            ExtractedField authority = fields.get("issuingAuthority");
            put(field("clientType", "from the organisation's name",
                    mapClientType(client == null ? null : client.value(),
                                  authority == null ? null : authority.value()),
                    0, null, false));
        }

        // ── plumbing ─────────────────────────────────────────────────────────

        /**
         * Records for a label key, summary block first. A record can appear in
         * more than one block (the blocks overlap); the first that produces a
         * valid value wins, so duplicates are harmless.
         */
        private List<TenderRecords.Record> candidates(String key, boolean summaryOnly) {
            List<TenderRecords.Record> out = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) {
                if (summaryOnly && isWholeDocument(blocks.get(b))) continue;
                List<TenderRecords.Record> here = new ArrayList<>();
                for (TenderRecords.Record r : recordsByBlock.get(b)) {
                    if (r.labelKey().equals(key)) here.add(r);
                }
                // A purpose-built label beats a generic one wherever both appear.
                here.sort((x, y) -> Integer.compare(y.strength(), x.strength()));
                out.addAll(here);
            }
            return out;
        }

        /** First candidate whose value is non-blank and survives validation. */
        private ExtractedField pick(String field, List<TenderRecords.Record> candidates,
                                    Function<TenderRecords.Record, String> toValue,
                                    Predicate<TenderRecords.Record> accept,
                                    boolean confident) {
            for (TenderRecords.Record r : candidates) {
                // A label mentioned inside a sentence is never a label/value pair.
                // TREDA's page 2 says "Certified that this DNIe-T contains 105
                // (one hundred five) pages" — that is not the tender reference.
                if (r.inProse()) continue;
                if (!accept.test(r)) continue;
                String value = toValue.apply(r);
                if (TenderValues.isBlank(value)) continue;
                ExtractedField f = new ExtractedField(field, r.labelText(), value.strip(),
                        r.page(), r.sourceText(), ExtractedField.REGEX, confident);
                if (TenderFieldValidator.check(f, ctx).ok()) return f;
            }
            return null;
        }

        private ExtractedField field(String name, String label, String value,
                                     int page, String source, boolean confident) {
            if (TenderValues.isBlank(value)) return null;
            ExtractedField f = new ExtractedField(name, label, value.strip(),
                    page > 0 ? page : pageOf(source == null ? value : source),
                    source == null ? value : TenderValues.clip(source, 240),
                    ExtractedField.REGEX, confident);
            return TenderFieldValidator.check(f, ctx).ok() ? f : null;
        }

        private void put(ExtractedField f) {
            if (f != null && !TenderValues.isBlank(f.value())) fields.put(f.field(), f);
        }

        /** Which page a snippet came from, for provenance on the scan-based rules. */
        private int pageOf(String snippet) {
            if (snippet == null || snippet.isBlank()) return 1;
            String needle = snippet.strip();
            if (needle.length() > 40) needle = needle.substring(0, 40);
            for (TenderText.Line line : cleaned.lines()) {
                if (line.text().contains(needle)) return line.page();
            }
            return 1;
        }
    }

    // ── shared value grammars ────────────────────────────────────────────────

    /** How far into page one the unlabelled-authority scan looks. */
    private static final int POSITIONAL_SCAN_LINES = 20;

    private static final Pattern EMAIL_ANY =
            Pattern.compile("((?:e-?mail\\s*[-:]\\s*)?[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})");
    private static final Pattern MOBILE =
            Pattern.compile("((?:\\+91[\\s\\-]?)?[6-9]\\d{9})(?!\\d)");
    private static final Pattern GSTIN =
            Pattern.compile("\\b(\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z\\d]Z[A-Z\\d])\\b");
    private static final Pattern LABELLED_PAN = Pattern.compile(
            "\\bPAN\\s*(?:No\\.?|Number)?\\s*[:\\-]\\s*([A-Z]{5}\\d{4}[A-Z])\\b");
    private static final Pattern CIN =
            Pattern.compile("\\b([LUu]\\d{5}[A-Za-z]{2}\\d{4}[A-Za-z]{3}\\d{6})\\b");
    private static final Pattern URL_ANY = Pattern.compile("(https?://\\S+|www\\.[A-Za-z0-9.\\-]+)");
    private static final Pattern PORTAL_URL = Pattern.compile(
            "((?:https?://|www\\.)[A-Za-z0-9.\\-]*(?:tender|procure|ireps|gem\\.gov|bid)"
          + "[A-Za-z0-9./\\-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOV_URL = Pattern.compile(
            "((?:https?://|www\\.)[A-Za-z0-9.\\-]+\\.(?:gov\\.in|nic\\.in)[A-Za-z0-9./\\-]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TALUK =
            Pattern.compile("\\b(?:in|at)\\s+([A-Za-z][A-Za-z ]{2,30}?)\\s+Taluk\\b");
    private static final Pattern DISTRICT =
            Pattern.compile("\\b([A-Za-z][A-Za-z ]{2,30}?)\\s+District\\b");
    private static final Pattern CITY_PIN = Pattern.compile(
            "([A-Za-z][A-Za-z .]{2,30}?)\\s*[-–,]?\\s*(?:PIN|Pin)?\\s*[-–:]?\\s*\\d{3}\\s?\\d{3}\\b");

    /** A small set of state names, used to pick out state/district from an address. */
    private static final String STATE_ALT =
            "Andhra Pradesh|Arunachal Pradesh|Assam|Bihar|Chhattisgarh|Goa|Gujarat|Haryana|"
          + "Himachal Pradesh|Jharkhand|Karnataka|Kerala|Madhya Pradesh|Maharashtra|Manipur|"
          + "Meghalaya|Mizoram|Nagaland|Odisha|Punjab|Rajasthan|Sikkim|Tamil Nadu|Telangana|"
          + "Tripura|Uttar Pradesh|Uttarakhand|West Bengal|Delhi|Puducherry|"
          + "Jammu and Kashmir|Ladakh|Chandigarh";
    private static final Pattern STATE = Pattern.compile("(" + STATE_ALT + ")", Pattern.CASE_INSENSITIVE);

    // ── performance security ─────────────────────────────────────────────────

    private static final Pattern SECURITY_ANCHOR = Pattern.compile(
            "Performance\\s+Security|Performance\\s+Bank\\s+Guarantee|\\bPBG\\b|Security\\s+Deposit",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PCT_OF = Pattern.compile(
            "(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*(?:\\([^)]{0,25}\\)\\s*)?of\\s+(?:the\\s+)?(?:total\\s+)?"
          + "(?:Amount\\s+Put\\s+[Tt]o\\s+[Tt]ender|Contract\\s+(?:Price|Value|Amount)"
          + "|Tender\\s+(?:Price|Value)|Total\\s+Contract|Estimated\\s+(?:Cost|Value)"
          + "|(?:estimated\\s+)?cost\\s+put\\s+to\\s+the\\s+tender)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARATIVE = Pattern.compile(
            "(?:less\\s+than|more\\s+than|not\\s+less\\s+than|at\\s+least|up\\s?to|below|above"
          + "|exceeding|minimum|maximum|over|than)\\s+\\S{0,12}$", Pattern.CASE_INSENSITIVE);

    // ── date disambiguation ──────────────────────────────────────────────────

    /**
     * Words that mean a "closing date" is somebody else's deadline. TREDA's
     * summary carries four dated rows before the bid deadline — clarification
     * opens, clarification closes, pre-bid, document download.
     */
    private static final List<String> SUBMISSION_DISQUALIFIERS = List.of(
            "clarification", "pre-bid", "pre bid", "prebid", "corrigendum", "download",
            "uploading", "publish", "opening", "bank guarantee", "validity", "amendment");
    private static final List<String> PRICE_WORDS = List.of("price", "financial");

    private static boolean mentionsAny(String haystack, List<String> needles) {
        if (haystack == null) return false;
        String l = haystack.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (l.contains(n)) return true;
        }
        return false;
    }

    private static String firstGroup(String src, Pattern p) {
        if (src == null) return null;
        Matcher m = p.matcher(src);
        return m.find() ? m.group(1).strip() : null;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    // ── value tidying ────────────────────────────────────────────────────────

    /**
     * A tender reference stops at the comma that introduces its date. TREDA
     * prints "DNIe-T No.F.6 (542)/TREDA/NCES/2025-26/2110, dated 12/06/2026";
     * without this the trailing date was all that came back.
     */
    static String reference(String raw) {
        String s = TenderValues.tidy(raw);
        if (s == null) return null;
        int cut = s.indexOf(',');
        if (cut > 3) s = s.substring(0, cut);
        s = s.replaceAll("(?i)\\s+dated\\b.*$", "");
        s = s.replaceAll("^[^A-Za-z0-9]+", "").replaceAll("[^A-Za-z0-9)\\]]+$", "").strip();
        if (s.length() < 4 || s.length() > 90) return null;
        if (!s.matches("[A-Za-z0-9][A-Za-z0-9/_.()\\[\\]\\-\\s]*")) return null;
        return s;
    }

    /** Longest work description kept; beyond this the title stops being a title. */
    private static final int TITLE_MAX = 300;

    /**
     * Where a cover-page title stops being the work and starts being paperwork:
     * the reference number, the agency's contact block, or the signature line.
     */
    private static final Pattern TITLE_BOILERPLATE = Pattern.compile(
            "\\b(?:DNIe?[\\s\\-]?T\\s*No|NIT\\s*No|Tender\\s*(?:Ref(?:erence)?|Notice)?\\s*No"
          + "|Bid\\s*Enquiry|Specification\\s*No|Amount\\s+Put\\s+[Tt]o\\s+Tender"
          + "|Estimated\\s+(?:Cost|Value)|Earnest\\s+Money|EMD|Last\\s+[Dd]ate"
          + "|Completion\\s+Period|Validity\\s+of\\s+Tender|Tele-?fax|Telephone"
          + "|Phone\\s*No|e-?mail|SIGNATURE\\s+OF\\s+THE\\s+BIDDER"
          + "|Signature\\s+of\\s+(?:the\\s+)?(?:Bidder|Tenderer|Contractor))\\b"
          + "|\\bURL\\s*:|\\bwww\\.|https?://|Page\\s+\\d+\\s+of\\s+\\d+",
            Pattern.CASE_INSENSITIVE);

    /**
     * Turn a raw capture into a usable work title.
     *
     * <p>Cover pages run the work description straight into the tender
     * reference, the issuing agency, its address, phone, email, URL and a
     * "SIGNATURE OF THE BIDDER" footer. Cut at the first such marker, then cap
     * the length on a clause boundary so the field stays a title rather than a
     * paragraph. The untouched original is always still in the attached PDF.
     *
     * <p>Shared by the regex and AI paths, so both produce the same shape.
     */
    public static String tidyTenderName(String raw) {
        String s = cleanName(raw);
        if (s == null) return null;

        Matcher m = TITLE_BOILERPLATE.matcher(s);
        // Only cut once there is a real title in hand — some documents open with
        // the reference, and trimming from index 0 would leave nothing.
        if (m.find() && m.start() > 40) s = s.substring(0, m.start());
        s = trimTail(s);

        if (s.length() > TITLE_MAX) {
            String head = s.substring(0, TITLE_MAX);
            int cut = Math.max(head.lastIndexOf(", "),
                      Math.max(head.lastIndexOf("; "), head.lastIndexOf(". ")));
            if (cut < TITLE_MAX / 2) cut = head.lastIndexOf(' ');
            if (cut > 0) head = head.substring(0, cut);
            s = trimTail(head);
        }
        return s.isEmpty() ? null : s;
    }

    /** Drop dangling punctuation left behind by a cut. */
    private static String trimTail(String s) {
        return s.replaceAll("[\\s,;:./\\-]+$", "").strip();
    }

    private static String cleanName(String v) {
        String s = TenderValues.tidy(v);
        if (s == null) return null;
        s = s.replaceAll("(?i)\\s*Page\\s+\\d+\\s+of\\s+\\d+\\s*", " ").strip();
        return s.isEmpty() ? null : s;
    }

    /** Fold a case-insensitive state match onto its canonical spelling. */
    private static String canonicalState(String v) {
        if (v == null) return null;
        for (String s : STATE_ALT.split("\\|")) {
            if (s.equalsIgnoreCase(v.strip())) return s;
        }
        return v;
    }

    private static String cleanPlace(String v) {
        if (v == null) return null;
        String s = v.replaceAll("\\s+", " ").replaceAll("^[,\\-\\s]+|[,\\-\\s]+$", "").strip();
        int comma = s.lastIndexOf(',');
        if (comma >= 0 && comma < s.length() - 1) s = s.substring(comma + 1).strip();
        return s.length() < 3 || s.length() > 40 ? null : s;
    }

    private static String trimUrl(String v) {
        if (v == null) return null;
        return v.replaceAll("[.,);\\]]+$", "").strip();
    }

    // ── vocabulary mapping ───────────────────────────────────────────────────

    private static String mapTenderType(String v) {
        if (v == null) return null;
        String l = v.toLowerCase(Locale.ROOT);
        if (l.contains("open")) return "Open";
        if (l.contains("limited")) return "Limited";
        if (l.contains("eoi") || l.contains("expression")) return "EOI";
        if (l.contains("rfp") || l.contains("request for proposal")) return "RFP";
        if (l.contains("rfq") || l.contains("request for quotation")) return "RFQ";
        if (l.contains("reverse")) return "Reverse Auction";
        if (l.contains("nomination")) return "Nomination";
        return null;
    }

    private static String inferTenderType(String flat) {
        if (find(flat, "\\bopen\\s+(?:e-?)?tender")) return "Open";
        if (find(flat, "\\blimited\\s+(?:e-?)?tender")) return "Limited";
        if (find(flat, "\\bexpression\\s+of\\s+interest\\b")) return "EOI";
        if (find(flat, "\\brequest\\s+for\\s+proposal\\b")) return "RFP";
        if (find(flat, "\\breverse\\s+auction\\b")) return "Reverse Auction";
        return null;
    }

    /**
     * Which portal the tender came from. IREPS is Indian Railways' own
     * e-procurement system — neither GeM nor CPPP nor a state portal — and its
     * documents close with a GFR sentence that merely names GeM, which is why
     * GeM now has to be evidenced by a portal address or a bid reference rather
     * than a bare mention.
     */
    private static String mapSource(String portal, String flat) {
        String l = (portal == null ? "" : portal.toLowerCase(Locale.ROOT));
        if (l.contains("ireps")) return "IREPS / Railways";
        if (l.contains("gem.gov")) return "GeM";
        if (l.contains("eprocure.gov")) return "CPPP";
        if (!l.isEmpty() && (l.contains("procure") || l.contains(".gov") || l.contains(".nic"))) {
            return "State Portal";
        }
        if (find(flat, "ireps\\.gov\\.in")) return "IREPS / Railways";
        if (find(flat, "gem\\.gov\\.in|Government\\s+e-?Marketplace|GEM/2\\d{3}/")) return "GeM";
        if (find(flat, "eprocure\\.gov\\.in|Central\\s+Public\\s+Procurement")) return "CPPP";
        if (find(flat, "e-?[Pp]rocurement")) return "State Portal";
        return null;
    }

    /** Map the work description onto the app's sector list. */
    private static String inferSector(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("rooftop") || l.contains("roof top")) return "Rooftop Solar";
        if (l.contains("ground mount") || l.contains("ground-mount")) return "Ground Mount";
        if (l.contains("solar pump") || l.contains("kusum")) return "Solar Pump";
        if (l.contains("bess") || l.contains("battery energy storage")) return "BESS / Storage";
        if (l.contains("street light") || l.contains("streetlight")) return "Street Lighting";
        if (l.contains("solar") || l.contains("photovoltaic") || l.contains(" pv ")) return "Solar EPC";
        if (l.contains("operation and maintenance") || l.contains("o&m")
                || l.contains("annual maintenance")) return "O&M";
        if (l.contains("sub-station") || l.contains("substation") || l.contains("transmission line")
                || l.contains("kv ") || l.contains("switchgear") || l.contains("transformer")) {
            return "Electrical";
        }
        if (l.contains("civil work") || l.contains("construction of building")) return "Civil";
        return null;
    }

    /** Government body vs PSU vs private, inferred from the organisation's name. */
    private static String mapClientType(String client, String authority) {
        String s = ((client == null ? "" : client) + " " + (authority == null ? "" : authority))
                .toLowerCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.contains("corporation limited") || s.contains("nigam") || s.contains("ltd")
                || s.contains("limited") || s.contains("vidyut") || s.contains("discom")) {
            return "PSU";
        }
        if (s.contains("department") || s.contains("ministry") || s.contains("municipal")
                || s.contains("nagar") || s.contains("panchayat") || s.contains("board")
                || s.contains("authority") || s.contains("government") || s.contains("directorate")
                || s.contains("agency") || s.contains("railway") || s.contains("rly")) {
            return "Government";
        }
        return null;
    }

    private static boolean find(String src, String regex) {
        return src != null && Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(src).find();
    }
}
