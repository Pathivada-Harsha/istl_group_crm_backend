package com.istlgroup.istl_group_crm_backend.service.tender;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The label vocabulary, and the scanner that finds every label on a line.
 *
 * <p>This exists because of how a two-column summary grid comes out of PDF text
 * extraction: <b>two label/value pairs land on one line.</b>
 *
 * <pre>
 * Advertised Value 68918769.50 Tendering Section WORKS
 * Earnest Money (Rs.) 534000.00 Validity of Offer ( Days) 180
 * Tender Type Open Bidding System Two Packet System
 * </pre>
 *
 * <p>A capture that runs to end of line reads the tender type as
 * "Open Bidding System Two Packet System". So every capture terminates at the
 * <em>next recognised label</em>, and the vocabulary therefore has to include
 * labels this system never extracts — Tendering Section, Validity of Offer,
 * Bidding Unit and friends exist here purely as terminators.
 *
 * <p>Where two labels start at the same place the longer one wins, so
 * "Tender Closing Date Time" is never read as "Closing Date" with a stray
 * "Time" left in front of the value.
 */
public final class TenderLabels {

    // ── field keys the extractor asks for by name ─────────────────────────────
    public static final String TENDER_NO      = "tenderNumber";
    public static final String TENDER_NAME    = "tenderName";
    public static final String AUTHORITY      = "issuingAuthority";
    public static final String CLIENT         = "clientCompany";
    public static final String ADDRESS        = "clientAddress";
    public static final String EMAIL          = "clientContactEmail";
    public static final String PHONE          = "clientContactPhone";
    public static final String ESTIMATED      = "estimatedValue";
    public static final String EMD            = "emdAmount";
    public static final String SUBMISSION     = "submissionDeadline";
    public static final String BID_OPENING    = "bidOpening";
    public static final String TECH_OPENING   = "technicalOpeningDate";
    public static final String FIN_OPENING    = "financialOpeningDate";
    public static final String PUBLISH        = "publishDate";
    public static final String CLARIFICATION  = "clarificationCloseDate";
    public static final String PRE_BID        = "preBidDate";
    public static final String TENDER_TYPE    = "tenderType";
    public static final String PORTAL         = "portalLink";
    public static final String LOCATION       = "location";
    public static final String TERMINATOR     = "-";

    /**
     * One label. {@code strength} breaks ties between candidates for the same
     * field: a purpose-built label ("Bid Submission End") beats a generic one
     * ("Closing Date") wherever both appear, whatever the document order.
     */
    public record Label(String key, String display, Pattern pattern, int strength) {}

    /** A label found on a line: where the label sits, and where its value starts. */
    public record Hit(Label label, int start, int labelEnd, int valueStart) {}

    /** Trailing punctuation between a label and its value ("No.:", " - ", ": "). */
    private static final String SEP = "\\s*\\.?\\s*[:\\-–—]?\\s*";

    private static final List<Label> LABELS = new ArrayList<>();

    private static void label(String key, String display, String core, int strength) {
        // (?<![A-Za-z0-9]) keeps "EMD" out of the middle of a word; group 1 is the
        // label itself so the caller can report exactly what matched.
        Pattern p = Pattern.compile("(?<![A-Za-z0-9])(" + core + ")(?![A-Za-z])" + SEP,
                Pattern.CASE_INSENSITIVE);
        LABELS.add(new Label(key, display, p, strength));
    }

    private static void label(String key, String display, String core) {
        label(key, display, core, 1);
    }

    /** Terminator-only: recognised so captures stop, never extracted itself. */
    private static void stop(String display, String core) {
        label(TERMINATOR, display, core, 0);
    }

