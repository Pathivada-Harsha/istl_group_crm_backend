package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Best-effort extractor that turns an uploaded NIT / tender PDF into a partial
 * tender field-map, using Apache PDFBox for text extraction plus labelled
 * regexes keyed to the frontend tenderData.js field names.
 *
 * <p>Indian tender documents come from dozens of issuing bodies (CPPP, GeM, state
 * portals, PSU NIT packs) and each words its summary table differently — "Estimated
 * Cost" vs "Amount Put To Tender", "Tender No." vs "Bid Enquiry No.", money written
 * as {@code ₹11,28,82,269} vs {@code Rs.2017.56Lakhs}. Rather than target one
 * template, every field here is driven by an <em>alternation of label synonyms</em>
 * plus shared value grammars (money-with-scale, multi-format dates, identity codes),
 * so a new template usually needs only another synonym in the relevant list.
 *
 * <p>Everything is guarded per-field: a miss on one field never aborts the parse —
 * the key is simply omitted, and the frontend fills only the fields that come back
 * (and only where currently blank).
 *
 * <p>The returned map keys match the frontend field-name contract exactly:
 * scalar keys ({@code tenderNumber}, {@code estimatedValue}, …) plus two optional
 * best-effort arrays, {@code boqItems} and {@code eligibilityCriteria}.
 */
@Component
public class TenderPdfExtractor {

    // ── shared value grammars ────────────────────────────────────────────────

    /** Currency markers seen ahead of an amount. */
    private static final String CUR = "(?:₹|Rs\\.?|INR|Rupees)";

    /** An amount, Indian or western comma grouping, optional decimals. */
    private static final String NUM = "(\\d[\\d,]*(?:\\.\\d+)?)";

    /** Magnitude words that multiply the amount (Lakh/Crore are the common ones). */
    private static final String SCALE =
            "(Lakhs?|Lacs?|Crores?|Cr\\b|Millions?|Mn\\b|Billions?|Bn\\b)";

    private static final String MON =
            "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|"
          + "Aug(?:ust)?|Sept?(?:ember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?";

    /** Every date shape we've seen in tender docs: 15.01.2026, 2026-01-15, 15-Jan-2026, January 15, 2026. */
    private static final String DATE =
            "(\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{4}"
          + "|\\d{4}-\\d{1,2}-\\d{1,2}"
          + "|\\d{1,2}[\\s\\-.]{1,3}(?:" + MON + ")[\\s\\-.,]{1,3}\\d{4}"
          + "|(?:" + MON + ")[\\s\\-.]{1,3}\\d{1,2}[\\s,]{1,3}\\d{4})";

    /** A small set of state names, used to pick out state/district from an address. */
    private static final String STATE_ALT =
            "Andhra Pradesh|Arunachal Pradesh|Assam|Bihar|Chhattisgarh|Goa|Gujarat|Haryana|"
          + "Himachal Pradesh|Jharkhand|Karnataka|Kerala|Madhya Pradesh|Maharashtra|Manipur|"
          + "Meghalaya|Mizoram|Nagaland|Odisha|Punjab|Rajasthan|Sikkim|Tamil Nadu|Telangana|"
          + "Tripura|Uttar Pradesh|Uttarakhand|West Bengal|Delhi|Puducherry|"
          + "Jammu and Kashmir|Ladakh|Chandigarh";

    // ── label synonym sets (add a new template by extending these) ────────────

    private static final String L_TENDER_NO =
            "Bid\\s*Enquiry\\s*(?:No|Number)|Tender\\s*Enquiry\\s*(?:No|Number)|"
          + "Tender\\s*Reference\\s*(?:No|Number)?|NIT\\s*(?:No|Number)|"
          + "Tender\\s*ID|Bid\\s*(?:No|Number)|e-?Tender\\s*(?:No|Number)|"
          + "Work\\s*Indent\\s*(?:No|Number)|Specification\\s*(?:No|Number)|"
          + "Package\\s*(?:No|Number)|Tender\\s*(?:No|Number)|Reference\\s*(?:No|Number)";

    private static final String L_TENDER_NAME =
            "TENDER\\s+FOR\\s+THE\\s+WORK\\s+OF|TENDER\\s+FOR\\s+THE\\s+WORK|TENDER\\s+FOR|"
          + "Name\\s+of\\s+(?:the\\s+)?Work|Work\\s+Description|Description\\s+of\\s+(?:the\\s+)?Work|"
          + "Brief\\s+description\\s+of\\s+(?:the\\s+)?work|Title\\s+of\\s+(?:the\\s+)?Work|Subject";

