package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest.PoLineItem;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest.PoAdjustmentLine;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.List;

/**
 * Generates a SESOLA Purchase Order PDF that mirrors the company template
 * (header logo, bordered info blocks, pricing table, terms, signatory).
 * Pure iText (same library/pattern as InvoicePdfService) — no Word/LibreOffice deps.
 */
@Service
@Slf4j
public class PurchaseOrderPdfService {

    @jakarta.annotation.PostConstruct
    void checkAssets() {
        log.info("[PO-PDF] asset check — logo:{} poppins-regular:{} poppins-bold:{}",
                loadAsset("sesola_logo.png") != null,
                loadAsset("Poppins-Regular.ttf") != null,
                loadAsset("Poppins-Bold.ttf") != null);
    }

    private static final DeviceRgb HEADER_BG = new DeviceRgb(0xF2, 0xF2, 0xF2);
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");
    private static final DecimalFormat MONEY2 = new DecimalFormat("#,##0.00");

    private static final String COMPANY_NAME = "SESOLA POWER PROJECTS PVT. LTD.";
    private static final String COMPANY_ADDR =
            "8TH Floor Pranava Vaishnoi Business Park,\n" +
            "Sy.No.29,30,31,32, &33, kothaguda, serilingampally\n" +
            "Hyderabad-500084, Telangana, India.";
    private static final String COMPANY_GST = "GST: 36AAYCS6860P1Z2";
    private static final String CONTACT_BLOCK =
            "Contact person: Mr. G Sudhakar\n" +
            "Email ID: sudhakar@sesolaenergy.com\n" +
            "Phone: +91 9247598798";
    private static final String FOOTER_NAME = "SESOLA POWER PROJECTS PRIVATE LIMITED";
    private static final String FOOTER_ADDR =
            "8th Floor, Pranava Vaishnoi Business Park, Kothaguda Village, Serilingampally Mandal, Ranga Reddy District, Telangana India 500084";
    private static final String FOOTER_CONTACT = "www.sesolaenergy.com | Email: info@sesolaenergy.com | M: +91 8340020020";

    private static final String[] TERMS = {
        "Price is firm till the 100% supply.",
        "Payment terms: Before dispatching the goods, 100% payment must be completed",
        "Delivery will be done Immediately.",
        "Delivery Terms: Transportation Extra",
        "Insurance: Sesola Power Projects Pvt. Ltd, Scope",
        "Packing Charges: Included",
        "Freight: Extra as per actuals",
        "Invoice should bear our purchase order number or name of our executive who has ordered the goods/services.",
        "Kindly mention Delivery Challan numbers on the invoice wherever you have delivered goods through a DC. DC should bear our Purchase Order Number.",
        "Test certificate should be provided with batch numbers before dispatches.",
        "The description of goods/services on the invoice should be similar to the description in the PO. Also kindly mention the serial number of goods/services as per PO on the invoice.",
        "Please ensure to supply the material during working hours, i.e., between 9:00 AM to 4:00 PM. In case of critical supplies, if there is any deviation from the same, please keep the concerned person informed about the delivery of material.",
        "Please send us an order acknowledgement confirming acceptance. Delivery of goods and/or services will be deemed as confirmation of acceptance of the terms and conditions of the purchase order.",
        "Subject to Hyderabad Jurisdiction.",
    };