    static {
        // ── tender reference ──
        // DNIT / DNIe-T / NIeT / e-NIT / IFB are the spellings that were missing;
        // without DNIe-T the TREDA reference went unrecognised and the trailing
        // ", dated 12/06/2026" was captured as the tender number instead.
        label(TENDER_NO, "Tender No.",
                "DNIe-?T\\s*(?:No|Number)?|DNIT\\s*(?:No|Number)?|NIe-?T\\s*(?:No|Number)?"
              + "|e-?NIT\\s*(?:No|Number)?|IFB\\s*(?:No|Number)?"
              + "|Bid\\s*Enquiry\\s*(?:No|Number)|Tender\\s*Enquiry\\s*(?:No|Number)"
              + "|Tender\\s*Reference\\s*(?:No|Number)?|NIT\\s*(?:No|Number)"
              + "|Tender\\s*ID|Bid\\s*(?:No|Number)|e-?Tender\\s*(?:No|Number)"
              + "|Work\\s*Indent\\s*(?:No|Number)|Specification\\s*(?:No|Number)"
              + "|Package\\s*(?:No|Number)|Tender\\s*(?:No|Number)|Reference\\s*(?:No|Number)", 2);

        // ── work description ──
        label(TENDER_NAME, "Name of Work",
                "TENDER\\s+FOR\\s+THE\\s+WORK\\s+OF|TENDER\\s+FOR\\s+THE\\s+WORK"
              + "|Name\\s+of\\s+(?:the\\s+)?Work|Work\\s+Description"
              + "|Description\\s+of\\s+(?:the\\s+)?Work"
              + "|Brief\\s+description\\s+of\\s+(?:the\\s+)?work"
              + "|Title\\s+of\\s+(?:the\\s+)?Work|Subject", 2);

        // ── parties ──
        // Deliberately NOT extended: both templates use these phrases in body
        // prose, which is exactly how the field became a sentence fragment. The
        // real authority is unlabelled in both, and is found by position instead.
        label(AUTHORITY, "Tender Inviting Authority",
                "Tender\\s+Inviting\\s+Authority|Bid\\s+Inviting\\s+Authority"
              + "|Tendering\\s+Authority|Inviting\\s+Authority"
              + "|Name\\s+of\\s+(?:the\\s+)?(?:Employer|Owner|Department)");
        label(CLIENT, "Procurement Entity",
                "Procurement\\s+Entity|Name\\s+of\\s+(?:the\\s+)?Organi[sz]ation"
              + "|Purchasing\\s+Authority|Owner\\s*/\\s*Employer");
        label(ADDRESS, "Address", "Address(?:\\s+[Ff]or\\s+Communication)?");
        label(EMAIL, "Email", "e-?mail\\s*(?:ID|Address)?");
        label(PHONE, "Telephone",
                "Telephone\\s*(?:No\\.?s?|Number)?|Phone\\s*(?:No\\.?s?|Number)?"
              + "|Mobile\\s*(?:No\\.?s?|Number)?|Contact\\s*(?:No\\.?s?|Number)");

        // ── money ──
        // "Advertised Value" is the IREPS standard term and was not recognised.
        label(ESTIMATED, "Estimated Cost",
                "Advertised\\s+Value|Amount\\s+Put\\s+[Tt]o\\s+Tender"
              + "|Estimated\\s+(?:Contract\\s+)?(?:Cost|Value|Amount)"
              + "|Approx(?:imate)?\\.?\\s+(?:Cost|Value)|Value\\s+of\\s+(?:the\\s+)?[Ww]ork"
              + "|Tender\\s+Value|Contract\\s+Value|Total\\s+Estimated|Estimated\\s+Price", 2);
        label(EMD, "EMD / Bid Security",
                "EMD\\s*/\\s*Bid\\s*[Ss]ecurity|Bid\\s*[Ss]ecurity\\s*/\\s*EMD"
              + "|Bid\\s*[Ss]ecurity\\s*\\(\\s*EMD\\s*\\)|Earnest\\s+Money\\s+Deposit"
              + "|Earnest\\s+Money|Bid\\s*[Ss]ecurity|EMD\\s*[Aa]mount|EMD", 2);

        // ── dates ──
        label(SUBMISSION, "Bid Submission End",
                "Tender\\s+Closing\\s+Date(?:\\s*(?:&|and)?\\s*Time)?"
              + "|Closing\\s+Date\\s*/\\s*Time"
              + "|Bid\\s+Submission\\s+(?:End|Closing)(?:\\s+Date)?"
              + "|Last\\s+[Dd]ate\\s+and\\s+[Tt]ime\\s+for\\s+submission"
              + "|Last\\s+[Dd]ate\\s+for\\s+submission"
              + "|Last\\s+[Dd]ate\\s+of\\s+(?:bid\\s+)?submission"
              + "|Due\\s+[Dd]ate\\s+(?:of|for)\\s+submission"
              + "|Last\\s+[Dd]ate\\s+for\\s+[Rr]eceipt", 3);
        // Generic, and genuinely ambiguous — TREDA's "Closing date" is the
        // clarification window, not the bid deadline.
        label(SUBMISSION, "Closing Date", "Closing\\s+Date", 1);

        label(TECH_OPENING, "Technical Bid Opening",
                "(?:Time\\s+and\\s+)?Date\\s+of\\s+[Oo]pening\\s+of\\s+Techno[\\s\\-]*Commercial"
              + "|Techno[\\s\\-]*Commercial\\s+Bid\\s+Opening|Technical\\s+Bid\\s+Opening"
              + "|Date\\s+of\\s+[Oo]pening\\s+of\\s+Technical", 3);
        label(FIN_OPENING, "Price Bid Opening",
                "Price\\s+Bid\\s+Opening|Financial\\s+Bid\\s+Opening"
              + "|[Oo]pening\\s+of\\s+(?:the\\s+)?(?:Price|Financial)\\s+Bid", 3);
        // Generic opener — which bid it refers to is decided from the record text.
        label(BID_OPENING, "Bid Opening", "(?:Online\\s+)?Bid\\s+Opening(?:\\s+Date)?", 1);

        label(PUBLISH, "Date of Publishing",
                "Date\\s+Time\\s+Of\\s+Uploading\\s+Tender|Date\\s+of\\s+Publishing(?:\\s+of)?"
              + "|Date\\s+of\\s+Publication|Published\\s+(?:on|Date)");
        label(CLARIFICATION, "Clarification Closing",
                "Closing\\s+date\\s+for\\s+seeking\\s+clarification"
              + "|Last\\s+date\\s+for\\s+seeking\\s+clarification", 2);
        label(PRE_BID, "Pre-Bid Meeting",
                "Pre[\\s\\-]?Bid\\s+Conference\\s+Date(?:\\s*Time)?"
              + "|Pre[\\s\\-]?Bid\\s+(?:Meeting|Conference)(?:\\s+Date)?");

        // ── classification ──
        label(TENDER_TYPE, "Tender Type", "Tender\\s+Type|Type\\s+of\\s+Tender|Bid\\s+Type");
        label(PORTAL, "Tender Portal",
                "Bidding\\s+portal|Tender\\s+Portal|e-?Procurement\\s+(?:Portal|Platform)"
              + "|Portal\\s+Link|Website");
        label(LOCATION, "Location of Work",
                "Location\\s+of\\s+(?:the\\s+)?[Ww]ork|Site\\s+Location|Place\\s+of\\s+[Ww]ork");

        // ── terminators only ──────────────────────────────────────────────────
        // Never extracted; they exist so the capture before them stops. Most come
        // straight off the IREPS grid, where they share a line with a value that
        // IS wanted.
        stop("Tendering Section", "Tendering\\s+Section");
        stop("Validity of Offer", "Validity\\s+of\\s+Offer");
        stop("Bidding Unit", "Bidding\\s+Unit");
        stop("Bidding Style", "Bidding\\s+Style");
        stop("Bidding System", "Bidding\\s+System");
        stop("Bidding type", "Bidding\\s+type");
        stop("Bidding Start Date", "Bidding\\s+Start\\s+Date");
        stop("Contract Type", "Contract\\s+Type");
        stop("Contract Category", "Contract\\s+Category");
        stop("Period of Completion", "Period\\s+of\\s+Completion");
        stop("Completion Period", "Completion\\s+[Pp]eriod(?:\\s+for)?");
        stop("Expenditure Type", "Expenditure\\s+Type");
        stop("Ranking Order For Bids", "Ranking\\s+Order\\s+For\\s+Bids");
        stop("Number of JV Member Allowed", "Number\\s+of\\s+JV\\s+Members?\\s+Allowed");
        stop("Number of Consortium Member Allowed",
                "Number\\s+of\\s+Consortium\\s+Members?\\s+Allowed");
        stop("Are JV allowed to bid", "Are\\s+JV\\s+allowed\\s+to\\s+bid");
        stop("Are Consortium allowed to bid", "Are\\s+Consortium\\s+allowed\\s+to\\s+bid");
        stop("Pre-Bid Conference Required", "Pre[\\s\\-]?Bid\\s+Conference\\s+Required");
        stop("Tender Doc. Cost", "Tender\\s+Doc\\.?\\s+Cost");
        stop("Tender Fee", "Tender\\s+Fee");
        stop("Bid Validity", "Bid\\s+Validity|Validity\\s+of\\s+Tender");
        stop("Place of Opening of Bids", "Place\\s+of\\s+Opening\\s+of\\s+Bids?");
        stop("Document download", "Document\\s+download");
        stop("Date of start of", "Date\\s+of\\s+start\\s+of");
        // The officer, not the agency: keeping it out of the authority set is
        // what stops "Sri <name>, Joint Director" landing in issuingAuthority.
        stop("Officer Inviting Bids", "Officer\\s+Inviting\\s+Bids");
        stop("Availability of Tender Documents",
                "Availability\\s+of\\s+Tender\\s+Documents?(?:\\s+In)?");
        stop("Tele-fax", "Tele-?fax");
        stop("URL", "URL");
        stop("Online bid submission", "Online\\s+bid\\s+submission");
    }

