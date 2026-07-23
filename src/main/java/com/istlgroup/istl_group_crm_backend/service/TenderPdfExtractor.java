package com.istlgroup.istl_group_crm_backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Government tender documents (e.g. the Deendayal Port Authority NIT) carry a
 * structured "Tender Information Summary" table whose labels are stable enough to
 * pull the headline fields reliably. Everything is guarded per-field: a miss on
 * one field never aborts the parse — the key is simply omitted, and the frontend
 * fills only the fields that come back (and only where currently blank).
 *
 * <p>The returned map keys match the frontend field-name contract exactly:
 * scalar keys ({@code tenderNumber}, {@code estimatedValue}, …) plus two optional
 * best-effort arrays, {@code boqItems} and {@code eligibilityCriteria}.
 */
@Component
public class TenderPdfExtractor {

    /** A small set of state names, used to pick out state/district from an address. */
    private static final String STATE_ALT =
            "Andhra Pradesh|Arunachal Pradesh|Assam|Bihar|Chhattisgarh|Goa|Gujarat|Haryana|"
          + "Himachal Pradesh|Jharkhand|Karnataka|Kerala|Madhya Pradesh|Maharashtra|Manipur|"
          + "Meghalaya|Mizoram|Nagaland|Odisha|Punjab|Rajasthan|Sikkim|Tamil Nadu|Telangana|"
          + "Tripura|Uttar Pradesh|Uttarakhand|West Bengal|Delhi|Puducherry|"
          + "Jammu and Kashmir|Ladakh|Chandigarh";

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
    public Map<String, Object> extractFromText(String text) {
        if (text == null) text = "";

        // A single-space "flattened" view is easier for label→value regexes; the
        // raw line-structured text is kept for the BOQ row scan.
        String flat = text.replaceAll("\\s+", " ").trim();

        Map<String, Object> out = new LinkedHashMap<>();

        // ── identification ──
        put(out, "tenderNumber",
                group(flat, "Tender\\s*No\\.?\\s*[:\\-]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{2,40})"));
        put(out, "tenderName", cleanName(firstNonNull(
                group(flat, "Name\\s+of\\s+Work\\s*[:\\-]?\\s*['‘’\"]?(.{15,400}?)['‘’\"]?\\s*(?:Tender\\s*No|Tender\\s*Type|\\d+\\s+Tender)"),
                group(flat, "TENDER\\s+FOR\\s*['‘’\"]?(.{15,400}?)['‘’\"]?\\s*(?:Online\\s+bid|OFFICE|Tender\\s*No)"))));
        String authority = group(flat,
                "Tender\\s+Inviting\\s+Authority\\s*[:\\-]?\\s*(.{5,120}?)\\s*(?:Address|Contact|Email|\\d+\\s+Address|\\d+\\s+Contact)");
        put(out, "issuingAuthority", authority);

        // ── client (org is usually the tail of the inviting-authority line) ──
        if (authority != null && authority.contains(",")) {
            put(out, "clientCompany", authority.substring(authority.lastIndexOf(',') + 1).trim());
        }

        // ── classification ──
        String type = group(flat,
                "Tender\\s+Type\\s*[:\\-]?\\s*(.{3,60}?)\\s*(?:Tender\\s*Call|Tender\\s*Inviting|Tender\\s*Portal|\\d+\\s+Tender)");
        put(out, "tenderType", mapTenderType(type));
        String portal = firstNonNull(
                group(flat, "Tender\\s+Portal\\s*[:\\-]?\\s*(https?://\\S+)"),
                group(flat, "(https?://[^\\s]*procure[^\\s]*)"));
        portal = trimUrl(portal);
        put(out, "portalLink", portal);
        put(out, "source", mapSource(portal));

        // ── location ──
        put(out, "location", group(flat,
                "Location\\s+of\\s+(?:the\\s+)?[Ww]ork\\s*[:\\-]?\\s*(.{4,120}?)\\s*(?:Estimated|Tender\\s*Fee|\\d+\\s+Estimated|\\.)"));
        put(out, "state", group(flat, "(" + STATE_ALT + ")"));
        put(out, "district", group(flat,
                "\\d{6}\\s*,\\s*([A-Za-z][A-Za-z ]{2,30}?)\\s*,\\s*(?:" + STATE_ALT + ")"));

        // ── financials ──
        put(out, "estimatedValue", cleanNum(group(flat,
                "Estimated\\s+Cost\\s*[:\\-]?\\s*(?:₹|Rs\\.?|INR)?\\s*([\\d,]+(?:\\.\\d+)?)")));
        put(out, "emdAmount", cleanNum(firstNonNull(
                group(flat, "Earnest\\s+Money\\s+Deposit\\s*(?:\\(EMD\\))?\\s*[:\\-]?\\s*(?:₹|Rs\\.?|INR)?\\s*([\\d,]+(?:\\.\\d+)?)"),
                group(flat, "\\bEMD\\b\\s*[:\\-]?\\s*(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d+)?)"))));
        put(out, "performanceSecurityPct", group(flat,
                "(\\d{1,2}(?:\\.\\d+)?)\\s*%\\s*of\\s+(?:the\\s+)?Contract\\s+(?:Price|Value)"));

        // ── key dates (dd.mm.yyyy → yyyy-MM-dd) ──
        String submission = toIso(group(flat,
                "Bid\\s+Submission\\s+End\\s+Date[^\\d]{0,40}(\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{4})"));
        put(out, "submissionDeadline", submission);
        put(out, "technicalOpeningDate", toIso(group(flat,
                "Bid\\s+Opening\\s+Date[^\\d]{0,40}(\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{4})")));
        put(out, "financialYear", financialYear(submission));

        // ── best-effort child arrays ──
        List<Map<String, Object>> boq = extractBoq(text);
        if (!boq.isEmpty()) out.put("boqItems", boq);

        List<Map<String, Object>> elig = extractEligibility(flat);
        if (!elig.isEmpty()) out.put("eligibilityCriteria", elig);

        return out;
    }