    public byte[] generate(PoDocumentRequest r) throws CustomException {        try {
            if (r.getItems() == null || r.getItems().isEmpty())
                throw new CustomException("PO must have at least one line item");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
            Document doc = new Document(pdf, PageSize.A4);
            // Reserve space: top margin holds the running logo, bottom margin holds the footer block.
            doc.setMargins(78, 32, 92, 32);

            PdfFont bold = loadFont("Poppins-Bold.ttf", com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
            PdfFont normal = loadFont("Poppins-Regular.ttf", com.itextpdf.io.font.constants.StandardFonts.HELVETICA);

            // Running header (logo) + footer (company block) drawn on EVERY page.
            pdf.addEventHandler(com.itextpdf.kernel.events.PdfDocumentEvent.END_PAGE,
                    new HeaderFooterHandler(loadAsset("sesola_logo.png"), bold, normal));

            doc.add(new Paragraph("PURCHASE ORDER").setFont(bold).setFontSize(14)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));

            addHeaderGrid(doc, r, bold, normal);

            doc.add(new Paragraph("Pricing:").setFont(bold).setFontSize(10).setMarginTop(8).setMarginBottom(2));
            addPricingTable(doc, r, bold, normal);

            addBankBlock(doc, r, bold, normal);
            addTerms(doc, r, bold, normal);
            addSignatureBlock(doc, r, bold, normal);

            doc.close();
            return baos.toByteArray();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating PO PDF", e);
            throw new CustomException("Failed to generate PO PDF: " + e.getMessage());
        }
    }

    /** Draws the logo (top-right) and the footer block on every page via END_PAGE events. */
    private class HeaderFooterHandler implements com.itextpdf.kernel.events.IEventHandler {
        private final byte[] logoBytes;
        private final PdfFont bold;
        private final PdfFont normal;
        HeaderFooterHandler(byte[] logoBytes, PdfFont bold, PdfFont normal) {
            this.logoBytes = logoBytes; this.bold = bold; this.normal = normal;
        }
        @Override
        public void handleEvent(com.itextpdf.kernel.events.Event event) {
            try {
                com.itextpdf.kernel.events.PdfDocumentEvent ev = (com.itextpdf.kernel.events.PdfDocumentEvent) event;
                com.itextpdf.kernel.pdf.PdfDocument pdfDoc = ev.getDocument();
                com.itextpdf.kernel.pdf.PdfPage page = ev.getPage();
                com.itextpdf.kernel.geom.Rectangle ps = page.getPageSize();
                com.itextpdf.kernel.pdf.canvas.PdfCanvas pdfCanvas =
                        new com.itextpdf.kernel.pdf.canvas.PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
                com.itextpdf.layout.Canvas canvas = new com.itextpdf.layout.Canvas(pdfCanvas, ps);

                // ── Header: logo top-right ──
                if (logoBytes != null) {
                    try {
                        Image logo = new Image(ImageDataFactory.create(logoBytes));
                        logo.scaleToFit(150, 42);
                        logo.setFixedPosition(ps.getWidth() - 32 - 150, ps.getTop() - 52);
                        canvas.add(logo);
                    } catch (Exception ignore) { /* logo optional */ }
                }

                // ── Footer: green rule above, company block below (clear separation) ──
                float fx = 32, fw = ps.getWidth() - 64;
                pdfCanvas.setStrokeColor(new DeviceRgb(0xCF, 0xE2, 0xB0)).setLineWidth(1.5f)
                        .moveTo(fx, 66).lineTo(fx + fw, 66).stroke();

                Div footer = new Div().setFixedPosition(fx, 16, fw).setTextAlignment(TextAlignment.CENTER);
                footer.add(new Paragraph(FOOTER_NAME).setFont(bold).setFontSize(12)
                        .setFontColor(new DeviceRgb(0x1F, 0x49, 0x7D)).setMargin(0).setMultipliedLeading(1f));
                footer.add(new Paragraph(FOOTER_ADDR).setFont(normal).setFontSize(7f).setMargin(0).setMultipliedLeading(1f));
                footer.add(new Paragraph(FOOTER_CONTACT).setFont(normal).setFontSize(7f).setMargin(0).setMultipliedLeading(1f));
                canvas.add(footer);
                canvas.close();
            } catch (Exception e) {
                log.warn("Header/footer render issue: {}", e.getMessage());
            }
        }
    }



    /** Writable directory for user-uploaded stamps (works on a deployed jar, unlike classpath). */
    public static final String STAMP_DIR = "uploads/po-stamps/";