    private TenderLabels() {}

    public static List<Label> all() { return List.copyOf(LABELS); }

    /**
     * Every label on a line, left to right, non-overlapping, longest-first at
     * any given start.
     */
    public static List<Hit> scan(String line) {
        List<Hit> raw = new ArrayList<>();
        if (line == null || line.isEmpty()) return raw;
        for (Label l : LABELS) {
            Matcher m = l.pattern().matcher(line);
            while (m.find()) {
                raw.add(new Hit(l, m.start(), m.end(1), m.end()));
            }
        }
        raw.sort((a, b) -> a.start() != b.start()
                ? Integer.compare(a.start(), b.start())
                : Integer.compare(b.labelEnd(), a.labelEnd()));

        List<Hit> kept = new ArrayList<>();
        int consumedTo = -1;
        for (Hit h : raw) {
            if (h.start() < consumedTo) continue;      // sits inside a longer label
            kept.add(h);
            consumedTo = h.labelEnd();
        }
        return kept;
    }

    /** Where the value that follows {@code hits[index]} ends on this line. */
    public static int valueEnd(List<Hit> hits, int index, int lineLength) {
        return index + 1 < hits.size() ? hits.get(index + 1).start() : lineLength;
    }

    /** True when the line carries no label at all — a continuation or an orphan. */
    public static boolean hasNoLabel(String line) {
        return scan(line).isEmpty();
    }
}