    // ── BOQ: scan line-by-line; rows are the "<n> <desc> Lump Sum …" / "… Month"
    //    entries under a running scope header (Part-A section names, etc.). ──
    private List<Map<String, Object>> extractBoq(String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String scope = "";
        Pattern lump = Pattern.compile("^\\s*(\\d{1,3})\\s+(.+?)\\s+Lump\\s+Sum\\b", Pattern.CASE_INSENSITIVE);
        Pattern month = Pattern.compile("^\\s*(\\d{1,3})\\s+(.+?)\\s+\\d{1,2}\\s+Month\\b", Pattern.CASE_INSENSITIVE);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String header = detectScope(line);
            if (header != null) { scope = header; continue; }
            Matcher m = lump.matcher(line);
            String unit = "Lump Sum";
            if (!m.find()) { m = month.matcher(line); unit = "Month"; if (!m.find()) continue; }
            String desc = m.group(2).trim();
            if (desc.length() < 3 || desc.length() > 120) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemNo", m.group(1));
            row.put("scope", scope);
            row.put("description", desc);
            row.put("unit", unit);
            rows.add(row);
            if (rows.size() >= 200) break;
        }
        return rows;
    }

    private String detectScope(String line) {
        String l = line.toLowerCase();
        if (l.contains("administrative office building") || l.contains("a. o. building") || l.contains("a.o. building"))
            return "Administrative Office Building";
        if (l.contains("gopalpuri")) return "Gopalpuri Colony";
        if (l.contains("outside cargo jetty")) return "Outside Cargo Jetty Area";
        if (l.contains("inside cargo jetty")) return "Inside Cargo Jetty Area";
        if (l.contains("bess complete system") || l.contains("part – b") || l.contains("part - b")) return "BESS";
        if (l.contains("comprehensive annual maintenance") || l.contains("part – c") || l.contains("part - c")) return "CAMC";
        return null;
    }

    // ── Eligibility: pull the two/three high-signal criteria if clearly present. ──
    private List<Map<String, Object>> extractEligibility(String flat) {
        List<Map<String, Object>> out = new ArrayList<>();
        String turnover = cleanNum(group(flat,
                "Average\\s+Annual\\s+(?:Financial\\s+)?Turnover[^₹%]{0,90}?(?:at\\s+least|not\\s+less\\s+than|≥)?\\s*(?:₹|Rs\\.?)?\\s*([\\d,]+(?:\\.\\d+)?)"));
        if (turnover != null) out.add(criterion("Financial",
                "Average Annual Turnover (last 3 years)", turnover, "gte"));
        if (Pattern.compile("similar\\s+(?:completed\\s+)?works?", Pattern.CASE_INSENSITIVE).matcher(flat).find()) {
            out.add(criterion("Technical", "Similar Work Experience",
                    "As per NIT (3 works ≥40% / 2 ≥50% / 1 ≥80% of estimated cost)", "contains"));
        }
        if (Pattern.compile("\\bEPF\\b.{0,40}\\bESI\\b", Pattern.CASE_INSENSITIVE).matcher(flat).find()) {
            out.add(criterion("Technical", "Statutory registrations (EPF, ESI, PAN, GST, ITR)", "Required", "boolean"));
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

    // ── small helpers ──
    private static void put(Map<String, Object> m, String key, String val) {
        if (val != null && !val.trim().isEmpty()) m.put(key, val.trim());
    }

    private static String group(String src, String regex) {
        if (src == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(src);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String firstNonNull(String a, String b) {
        return (a != null && !a.trim().isEmpty()) ? a : b;
    }

    /** Strip currency symbols/commas/text, keep digits + one decimal point. */
    private static String cleanNum(String v) {
        if (v == null) return null;
        String d = v.replaceAll("[^0-9.]", "");
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return d.isEmpty() ? null : d;
    }

    private static String cleanName(String v) {
        if (v == null) return null;
        return v.replaceAll("\\s+", " ").replaceAll("['‘’\"]+$", "").trim();
    }

    private static String trimUrl(String v) {
        if (v == null) return null;
        return v.replaceAll("[.,);\\]]+$", "").trim();
    }

    private static String mapTenderType(String v) {
        if (v == null) return null;
        String l = v.toLowerCase();
        if (l.contains("open")) return "Open";
        if (l.contains("limited")) return "Limited";
        if (l.contains("eoi") || l.contains("expression")) return "EOI";
        if (l.contains("rfp")) return "RFP";
        if (l.contains("rfq")) return "RFQ";
        if (l.contains("reverse")) return "Reverse Auction";
        if (l.contains("nomination")) return "Nomination";
        return null;
    }

    private static String mapSource(String portal) {
        if (portal == null) return null;
        String l = portal.toLowerCase();
        if (l.contains("gem.gov")) return "GeM";
        if (l.contains("eprocure.gov")) return "CPPP";
        if (l.contains("procure") || l.contains(".gov")) return "State Portal";
        return null;
    }

    /** dd.mm.yyyy / dd-mm-yyyy / dd/mm/yyyy → yyyy-MM-dd. */
    private static String toIso(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("(\\d{2})[.\\-/](\\d{2})[.\\-/](\\d{4})").matcher(v);
        if (!m.find()) return null;
        return m.group(3) + "-" + m.group(2) + "-" + m.group(1);
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
