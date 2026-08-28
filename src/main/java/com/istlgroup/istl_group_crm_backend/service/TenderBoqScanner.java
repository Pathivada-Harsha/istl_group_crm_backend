package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.istlgroup.istl_group_crm_backend.service.tender.TenderText;
import com.istlgroup.istl_group_crm_backend.service.tender.TenderValues;

/**
 * Best-effort child arrays: the bill of quantities and the eligibility
 * criteria.
 *
 * <p>Both are genuinely best-effort — a schedule of rates has no standard
 * layout — so neither is ever imported without the reviewer ticking it. They
 * live apart from {@link TenderPdfExtractor} because they scan lines rather
 * than label/value records, and because a miss here says nothing about whether
 * the headline fields parsed.
 */
final class TenderBoqScanner {

    /**
     * "&lt;n&gt; &lt;description&gt; &lt;unit&gt; [qty]" under a running scope
     * header. The unit vocabulary is deliberately broad so schedules from civil,
     * electrical and supply tenders all land.
     */
    private static final Pattern BOQ_ROW = Pattern.compile(
            "^\\s*(\\d{1,3})[.)]?\\s+(.{4,300}?)\\s+"
          + "(Lump\\s*Sum|L\\.?S\\.?|Nos?\\.?|Each|Set|Sets|Lot|Job|Point|Pair|Unit|"
          + "M\\.?T\\.?|Kgs?|Km|Mtrs?|Metres?|Meters?|RMT|R\\.?Mt|Sq\\.?\\s?m(?:tr)?|Sqm|"
          + "Cu\\.?\\s?m(?:tr)?|Cum|Ltrs?|Litres?|Tonnes?|Tons?|Bags?|Months?|Days?|Years?)"
          + "\\b\\s*([\\d,]+(?:\\.\\d+)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Verbs that only turn up in contract prose, never in a schedule line. */
    private static final Pattern PROSE = Pattern.compile(
            "\\b(?:shall|should|must|will\\s+be|hereby|whereas|thereof|is\\s+to\\s+be)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_ROWS = 200;

    private TenderBoqScanner() {}

    static List<Map<String, Object>> boq(TenderText doc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String scope = "";
        for (TenderText.Line line : doc.lines()) {
            String text = line.text();
            // Table-of-contents rows ("Section-9 …… 204") are not BOQ lines.
            if (text.matches(".*\\.{4,}.*")) continue;

            // Row test first: a priced line like "4 Comprehensive annual
            // maintenance Month 60" also reads as a CAMC section header, and it
            // is the row that matters.
            Matcher m = BOQ_ROW.matcher(text);
            if (!m.find()) {
                String header = detectScope(text);
                if (header != null) scope = header;
                continue;
            }
            String desc = m.group(2).strip().replaceAll("\\s+", " ");
            if (desc.length() < 5 || !desc.matches(".*[A-Za-z]{3,}.*")) continue;
            // Must read like a schedule line, not a numbered clause the row
            // pattern happened to fit: no leading punctuation, and no verb that
            // only appears in prose ("This offer shall remain valid … 180 days").
            if (!Character.isLetterOrDigit(desc.charAt(0))) continue;
            if (PROSE.matcher(desc).find()) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemNo", m.group(1));
            row.put("scope", scope);
            row.put("description", TenderValues.clip(desc, 300));
            row.put("unit", normalizeUnit(m.group(3)));
            if (m.group(4) != null) row.put("quantity", m.group(4).replace(",", ""));
            row.put("page", line.page());
            rows.add(row);
            if (rows.size() >= MAX_ROWS) break;
        }
        return rows;
    }

    /** Section/part headers that group the BOQ rows beneath them. */
    private static String detectScope(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.length() > 90) return null;

        // A captioned part header ("Part - A: Supply of Materials") is the most
        // specific thing a line can be, so it wins over the keyword rules below —
        // those would reduce it to just "Supply".
        Matcher part = Pattern.compile("^part\\s*[-–—]?\\s*([A-Z])\\b[:\\-\\s]*(.{0,60})?",
                Pattern.CASE_INSENSITIVE).matcher(line);
        String partTail = null;
        if (part.find()) {
            partTail = part.group(2) == null ? "" : part.group(2).strip();
            if (!partTail.isEmpty()) return partTail;
        }

        if (l.contains("administrative office building") || l.contains("a.o. building")
                || l.contains("a. o. building")) return "Administrative Office Building";
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
        return u.strip();
    }

    // ── eligibility: pull the high-signal criteria when clearly present ───────

    static List<Map<String, Object>> eligibility(String flat) {
        List<Map<String, Object>> out = new ArrayList<>();

        String turnover = moneyAfter(flat,
                "(?:Average\\s+)?Annual\\s+(?:Financial\\s+)?[Tt]urnover|minimum\\s+financial\\s+turnover|"
              + "financial\\s+turnover\\s+of");
        if (turnover != null) {
            out.add(criterion("Financial", "Annual Turnover (as per NIT)", turnover, "gte"));
        }

        String liquid = moneyAfter(flat,
                "Liquid\\s+Assets|[Ww]orking\\s+[Cc]apital|credit\\s+facilities\\s+of|Solvency");
        if (liquid != null) {
            out.add(criterion("Financial", "Liquid assets / credit facility", liquid, "gte"));
        }

        if (find(flat, "similar\\s+(?:nature\\s+of\\s+)?(?:completed\\s+)?works?")) {
            out.add(criterion("Technical", "Similar Work Experience",
                    "As per NIT (completed works of similar nature)", "contains"));
        }
        String years = group(flat,
                "minimum\\s+(?:experience\\s+of\\s+)?(\\d{1,2})\\s*(?:\\([A-Za-z]+\\)\\s*)?years?"
              + "\\s+(?:of\\s+)?experience");
        if (years != null) {
            out.add(criterion("Technical", "Minimum years of experience", years, "gte"));
        }

        String licence = group(flat,
                "((?:Super\\s+Grade|Class[\\s\\-]*A|Class[\\s\\-]*1|Class[\\s\\-]*I)\\s+"
              + "Electrical\\s+Contractors?'?s?\\s+Licen[cs]e)");
        if (licence != null) {
            out.add(criterion("Legal", "Electrical contractor licence",
                    TenderValues.tidy(licence), "contains"));
        }

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

    private static Map<String, Object> criterion(String category, String name,
                                                 String required, String operator) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("category", category);
        c.put("criterionName", name);
        c.put("requiredValue", required);
        c.put("ourValue", "");
        c.put("operator", operator);
        return c;
    }

    /** How far past a label an eligibility figure is still that label's figure. */
    private static final int MONEY_WINDOW = 120;

    private static String moneyAfter(String flat, String labelAlt) {
        Matcher label = Pattern.compile("(?:" + labelAlt + ")", Pattern.CASE_INSENSITIVE).matcher(flat);
        while (label.find()) {
            String window = flat.substring(label.end(),
                    Math.min(flat.length(), label.end() + MONEY_WINDOW));
            String v = TenderValues.money(window);
            if (v != null) return v;
        }
        return null;
    }

    private static String group(String src, String regex) {
        if (src == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(src);
        return m.find() ? m.group(1).strip() : null;
    }

    private static boolean find(String src, String regex) {
        return src != null && Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(src).find();
    }
}
