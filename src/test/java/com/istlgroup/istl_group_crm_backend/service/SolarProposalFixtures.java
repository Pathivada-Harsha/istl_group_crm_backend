package com.istlgroup.istl_group_crm_backend.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two signed-off reference proposals, as the token maps and BOM rows the
 * renderers consume.
 *
 * <p>Shared by {@code SolarProposalDocTest} (the .docx skeleton filler) and
 * {@code SolarProposalPdfTest} (the iText renderer) so the two can never disagree
 * about what the reference data is — the whole point of having both is that they
 * render the SAME proposal two ways.
 */
public final class SolarProposalFixtures {

    public static final String TEMPLATE = "proposal-templates/solar-proposal-template.docx";

    private SolarProposalFixtures() { }

    /** The residential reference: subsidy + ROI both applicable. */
    public static Map<String, String> baseTokens() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("DOC_TITLE", "Proposal");
        t.put("COVER_TITLE", "Client Residential");
        t.put("COVER_SUBTITLE", "4 kWp Ongrid Rooftop Solar Plant");
        t.put("CLIENT_NAME", "Client");
        t.put("SITE_LOCATION", "Hyderabad");
        t.put("CAPACITY_LABEL", "4 kWp");
        t.put("CAPACITY_LINE", "4 kWp On grid Rooftop Solar Plant");
        t.put("WORK_DESC", "4 kWp Rooftop Solar PV Plant with DCR panels");
        t.put("PRICE_BASE", "₹2,32,747");
        t.put("GST_PCT", "8.9%");
        t.put("GST_AMOUNT", "₹20,714");
        t.put("GST_AMOUNT_ROUNDED", "₹20,714");
        t.put("PRICE_TOTAL", "₹2,53,461");
        t.put("AMOUNT_WORDS", "Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only.");
        t.put("QUOTE_VALID_DAYS", "10");
        t.put("QUOTE_VALID_DAY", "Monday");
        t.put("QUOTE_VALID_DATE", "01-01-26");
        t.put("SUBSIDY_LINE", "Central Government Subsidy Applicable: ₹78,000");
        t.put("SUBSIDY_NOTE_1", "Subsidy will be credited directly to the customer's bank account as per MNRE norms.");
        t.put("SUBSIDY_NOTE_2", "Subsidy is subject to approval from the concerned authorities and compliance with prevailing MNRE guidelines.");
        t.put("SUBSIDY_NOTE_3", "Customer has to register on the National Portal for Rooftop Solar and complete all required formalities for subsidy claim.");
        t.put("ROI_TITLE_CAP", "4 kWp");
        t.put("ROI_CAPACITY", "4 kWp");
        t.put("ROI_TARIFF", "₹9.25 / Unit");
        t.put("ROI_SPECIFIC_GEN", "1,460 Units/kWp/Year");
        t.put("ROI_ANNUAL_GEN", "5,840 Units");
        t.put("ROI_ANNUAL_SAVINGS", "₹54,020");
        t.put("ROI_MONTHLY_SAVINGS", "₹4,502");
        t.put("ROI_PAYBACK", "4.7 Years");
        t.put("ROI_ANNUAL_ROI", "21.31%");
        t.put("ROI_LIFE", "25+ Years");
        t.put("ROI_LIFETIME_GEN", "1,46,000 Units");
        t.put("ROI_LIFETIME_SAVINGS", "₹13,50,500");
        t.put("ROI_NET_BENEFIT", "₹10,97,039");
        return t;
    }

    public static Map<String, String> row(String component, String spec, String make, String qty, String unit) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("SL", "");   // filled by the service; the raw filler takes what it's given
        r.put("COMPONENT", component);
        r.put("SPEC", spec);
        r.put("MAKE", make);
        r.put("QTY", qty);
        r.put("UNIT", unit);
        return r;
    }

    /** The residential reference's 19 BOM lines. */
    public static List<Map<String, String>> residentialBom() {
        List<Map<String, String>> b = new ArrayList<>();
        b.add(row("PV Modules", "590Wp & Above N Type TOPCON Bifacial DCR Modules", "Govt.Approved ALMM DCR Panels", "7", "No's"));
        b.add(row("Inverter", "4kW Single Phase Grid Tie inverter", "POLYCAB", "1", "No's"));
        b.add(row("DCDB", "* PC Enclosure with IP 65 Protection * DC Type 2 SPD - 600 V", "Elemax Breaker with Trinity touch DC Fuse", "1", "No's"));
        b.add(row("ACDB", "* IP 65 Indoor Polycarbonate Enclosure * AC Type 2 SPD", "Schnieder Electrical MCCB", "1", "No's"));
        b.add(row("Structures", "Pre-Galvanized Iron MMS upto 300MM Module Lowerend clearance from the roof", "Reputed Make", "1", "Set"));
        b.add(row("Connectors", "Solar MC4 Connectors (1500 V 30A)", "Reputed Make", "50", "Pairs"));
        b.add(row("DC Cable", "1C*4 sq mm DC Cable (75 R + 75 B)", "POLYCAB", "50", "Meters"));
        b.add(row("AC Cable", "4C* 4 sq mm, multi strand Aluminium Armoured AC Cable", "POLYCAB", "100", "Meters"));
        b.add(row("Earthing cable", "1C*4 sq mm Cu Green Flexible cable", "POLYCAB", "25", "Meters"));
        b.add(row("Lightning Arrestor", "Conventional type, multi spike, 1 meter LA", "EXCEL", "1", "No's"));
        b.add(row("Earth Rod", "14 mm Dia, 1 mt long, 100 microns copper bonded earth Rod", "EXCEL", "2", "No's"));
        b.add(row("Chemical Bag", "10 kg carbaneous bag", "EXCEL", "1", "No's"));
        b.add(row("Earth Chamber", "18*18 cm FRP earth bit chamber with cover", "EXCEL", "1", "No's"));
        b.add(row("Lugs", "4 sq mm Ring type lugs", "STANDARD", "10", "No's"));
        b.add(row("Lugs", "4 sq mm Pin type lugs", "STANDARD", "8", "No's"));
        b.add(row("Cable Tie", "UV Protected 200 mm cable tie", "STANDARD", "30", "Piece"));
        b.add(row("PVC Pipes", "25 mm PVC black ISI pipe heavy duty", "AEROPLAST", "40", "Meters"));
        b.add(row("Insulation Tapes", "Supply of Insulation tape (R, Y, B)", "STANDARD", "3", "No's"));
        b.add(row("Trunking Profile", "45*45 PVC Trunking Profile", "AEROPLAST", "1", "No's"));
        return numbered(b);
    }

    /** The commercial reference's 19 BOM lines — different makes and sizes. */
    public static List<Map<String, String>> commercialBom() {
        List<Map<String, String>> b = new ArrayList<>();
        b.add(row("PV Modules", "590Wp & Above N Type TOPCON Bifacial DCR Modules", "SWELECT/ JAKSON", "85", "No's"));
        b.add(row("Inverter", "50kW Single Phase Grid Tie inverter", "POLYCAB", "1", "No's"));
        b.add(row("DCDB", "* PC Enclosure with IP 65 Protection * DC Type 2 SPD - 600 V", "L&T / ABB", "1", "No's"));
        b.add(row("ACDB", "* IP 65 Indoor Polycarbonate Enclosure * AC Type 2 SPD", "L&T/ ABB", "1", "No's"));
        b.add(row("Structures", "Aluminium Structure", "KAMPSOL", "1", "Set"));
        b.add(row("Connectors", "Solar MC4 Connectors (1500 V 30A)", "NINGBO", "50", "Pairs"));
        b.add(row("DC Cable", "1C*6 sq mm DC Cable (75 R + 75 B)", "POLYCAB", "150", "Meters"));
        b.add(row("AC Cable", "4C* 50 sq mm, multi strand Aluminium Armoured AC Cable", "POLYCAB", "100", "Meters"));
        b.add(row("Earthing cable", "1C*4 sq mm Cu Green Flexible cable", "POLYCAB", "250", "Meters"));
        b.add(row("Lightning Arrestor", "Conventional type, multi spike, 1 meter LA", "EXCEL", "1", "No's"));
        b.add(row("Earth Rod", "14 mm Dia, 1 mt long, 100 microns copper bonded earth Rod", "EXCEL", "4", "No's"));
        b.add(row("Chemical Bag", "10 kg carbaneous bag", "EXCEL", "5", "No's"));
        b.add(row("Earth Chamber", "18*18 cm FRP earth bit chamber with cover", "EXCEL", "5", "No's"));
        b.add(row("Lugs", "4 sq mm Ring type lugs", "STANDARD", "10", "No's"));
        b.add(row("Lugs", "4 sq mm Pin type lugs", "STANDARD", "8", "No's"));
        b.add(row("Cable Tie", "UV Protected 200 mm cable tie", "STANDARD", "30", "Piece"));
        b.add(row("PVC Pipes", "25 mm PVC black ISI pipe heavy duty", "AEROPLAST", "40", "Meters"));
        b.add(row("Insulation Tapes", "Supply of Insulation tape (R, Y, B)", "STANDARD", "3", "No's"));
        b.add(row("Trunking Profile", "45*45 PVC Trunking Profile", "AEROPLAST", "1", "No's"));
        return numbered(b);
    }

    public static List<Map<String, String>> numbered(List<Map<String, String>> rows) {
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("SL", String.valueOf(i + 1));
        return rows;
    }

    /** The .docx skeleton bytes, off the classpath. */
    public static byte[] template() throws Exception {
        try (InputStream in = SolarProposalFixtures.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException("skeleton not on the classpath: " + TEMPLATE);
            return in.readAllBytes();
        }
    }
}