    private static final String L_NAME_END =
            "Tender\\s*Reference|Bid\\s*Enquiry|Tender\\s*(?:No|Number)\\b|Tender\\s*Type|"
          + "Availability\\s+of\\s+Tender|Amount\\s+Put|Estimated\\s+(?:Cost|Value)|"
          + "Earnest\\s+Money|\\bEMD\\b|Bid\\s+Security|Online\\s+bid|Completion\\s+Period|"
          + "Period\\s+of\\s+completion|Last\\s+[Dd]ate|Bid\\s+Submission|Validity\\s+of\\s+Tender|"
          + "Page\\s+\\d+\\s+of\\s+\\d+";

    private static final String L_AUTHORITY =
            "Tender\\s+Inviting\\s+Authority|Bid\\s+Inviting\\s+Authority|"
          + "Name\\s+of\\s+(?:the\\s+)?(?:Employer|Owner|Department)|Tendering\\s+Authority|"
          + "Inviting\\s+Authority";

    private static final String L_CLIENT =
            "Procurement\\s+Entity|Name\\s+of\\s+(?:the\\s+)?Organisation|"
          + "Name\\s+of\\s+(?:the\\s+)?Organization|Purchasing\\s+Authority|Owner\\s*/\\s*Employer";

    private static final String L_ESTIMATED =
            "Amount\\s+Put\\s+[Tt]o\\s+Tender|Estimated\\s+(?:Contract\\s+)?(?:Cost|Value|Amount)|"
          + "Approx(?:imate)?\\.?\\s+(?:Cost|Value)|Value\\s+of\\s+(?:the\\s+)?[Ww]ork|"
          + "Tender\\s+Value|Contract\\s+Value|Total\\s+Estimated|Estimated\\s+Price";

    private static final String L_EMD =
            "EMD\\s*/\\s*Bid\\s*[Ss]ecurity|Bid\\s*[Ss]ecurity\\s*\\(\\s*EMD\\s*\\)|"
          + "Earnest\\s+Money\\s+Deposit|Earnest\\s+Money|Bid\\s*[Ss]ecurity|EMD\\s*[Aa]mount|\\bEMD\\b";

    private static final String L_SUBMIT_DATE =
            "Last\\s+[Dd]ate\\s+and\\s+[Tt]ime\\s+for\\s+submission|Last\\s+[Dd]ate\\s+for\\s+submission|"
          + "Last\\s+[Dd]ate\\s+of\\s+(?:bid\\s+)?submission|Bid\\s+Submission\\s+(?:End|Closing)\\s+Date|"
          + "Due\\s+[Dd]ate\\s+(?:of|for)\\s+submission|Closing\\s+Date|"
          + "Last\\s+[Dd]ate\\s+for\\s+[Rr]eceipt";

    private static final String L_TECH_DATE =
            "(?:Time\\s+and\\s+)?Date\\s+of\\s+[Oo]pening\\s+of\\s+Techno[\\s\\-]*Commercial|"
          + "Techno[\\s\\-]*Commercial\\s+Bid\\s+Opening|Technical\\s+Bid\\s+Opening|"
          + "Date\\s+of\\s+[Oo]pening\\s+of\\s+Technical|Bid\\s+Opening\\s+Date";

    private static final String L_FIN_DATE =
            "(?:Date\\s+of\\s+)?[Oo]pening\\s+of\\s+(?:the\\s+)?(?:Price|Financial)\\s*\\(?\\s*"
          + "(?:Financial|Price)?\\s*\\)?\\s*Bid|Price\\s+Bid\\s+Opening|Financial\\s+Bid\\s+Opening";

    // ── entry points ─────────────────────────────────────────────────────────

    /** Convenience: load the PDF text from bytes, then run the regex extractor. */
    public Map<String, Object> extract(byte[] pdfBytes) throws IOException {
        return extractFromText(loadText(pdfBytes));
    }