    /**
     * Save an uploaded stamp image under a sanitized, user-chosen name.
     * Returns the stored file name (what callers pass later as stampFileName).
     */
    public String saveStamp(String desiredName, byte[] imageBytes, String originalFilename) {
        try {
            java.io.File dir = new java.io.File(STAMP_DIR);
            if (!dir.exists()) dir.mkdirs();
            String ext = ".png";
            if (originalFilename != null && originalFilename.contains(".")) {
                String e = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase();
                if (e.matches("\\.(png|jpg|jpeg)")) ext = e;
            }
            String base = (desiredName == null || desiredName.isBlank() ? "stamp" : desiredName)
                    .trim().replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = base + ext;
            java.nio.file.Files.write(new java.io.File(dir, fileName).toPath(), imageBytes);
            log.info("Saved PO stamp '{}' ({} bytes)", fileName, imageBytes.length);
            return fileName;
        } catch (Exception e) {
            log.error("Failed to save stamp", e);
            throw new RuntimeException("Failed to save stamp: " + e.getMessage());
        }
    }

    /** List available stamp file names: the default plus any uploaded ones. */
    public java.util.List<String> listStamps() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.add("sesola_stamp.png"); // bundled default, always offered first
        try {
            java.io.File dir = new java.io.File(STAMP_DIR);
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().matches(".*\\.(png|jpg|jpeg)"));
            if (files != null) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
                for (java.io.File f : files) names.add(f.getName());
            }
        } catch (Exception e) { log.warn("Could not list stamps: {}", e.getMessage()); }
        return new java.util.ArrayList<>(names);
    }

    /** Try the writable stamps dir first, then a few classpath locations. */
    private byte[] loadAsset(String name) {
        // 1) Uploaded stamps live on the filesystem (classpath is read-only in a jar).
        try {
            java.io.File f = new java.io.File(STAMP_DIR, name);
            if (f.exists() && f.isFile()) {
                return java.nio.file.Files.readAllBytes(f.toPath());
            }
        } catch (Exception ignore) { /* fall through to classpath */ }
        // 2) Bundled assets (default logo/stamp/fonts) ship on the classpath.
        String[] paths = { "po-assets/" + name, "static/po-assets/" + name, "templates/po-assets/" + name, name };
        for (String path : paths) {
            try {
                ClassPathResource res = new ClassPathResource(path);
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) { return in.readAllBytes(); }
                }
            } catch (Exception ignore) { /* try next */ }
        }
        return null;
    }

    /** Load an embedded TTF (e.g. Poppins); fall back to a built-in font if absent. */
    private PdfFont loadFont(String ttfName, String fallbackStandard) {
        try {
            byte[] ttf = loadAsset(ttfName);
            if (ttf != null) {
                return PdfFontFactory.createFont(ttf, com.itextpdf.io.font.PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            }
            log.warn("Font {} not found on classpath; falling back to {}. Place it under src/main/resources/po-assets/.",
                     ttfName, fallbackStandard);
        } catch (Exception e) {
            log.warn("Failed to load font {} ({}); using fallback.", ttfName, e.getMessage());
        }
        try { return PdfFontFactory.createFont(fallbackStandard); }
        catch (Exception e) { throw new RuntimeException("Font load failed", e); }
    }

    private Cell noBorder(Cell c) { return c.setBorder(Border.NO_BORDER); }

    private Paragraph p(String text, PdfFont font, float size) {
        Paragraph para = new Paragraph();
        String[] lines = text == null ? new String[]{""} : text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) para.add("\n");
            para.add(new Text(lines[i]).setFont(font).setFontSize(size));
        }
        return para.setMultipliedLeading(1.1f);
    }

    // Kept for call-site compatibility; the full 3-row header is built here in one table.
    private void addHeaderGrid(Document doc, PoDocumentRequest r, PdfFont bold, PdfFont normal) {
        // Fixed 50/50 columns: equal weights + fixed layout + full width, so long text
        // in one cell cannot stretch that column wider than the other.
        Table t = new Table(new float[]{1f, 1f})
                .useAllAvailableWidth()
                .setFixedLayout();
        t.setBorder(new SolidBorder(ColorConstants.BLACK, 0.75f)).setMarginTop(6);
        Border div = new SolidBorder(ColorConstants.BLACK, 0.75f);

        // Row 1 — Company address | Contact (editable, fall back to defaults)
        Cell c1 = noBorder(new Cell()).setBorderRight(div).setBorderBottom(div).setPadding(6);
        c1.add(p("COMPANY ADDRESS:", normal, 9));
        c1.add(p(defIfBlank(r.getCompanyName(), COMPANY_NAME), bold, 9.5f));
        c1.add(p(defIfBlank(r.getCompanyAddress(), COMPANY_ADDR), normal, 9));
        c1.add(p(defIfBlank(r.getCompanyGst(), COMPANY_GST), normal, 9));
        t.addCell(c1);
        Cell c2 = noBorder(new Cell()).setBorderBottom(div).setPadding(6);
        c2.add(p(defIfBlank(r.getCompanyContact(), CONTACT_BLOCK), normal, 9));
        t.addCell(c2);

        // Row 2 — Vendor (To) | PO meta
        Cell v1 = noBorder(new Cell()).setBorderRight(div).setBorderBottom(div).setPadding(6);
        v1.add(p("To,", normal, 9));
        v1.add(p(nz(r.getVendorName()), bold, 9.5f));
        if (notBlank(r.getVendorAddress())) v1.add(p(r.getVendorAddress(), normal, 9));
        if (notBlank(r.getSupplierGst())) v1.add(p("Supplier GST: " + r.getSupplierGst(), bold, 9));
        t.addCell(v1);
        Cell v2 = noBorder(new Cell()).setBorderBottom(div).setPadding(6);
        v2.add(p("PO No: " + nz(r.getPoNo()), normal, 9));
        v2.add(p("DATE: " + nz(r.getPoDate()), normal, 9));
        v2.add(p("QUOTE REF: " + nz(r.getQuoteRef()), normal, 9));
        if (notBlank(r.getQuoteRefDate())) v2.add(p("Date: " + r.getQuoteRefDate(), normal, 9));
        if (notBlank(r.getKindAttention())) v2.add(p("Kind Attention: " + r.getKindAttention(), normal, 9));
        if (notBlank(r.getVendorPhone())) v2.add(p("Phone: " + r.getVendorPhone(), normal, 9));
        if (notBlank(r.getVendorEmail())) v2.add(p("Email ID: " + r.getVendorEmail(), normal, 9));
        t.addCell(v2);

        // Row 3 — Bill To | Ship To
        Cell b1 = noBorder(new Cell()).setBorderRight(div).setPadding(6);
        b1.add(p("Bill To Adress:", normal, 9));
        b1.add(p(nz(defIfBlank(r.getBillToName(), COMPANY_NAME)), bold, 9.5f));
        b1.add(p(defIfBlank(r.getBillToAddress(), COMPANY_ADDR), normal, 9));
        t.addCell(b1);
        Cell b2 = noBorder(new Cell()).setPadding(6);
        b2.add(p("Ship to:", normal, 9));
        b2.add(p(nz(defIfBlank(r.getShipToName(), COMPANY_NAME)), bold, 9.5f));
        b2.add(p(defIfBlank(r.getShipToAddress(), COMPANY_ADDR), normal, 9));
        if (notBlank(r.getShipToPhone())) b2.add(p("Phone no: " + r.getShipToPhone(), normal, 9));
        t.addCell(b2);

        doc.add(t);
    }

    private void addPricingTable(Document doc, PoDocumentRequest r, PdfFont bold, PdfFont normal) {
        Table t = new Table(new float[]{0.6f, 3.2f, 1.3f, 0.8f, 1.1f, 1.2f})
                .setWidth(UnitValue.createPercentValue(100));
        Border b = new SolidBorder(ColorConstants.BLACK, 0.5f);

        String[] heads = {"S.No", "Item & Description", "HSN Code", "Qty", "Price Per Unit", "AMOUNT"};
        for (String h : heads) {
            t.addHeaderCell(new Cell().add(p(h, bold, 9)).setBackgroundColor(HEADER_BG)
                    .setBorder(b).setPadding(4).setTextAlignment(TextAlignment.CENTER));
        }

        List<PoLineItem> items = r.getItems();
        int sNo = 1;
        for (PoLineItem it : items) {
            // S.No is the row position — compute it here so it's always correct,
            // independent of whatever the payload sent.
            int rowNo = (it.getSNo() != null && it.getSNo() > 0) ? it.getSNo() : sNo;
            t.addCell(cell(String.valueOf(rowNo), normal, b, TextAlignment.CENTER));
            t.addCell(cell(nz(it.getDescription()), normal, b, TextAlignment.LEFT));
            t.addCell(cell(nz(it.getHsnCode()), normal, b, TextAlignment.CENTER));
            t.addCell(cell(num(it.getQty()), normal, b, TextAlignment.CENTER));
            t.addCell(cell(money(it.getPricePerUnit()), normal, b, TextAlignment.RIGHT));
            t.addCell(cell(money(it.getAmount()), normal, b, TextAlignment.RIGHT));
            sNo++;
        }

        // GST row
        addSpanSummaryRow(t, b, normal, bold,
                "GST " + (r.getGstPercent() != null ? trimNum(r.getGstPercent()) + "%" : ""), money(r.getGstAmount()));
        // Total row
        addSpanSummaryRow(t, b, normal, bold, "Total Amount", money(r.getTotalAmount()));

        // Optional adjustments (label spans description, amount in last col)
        if (r.getAdjustments() != null) {
            for (PoAdjustmentLine adj : r.getAdjustments()) {
                if (adj == null || (!notBlank(adj.getLabel()) && adj.getAmount() == null)) continue;
                t.addCell(cell("", normal, b, TextAlignment.CENTER));
                t.addCell(cell(nz(adj.getLabel()), normal, b, TextAlignment.LEFT));
                t.addCell(cell("", normal, b, TextAlignment.CENTER));
                t.addCell(cell("", normal, b, TextAlignment.CENTER));
                t.addCell(cell("", normal, b, TextAlignment.RIGHT));
                t.addCell(cell(money(adj.getAmount()), normal, b, TextAlignment.RIGHT));
            }
        }
        // Let the pricing table flow across pages when there are many items.
        // Header cells (addHeaderCell) repeat automatically on each continuation page.
        doc.add(t.setMarginBottom(6));
    }

    // A summary row: empty S.No + Description + HSN, label spanning Qty+Price, value in AMOUNT.
    private void addSpanSummaryRow(Table t, Border b, PdfFont normal, PdfFont bold, String label, String value) {
        t.addCell(new Cell(1, 3).setBorder(b).setPadding(4)); // blank across first 3 cols
        t.addCell(new Cell(1, 2).add(p(label, bold, 9)).setBorder(b).setPadding(4).setTextAlignment(TextAlignment.RIGHT));
        t.addCell(cell(value, bold, b, TextAlignment.RIGHT));
    }

    private void addBankBlock(Document doc, PoDocumentRequest r, PdfFont bold, PdfFont normal) {
        Div block = new Div().setKeepTogether(true).setMarginTop(4);
        block.add(new Paragraph("Account details of Vender:").setFont(bold).setFontSize(10).setMarginBottom(2));
        StringBuilder sb = new StringBuilder();
        if (notBlank(r.getBankAccountName())) sb.append("Account Name: ").append(r.getBankAccountName()).append("\n");
        if (notBlank(r.getBankName()))        sb.append("Bank: ").append(r.getBankName()).append("\n");
        if (notBlank(r.getBankBranch()))      sb.append("Branch: ").append(r.getBankBranch()).append("\n");
        if (notBlank(r.getBankAccountNo()))   sb.append("A/c no: ").append(r.getBankAccountNo()).append("\n");
        if (notBlank(r.getBankIfsc()))        sb.append("IFSC: ").append(r.getBankIfsc());
        block.add(p(sb.toString(), normal, 9));
        doc.add(block);
    }

    private void addTerms(Document doc, PoDocumentRequest r, PdfFont bold, PdfFont normal) {
        // Use edited terms when supplied; otherwise the standard SESOLA terms.
        java.util.List<String> terms = (r.getTerms() != null && !r.getTerms().isEmpty())
                ? r.getTerms() : java.util.Arrays.asList(TERMS);
        // Terms flow continuously after the bank details (no forced page break).
        // Wrap the heading with the first term so the heading never lands alone at a page foot.
        Div head = new Div().setKeepTogether(true).setMarginTop(10);
        head.add(new Paragraph("Terms & Conditions:").setFont(bold).setFontSize(10).setMarginBottom(4));
        if (!terms.isEmpty()) {
            head.add(new Paragraph("1. " + terms.get(0)).setFont(normal).setFontSize(9).setMarginBottom(3).setMultipliedLeading(1.1f));
        }
        doc.add(head);
        for (int i = 1; i < terms.size(); i++) {
            doc.add(new Paragraph((i + 1) + ". " + terms.get(i)).setFont(normal).setFontSize(9)
                    .setMarginBottom(3).setMultipliedLeading(1.1f));
        }
    }

    private void addSignatureBlock(Document doc, PoDocumentRequest r, PdfFont bold, PdfFont normal) {
        Table t = new Table(new float[]{1f, 1f}).setWidth(UnitValue.createPercentValue(100)).setMarginTop(14);

        Cell left = noBorder(new Cell()).setPadding(2);
        left.add(p(COMPANY_NAME.replace(" PVT. LTD.", " PVT LTD"), bold, 9.5f));
        // Company stamp sits between the company name and the signatory name.
        String stampName = defIfBlank(r.getStampFileName(), "sesola_stamp.png");
        byte[] stampBytes = loadAsset(stampName);
        if (stampBytes == null && !"sesola_stamp.png".equals(stampName)) stampBytes = loadAsset("sesola_stamp.png");
        if (stampBytes != null) {
            try {
                Image stamp = new Image(ImageDataFactory.create(stampBytes));
                stamp.scaleToFit(95, 95);
                left.add(new Paragraph().add(stamp).setMarginTop(2).setMarginBottom(2));
            } catch (Exception e) { log.warn("PO stamp failed to render: {}", e.getMessage()); left.add(new Paragraph("\n\n\n")); }
        } else {
            left.add(new Paragraph("\n\n\n")); // space for a manual signature if no stamp
        }
        left.add(p(nz(defIfBlank(r.getSignatoryName(), "G Sudhakar")), bold, 9.5f));
        left.add(p(defIfBlank(r.getSignatoryTitle(), "Manager-Procurement"), bold, 9));
        left.add(p(defIfBlank(r.getSignatoryPhone(), "+91-9247598798"), bold, 9));
        left.add(p("Authorized Signatory", bold, 9));
        t.addCell(left);

        Cell right = noBorder(new Cell()).setPadding(2);
        right.add(p("Order Acceptance", bold, 9.5f).setTextAlignment(TextAlignment.CENTER));
        right.add(new Paragraph("\n\n\n\n\n"));
        right.add(p("M/s " + nz(r.getVendorName()), bold, 9.5f).setTextAlignment(TextAlignment.CENTER));
        t.addCell(right);
        doc.add(t.setKeepTogether(true));
    }

    // ── small helpers ──
    private Cell cell(String text, PdfFont font, Border b, TextAlignment align) {
        return new Cell().add(p(text, font, 9)).setBorder(b).setPadding(4).setTextAlignment(align);
    }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String defIfBlank(String s, String d) { return notBlank(s) ? s : d; }
    private static String money(Double v) { return v == null ? "" : MONEY.format(v); }
    private static String num(Double v) {
        if (v == null) return "";
        if (v == Math.floor(v)) return String.valueOf(v.longValue());
        return MONEY2.format(v);
    }
    private static String trimNum(Double v) {
        if (v == null) return "";
        if (v == Math.floor(v)) return String.valueOf(v.longValue());
        return String.valueOf(v);
    }
}