    /** Extract raw text from a PDF — shared by the AI parser and this regex one,
     *  so a fallback never re-parses the file. */
    public static String loadText(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text != null ? text : "";
        }
    }

    /** Best-effort regex extractor over already-extracted PDF text. */
    public Map<String, Object> extractFromText(String rawText) {
        String text = normalize(rawText);

        // A single-space "flattened" view is easier for label→value regexes; the
        // raw line-structured text is kept for the BOQ row scan.
        String flat = text.replaceAll("\\s+", " ").trim();

        Map<String, Object> out = new LinkedHashMap<>();

        // ── identification ──
        // A reference must carry a digit, so a label like "Bid Enquiry No." that is
        // itself preceded by "Tender Reference /" can't be mistaken for the value.
        put(out, "tenderNumber", firstMatch(flat,
                "(?:" + L_TENDER_NO + ")[^A-Za-z0-9]{0,12}([A-Za-z0-9][A-Za-z0-9/\\-_.]{4,60})",
                v -> v.matches(".*\\d.*") && !v.matches("(?i)(page|of|and|the)\\b.*")));

        String name = null;
        for (String candidate : allMatches(flat,
                "(?:" + L_TENDER_NAME + ")\\s*[:\\-]?\\s*['\"]?(.{15,900}?)['\"]?\\s*(?:" + L_NAME_END + ")")) {
            String c = tidyTenderName(candidate);
            if (c != null && c.length() >= 15) { name = c; break; }
        }
        put(out, "tenderName", name);

        String authority = labelled(flat, L_AUTHORITY,
                "(.{5,160}?)\\s*(?:" + L_CLIENT + "|Address|Contact|Email|Telephone|Phone|Tender\\s*(?:No|Reference))");
        put(out, "issuingAuthority", authority);

        // ── client / developer KYC ──
        String client = labelled(flat, L_CLIENT,
                "(.{5,160}?)\\s*(?:Address|Contact|Email|Telephone|Phone|CIN|Tender\\s*(?:No|Reference))");
        // Fall back to the tail of the inviting-authority line ("…, KPTCL").
        if (isBlank(client) && authority != null && authority.contains(",")) {
            client = authority.substring(authority.lastIndexOf(',') + 1).trim();
        }
        put(out, "clientCompany", client);
        put(out, "clientType", mapClientType(client, authority, flat));

        String address = labelled(flat, "Address(?:\\s+[Ff]or\\s+Communication)?",
                "(.{10,220}?)\\s*(?:Telephone|Phone|Email|e-?mail|Tender\\s*(?:No|Reference)|Amount\\s+Put)");
        put(out, "clientAddress", address);
        put(out, "clientContactEmail",
                group(flat, "([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})"));
        put(out, "clientContactPhone", firstNonNull(
                group(flat, "(?:Telephone|Phone|Mobile|Contact)\\s*(?:No\\.?s?|Number)?\\s*[:\\-]?\\s*"
                          + "((?:\\+91[\\s\\-]?)?[6-9]\\d{9})"),
                group(flat, "(?<!\\d)((?:\\+91[\\s\\-]?)?[6-9]\\d{9})(?!\\d)")));
        put(out, "clientGstin",
                group(flat, "\\b(\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z\\d]Z[A-Z\\d])\\b"));
        put(out, "clientPan",
                group(flat, "(?<![A-Z0-9])([A-Z]{5}\\d{4}[A-Z])(?![A-Z0-9])"));
        put(out, "clientCin",
                group(flat, "\\b([LUu]\\d{5}[A-Za-z]{2}\\d{4}[A-Za-z]{3}\\d{6})\\b"));

        // "Bengaluru- 560009" / "Bengaluru, 560 009" → city
        String cityFromPin = group(address != null ? address : flat,
                "([A-Za-z][A-Za-z .]{2,30}?)\\s*[-–,]?\\s*(?:PIN|Pin)?\\s*[-–:]?\\s*\\d{3}\\s?\\d{3}\\b");
        put(out, "clientCity", cleanPlace(cityFromPin));

        // ── classification ──
        String type = labelled(flat, "Tender\\s+Type|Type\\s+of\\s+Tender|Bid\\s+Type",
                "(.{3,60}?)\\s*(?:Tender\\s*Call|Tender\\s*Inviting|Tender\\s*Portal|Tender\\s*(?:No|Reference)|Estimated|Amount\\s+Put)");
        put(out, "tenderType", firstNonNull(mapTenderType(type), inferTenderType(flat)));

        String portal = firstNonNull(
                group(flat, "(?:Tender\\s+Portal|e-?Procurement\\s+(?:Portal|Platform)|Portal\\s+Link|Website)"
                          + "[^h]{0,40}(https?://\\S+)"),
                firstMatch(flat, "(https?://[^\\s,;)]+)",
                        v -> v.matches("(?i).*(procure|gem\\.gov|\\.gov\\.in|\\.nic\\.in|tender).*")));
        portal = trimUrl(portal);
        put(out, "portalLink", portal);
        put(out, "source", mapSource(portal, flat));
        put(out, "sector", inferSector(firstNonNull(name, "") + " " + flat));

        // ── location ──
        put(out, "location", firstNonNull(
                labelled(flat, "Location\\s+of\\s+(?:the\\s+)?[Ww]ork|Site\\s+Location|Place\\s+of\\s+[Ww]ork",
                        "(.{4,120}?)\\s*(?:Estimated|Tender\\s*Fee|Amount\\s+Put|Completion|\\.)"),
                group(flat, "\\b(?:in|at)\\s+([A-Za-z][A-Za-z ]{2,30}?)\\s+Taluk\\b")));
        // Matched case-insensitively, so fold "KARNATAKA" (from a letterhead) back
        // onto the canonical spelling the dropdown expects.
        put(out, "state", canonicalState(group(flat, "(" + STATE_ALT + ")")));
        put(out, "district", firstNonNull(
                group(flat, "\\b([A-Za-z][A-Za-z ]{2,30}?)\\s+District\\b"),
                group(flat, "\\d{6}\\s*,\\s*([A-Za-z][A-Za-z ]{2,30}?)\\s*,\\s*(?:" + STATE_ALT + ")")));

        // ── financials (Lakh / Crore normalised to plain rupees) ──
        put(out, "estimatedValue", moneyAfter(flat, L_ESTIMATED));
        put(out, "emdAmount", moneyAfter(flat, L_EMD));
        put(out, "performanceSecurityPct", group(flat,
                "(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*(?:\\([^)]{0,25}\\)\\s*)?of\\s+(?:the\\s+)?"
              + "(?:Amount\\s+Put\\s+[Tt]o\\s+[Tt]ender|Contract\\s+(?:Price|Value|Amount)|"
              + "Tender\\s+(?:Price|Value)|Total\\s+Contract|Estimated\\s+(?:Cost|Value))"));

        // ── key dates ──
        String submission = dateAfter(flat, L_SUBMIT_DATE);
        put(out, "submissionDeadline", submission);
        put(out, "technicalOpeningDate", dateAfter(flat, L_TECH_DATE));
        put(out, "financialOpeningDate", dateAfter(flat, L_FIN_DATE));
        // FY is usually baked into the reference ("KPTCL/2026-27/…"); fall back to
        // deriving it from the submission date.
        put(out, "financialYear", firstNonNull(
                fyFromText((String) out.get("tenderNumber")), financialYear(submission)));

        // ── best-effort child arrays ──
        List<Map<String, Object>> boq = extractBoq(text);
        if (!boq.isEmpty()) out.put("boqItems", boq);

        List<Map<String, Object>> elig = extractEligibility(flat);
        if (!elig.isEmpty()) out.put("eligibilityCriteria", elig);

        return out;
    }

    // ── BOQ: scan line-by-line for "<n> <description> <unit> [qty]" rows under a
    //    running scope header. Unit vocabulary is deliberately broad so schedules
    //    from civil, electrical and supply tenders all land. ──
    private static final Pattern BOQ_ROW = Pattern.compile(
            "^\\s*(\\d{1,3})[.)]?\\s+(.{4,300}?)\\s+"
          + "(Lump\\s*Sum|L\\.?S\\.?|Nos?\\.?|Each|Set|Sets|Lot|Job|Point|Pair|Unit|"
          + "M\\.?T\\.?|Kgs?|Km|Mtrs?|Metres?|Meters?|RMT|R\\.?Mt|Sq\\.?\\s?m(?:tr)?|Sqm|"
          + "Cu\\.?\\s?m(?:tr)?|Cum|Ltrs?|Litres?|Tonnes?|Tons?|Bags?|Months?|Days?|Years?)"
          + "\\b\\s*([\\d,]+(?:\\.\\d+)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private List<Map<String, Object>> extractBoq(String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String scope = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Table-of-contents rows ("Section-9 …… 204") are not BOQ lines.
            if (line.contains("....") || line.matches(".*\\.{4,}.*")) continue;

            // Row test first: a priced line like "4 Comprehensive annual
            // maintenance Month 60" also reads as a CAMC section header, and it
            // is the row that matters.
            Matcher m = BOQ_ROW.matcher(line);
            if (!m.find()) {
                String header = detectScope(line);
                if (header != null) scope = header;
                continue;
            }
            String desc = m.group(2).trim().replaceAll("\\s+", " ");
            // Must read like a description, not a stray numeric table row.
            if (desc.length() < 5 || !desc.matches(".*[A-Za-z]{3,}.*")) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemNo", m.group(1));
            row.put("scope", scope);
            row.put("description", clip(desc, 300));
            row.put("unit", normalizeUnit(m.group(3)));
            if (m.group(4) != null) row.put("quantity", m.group(4).replace(",", ""));
            rows.add(row);
            if (rows.size() >= 200) break;
        }
        return rows;
    }

    /** Section/part headers that group the BOQ rows beneath them. */
    private String detectScope(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.length() > 90) return null;

        // A captioned part header ("Part - A: Supply of Materials") is the most
        // specific thing a line can be, so it wins over the keyword rules below —
        // those would reduce it to just "Supply".
        Matcher part = Pattern.compile("^part\\s*[-–—]?\\s*([A-Z])\\b[:\\-\\s]*(.{0,60})?",
                Pattern.CASE_INSENSITIVE).matcher(line);
        String partTail = null;
        if (part.find()) {
            partTail = part.group(2) == null ? "" : part.group(2).trim();
            if (!partTail.isEmpty()) return partTail;
        }

        if (l.contains("administrative office building") || l.contains("a.o. building")
                || l.contains("a. o. building")) return "Administrative Office Building";
        if (l.contains("gopalpuri")) return "Gopalpuri Colony";
        if (l.contains("outside cargo jetty")) return "Outside Cargo Jetty Area";
        if (l.contains("inside cargo jetty")) return "Inside Cargo Jetty Area";
        if (l.contains("bess")) return "BESS";
        if (l.contains("comprehensive annual maintenance") || l.contains("camc")) return "CAMC";
        if (l.contains("civil work")) return "Civil Works";
        if (l.contains("electrical work")) return "Electrical Works";
        if (l.contains("erection") && l.contains("commissioning")) return "Erection & Commissioning";
        if (l.contains("supply of material") || l.contains("supply portion")) return "Supply";
        if (l.contains("transmission line")) return "Transmission Line";
        if (l.contains("sub-station") || l.contains("substation")) return "Sub-Station";

        // Bare "Part - B" with no caption: keep the part letter as the scope.
        if (partTail != null) return "Part " + part.group(1).toUpperCase(Locale.ROOT);
        return null;
    }

    private static String normalizeUnit(String u) {
        if (u == null) return null;
        String l = u.toLowerCase(Locale.ROOT).replace(".", "").replace(" ", "");
        if (l.startsWith("lump") || l.equals("ls")) return "Lump Sum";
        if (l.startsWith("no")) return "Nos";
        if (l.startsWith("month")) return "Month";
        if (l.startsWith("sq")) return "Sqm";
        if (l.startsWith("cu")) return "Cum";
        if (l.startsWith("mt") && l.length() <= 2) return "MT";
        return u.trim();
    }

    // ── Eligibility: pull the high-signal criteria when clearly present. ──
    private List<Map<String, Object>> extractEligibility(String flat) {
        List<Map<String, Object>> out = new ArrayList<>();

        String turnover = moneyAfter(flat,
                "(?:Average\\s+)?Annual\\s+(?:Financial\\s+)?[Tt]urnover|minimum\\s+financial\\s+turnover|"
              + "financial\\s+turnover\\s+of");
        if (turnover != null) out.add(criterion("Financial",
                "Annual Turnover (as per NIT)", turnover, "gte"));

        String liquid = moneyAfter(flat,
                "Liquid\\s+Assets|[Ww]orking\\s+[Cc]apital|credit\\s+facilities\\s+of|Solvency");
        if (liquid != null) out.add(criterion("Financial",
                "Liquid assets / credit facility", liquid, "gte"));

        if (find(flat, "similar\\s+(?:nature\\s+of\\s+)?(?:completed\\s+)?works?")) {
            out.add(criterion("Technical", "Similar Work Experience",
                    "As per NIT (completed works of similar nature)", "contains"));
        }
        String years = group(flat,
                "minimum\\s+(?:experience\\s+of\\s+)?(\\d{1,2})\\s*(?:\\([A-Za-z]+\\)\\s*)?years?"
              + "\\s+(?:of\\s+)?experience");
        if (years != null) out.add(criterion("Technical",
                "Minimum years of experience", years, "gte"));

        String licence = group(flat,
                "((?:Super\\s+Grade|Class[\\s\\-]*A|Class[\\s\\-]*1|Class[\\s\\-]*I)\\s+"
              + "Electrical\\s+Contractors?'?s?\\s+Licen[cs]e)");
        if (licence != null) out.add(criterion("Legal", "Electrical contractor licence",
                cleanName(licence), "contains"));

        if (find(flat, "\\bEPF\\b.{0,60}\\bESI\\b") || find(flat, "PAN\\b.{0,40}\\bGST")) {
            out.add(criterion("Legal",
                    "Statutory registrations (PAN, GST, EPF/ESI, ITR)", "Required", "boolean"));
        }
        if (find(flat, "(?:not\\s+have\\s+been\\s+)?[Bb]lack\\s?listed")) {
            out.add(criterion("Legal", "Not blacklisted / debarred", "Required", "boolean"));
        }
        if (find(flat, "\\bISO\\s*[:\\-]?\\s*\\d{4}")) {
            out.add(criterion("Technical", "ISO certification", "Required", "boolean"));
        }
        return out;
    }

    private Map<String, Object> criterion(String category, String name, String required, String operator) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("category", category);
        c.put("criterionName", name);
        c.put("requiredValue", required);
        c.put("ourValue", "");
        c.put("operator", operator);
        return c;
    }

    // ── label→value plumbing ──────────────────────────────────────────────────

    /** Match {@code (labelAlt) <sep> valueRegex} and return the first capture group. */
    private static String labelled(String flat, String labelAlt, String valueRegex) {
        return group(flat, "(?:" + labelAlt + ")\\s*[:\\-]?\\s*" + valueRegex);
    }

    /** How far past a label we keep looking for that label's figure. */
    private static final int MONEY_WINDOW = 120;

    private static final Pattern AMOUNT = Pattern.compile(
            CUR + "?\\s*\\.?\\s*" + NUM + "\\s*" + SCALE + "?", Pattern.CASE_INSENSITIVE);

    /**
     * First amount following any of the label synonyms, normalised to plain rupees.
     *
     * <p>Two wrinkles this handles. Page furniture ("… Page 3 of 205 …") often sits
     * between a label and its figure, so every number inside the window is tried
     * until one is plausibly an amount rather than just the first. And column
     * headers such as "(Rs. in lakhs)" supply the magnitude when the figure itself
     * carries no unit — so {@code Rs.2017.56Lakhs} and {@code (Rs. in lakhs) 2017.56}
     * both yield 201756000.
     */
    private static String moneyAfter(String flat, String labelAlt) {
        Matcher label = Pattern.compile("(?:" + labelAlt + ")", Pattern.CASE_INSENSITIVE).matcher(flat);
        while (label.find()) {
            String window = flat.substring(label.end(),
                    Math.min(flat.length(), label.end() + MONEY_WINDOW));
            Matcher a = AMOUNT.matcher(window);
            while (a.find()) {
                // Only the text immediately before the figure can qualify its scale.
                String context = window.substring(Math.max(0, a.start() - 45), a.start());
                String scale = a.group(2) != null ? a.group(2) : scaleFromContext(context);
                String v = money(a.group(1), scale);
                if (v != null) return v;
            }
        }
        return null;
    }

    /** First date following any of the label synonyms, as yyyy-MM-dd. */
    private static String dateAfter(String flat, String labelAlt) {
        Matcher m = Pattern.compile("(?:" + labelAlt + ").{0,60}?" + DATE,
                Pattern.CASE_INSENSITIVE).matcher(flat);
        while (m.find()) {
            String iso = toIso(m.group(1));
            if (iso != null) return iso;
        }
        return null;
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    /**
     * Fold the typographic characters PDF extraction leaves behind - non-breaking
     * and narrow spaces, soft hyphens, en/em dashes, curly quotes and fi/fl
     * ligatures. Without this a label that reads "Tender No." on screen can carry
     * a NBSP that \s never matches, and every regex below silently misses.
     */
    private static String normalize(String t) {
        if (t == null) return "";
        return t.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace(' ', ' ').replace(' ', ' ')
                .replace("­", "").replace("​", "")
                .replace("ﬁ", "fi").replace("ﬂ", "fl")
                .replaceAll("[‐-―−]", "-")
                .replaceAll("[‘’‛]", "'")
                .replaceAll("[“”]", "\"");
    }

    private static void put(Map<String, Object> m, String key, String val) {
        if (val != null && !val.trim().isEmpty()) m.put(key, val.trim());
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String group(String src, String regex) {
        if (src == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(src);
        return m.find() ? m.group(1).trim() : null;
    }

    /** All group-1 captures, in document order — lets a caller pick the best one. */
    private static List<String> allMatches(String src, String regex) {
        List<String> out = new ArrayList<>();
        if (src == null) return out;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(src);
        while (m.find() && out.size() < 20) out.add(m.group(1).trim());
        return out;
    }

    /** First group-1 capture that satisfies {@code ok} — used where a label can
     *  legitimately appear inside another label's value. */
    private static String firstMatch(String src, String regex, Predicate<String> ok) {
        for (String v : allMatches(src, regex)) {
            if (ok.test(v)) return v;
        }
        return null;
    }

    private static boolean find(String src, String regex) {
        return src != null && Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(src).find();
    }

    private static String firstNonNull(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    /** Amount + magnitude word → plain rupees, e.g. ("2017.56", "Lakhs") → 201756000. */
    private static String money(String num, String scale) {
        if (num == null) return null;
        String d = num.replace(",", "");
        BigDecimal v;
        try { v = new BigDecimal(d); } catch (Exception e) { return null; }
        BigDecimal mult = BigDecimal.ONE;
        if (scale != null) {
            String s = scale.toLowerCase(Locale.ROOT).replace(".", "");
            if (s.startsWith("lakh") || s.startsWith("lac")) mult = new BigDecimal("100000");
            else if (s.startsWith("cr")) mult = new BigDecimal("10000000");
            else if (s.startsWith("million") || s.equals("mn")) mult = new BigDecimal("1000000");
            else if (s.startsWith("billion") || s.equals("bn")) mult = new BigDecimal("1000000000");
        }
        // Without a magnitude word a tender figure is never a handful of rupees —
        // a small bare number is a serial/page number the lazy match ran into, so
        // reject it and let the caller try the next occurrence.
        if (mult.equals(BigDecimal.ONE) && v.compareTo(new BigDecimal("1000")) < 0) return null;
        v = v.multiply(mult).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return v.toPlainString();
    }

    /** "(Rs. in lakhs)" style text between a label and its figure. */
    private static String scaleFromContext(String filler) {
        if (filler == null) return null;
        String l = filler.toLowerCase(Locale.ROOT);
        if (l.contains("lakh") || l.contains("lac")) return "Lakh";
        if (l.contains("crore")) return "Crore";
        if (l.contains("million")) return "Million";
        return null;
    }

    /** Longest work description we keep; beyond this the title stops being a title. */
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
     * Turn a raw cover-page capture into a usable work title.
     *
     * <p>Cover pages run the work description straight into the tender reference,
     * the issuing agency, its address, phone, email, URL and a "SIGNATURE OF THE
     * BIDDER" footer — all of which the greedy capture swallows. Cut at the first
     * such marker, then cap the length on a clause boundary so the field stays a
     * title rather than a paragraph. The untouched original is always still in the
     * attached PDF.
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
        return s.replaceAll("[\\s,;:./\\-]+$", "").trim();
    }

    private static String cleanName(String v) {
        if (v == null) return null;
        String s = v.replaceAll("\\s+", " ")
                    .replaceAll("^[\\s:'\"\\-]+", "")
                    .replaceAll("['\"\\s]+$", "")
                    .trim();
        // Page furniture can survive the flattening; strip it from either end.
        s = s.replaceAll("(?i)\\s*Page\\s+\\d+\\s+of\\s+\\d+\\s*", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /** Fold a case-insensitive state match onto its canonical spelling. */
    private static String canonicalState(String v) {
        if (v == null) return null;
        for (String s : STATE_ALT.split("\\|")) {
            if (s.equalsIgnoreCase(v.trim())) return s;
        }
        return v;
    }

    private static String cleanPlace(String v) {
        if (v == null) return null;
        String s = v.replaceAll("\\s+", " ").replaceAll("^[,\\-\\s]+|[,\\-\\s]+$", "").trim();
        // Drop a leading address fragment: keep the last word-group before the PIN.
        int comma = s.lastIndexOf(',');
        if (comma >= 0 && comma < s.length() - 1) s = s.substring(comma + 1).trim();
        return s.length() < 3 || s.length() > 40 ? null : s;
    }

    private static String clip(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max).trim();
    }

    private static String trimUrl(String v) {
        if (v == null) return null;
        return v.replaceAll("[.,);\\]]+$", "").trim();
    }

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

    /** Unlabelled documents still say what kind of bid this is in the invitation text. */
    private static String inferTenderType(String flat) {
        if (find(flat, "\\bopen\\s+(?:e-?)?tender")) return "Open";
        if (find(flat, "\\blimited\\s+(?:e-?)?tender")) return "Limited";
        if (find(flat, "\\bexpression\\s+of\\s+interest\\b")) return "EOI";
        if (find(flat, "\\brequest\\s+for\\s+proposal\\b")) return "RFP";
        if (find(flat, "\\breverse\\s+auction\\b")) return "Reverse Auction";
        return null;
    }

    private static String mapSource(String portal, String flat) {
        String l = (portal == null ? "" : portal.toLowerCase(Locale.ROOT));
        if (l.contains("gem.gov")) return "GeM";
        if (l.contains("eprocure.gov")) return "CPPP";
        if (!l.isEmpty() && (l.contains("procure") || l.contains(".gov") || l.contains(".nic")))
            return "State Portal";
        if (find(flat, "\\bGeM\\b")) return "GeM";
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
                || l.contains("kv ") || l.contains("switchgear") || l.contains("transformer"))
            return "Electrical";
        if (l.contains("civil work") || l.contains("construction of building")) return "Civil";
        return null;
    }

    /** Government body vs PSU vs private, inferred from the organisation's name. */
    private static String mapClientType(String client, String authority, String flat) {
        String s = ((client == null ? "" : client) + " " + (authority == null ? "" : authority))
                .toLowerCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.contains("corporation limited") || s.contains("nigam") || s.contains("ltd")
                || s.contains("limited") || s.contains("vidyut") || s.contains("discom"))
            return "PSU";
        if (s.contains("department") || s.contains("ministry") || s.contains("municipal")
                || s.contains("nagar") || s.contains("panchayat") || s.contains("board")
                || s.contains("authority") || s.contains("government") || s.contains("directorate"))
            return "Government";
        if (find(flat, "\\bPvt\\.?\\s*Ltd|Private\\s+Limited")) return "Private";
        return null;
    }

    /** Any supported date spelling → yyyy-MM-dd, or null if it isn't a real date. */
    private static String toIso(String v) {
        if (v == null) return null;
        String s = v.trim();

        Matcher m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(s);
        if (m.find()) return iso(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));

        m = Pattern.compile("^(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})$").matcher(s);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            // Indian documents are dd/mm; only swap when that reading is impossible.
            int day = a, month = b;
            if (a > 12 && b <= 12) { day = a; month = b; }
            else if (b > 12 && a <= 12) { day = b; month = a; }
            return iso(Integer.parseInt(m.group(3)), month, day);
        }

        m = Pattern.compile("^(\\d{1,2})[\\s\\-.]{1,3}(" + MON + ")[\\s\\-.,]{1,3}(\\d{4})$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return iso(Integer.parseInt(m.group(3)), monthOf(m.group(2)),
                Integer.parseInt(m.group(1)));

        m = Pattern.compile("^(" + MON + ")[\\s\\-.]{1,3}(\\d{1,2})[\\s,]{1,3}(\\d{4})$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return iso(Integer.parseInt(m.group(3)), monthOf(m.group(1)),
                Integer.parseInt(m.group(2)));

        return null;
    }

    private static String iso(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1990 || year > 2100) return null;
        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private static int monthOf(String name) {
        String k = name.substring(0, 3).toLowerCase(Locale.ROOT);
        switch (k) {
            case "jan": return 1;  case "feb": return 2;  case "mar": return 3;
            case "apr": return 4;  case "may": return 5;  case "jun": return 6;
            case "jul": return 7;  case "aug": return 8;  case "sep": return 9;
            case "oct": return 10; case "nov": return 11; case "dec": return 12;
            default: return 0;
        }
    }

    /** Pull "2026-27" straight out of a tender reference when it carries the FY. */
    private static String fyFromText(String ref) {
        if (ref == null) return null;
        Matcher m = Pattern.compile("(20\\d{2})\\s*[-/]\\s*(\\d{2})\\b").matcher(ref);
        if (!m.find()) return null;
        int start = Integer.parseInt(m.group(1));
        int end = Integer.parseInt(m.group(2));
        if (end != (start + 1) % 100) return null;
        return start + "-" + m.group(2);
    }

    /** Indian FY from an ISO date: Apr–Mar → "2026-27". */
    private static String financialYear(String iso) {
        if (iso == null) return null;
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-\\d{2}").matcher(iso);
        if (!m.find()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int start = (month >= 4) ? year : year - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
