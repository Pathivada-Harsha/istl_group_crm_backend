package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.util.MoneyRounding;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.borders.Border;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private final CustomersService customersService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    
    // invoice_items has no hsn_code column, so every line prints the same HSN.
    // Giving items their own HSN is a schema change and a separate piece of work;
    // this at least names the assumption instead of leaving the literal buried in
    // two different tables.
    private static final String DEFAULT_HSN = "90283090";

    /** Unit shown when a line carries none. */
    private static final String DEFAULT_UNIT_TYPE = "Nos";

    // ISTL Company details
    private static final String ISTL_NAME = "ISCIENTIFIC TECHSOLUTIONS LABS PVT LTD";
    private static final String ISTL_ADDRESS = "133/1/B, 1st Floor, Phase II, IDA Cherlapally";
    private static final String ISTL_CITY = "Hyderabad, Telangana - 500051";
    private static final String ISTL_GSTIN = "36AAGCI8913D1ZL";
    private static final String ISTL_PAN = "AAGCI8913D";
    private static final String ISTL_EMAIL = "accounts@istlabs.in";
    private static final String ISTL_UDYAM = "UDYAM(MSME)- TS-20-0045223";
    private static final String ISTL_CIN = "U31900KA2022PTC167257";

    // SESOLA Company details
    private static final String SESOLA_NAME = "SESOLA POWER PROJECTS PRIVATE LIMITED";
    private static final String SESOLA_ADDRESS = "8th Floor, Pranava Vaishnoi Business Park";
    private static final String SESOLA_CITY = "Survey Nos.29, 30, 31, 32 And 33 Of Kothaguda Village, Serilingampally Mandal, Ranga Reddy District, Telangana India 500084";
    private static final String SESOLA_GSTIN = "36AASCS1234D1ZL";
    private static final String SESOLA_PAN = "AASCS1234D";
    private static final String SESOLA_EMAIL = "accounts@sesola.com";
    private static final String SESOLA_UDYAM = "";
    private static final String SESOLA_CIN = "";

    private static final String STATE_CODE = "36";
    private static final String STATE_NAME = "Telangana";

    public byte[] generateInvoicePdf(InvoiceEntity invoice) throws CustomException {
        try {
            log.info("Generating GST compliant PDF for invoice: {}", invoice.getInvoiceNo());
            
            if (invoice.getProjectId() == null || invoice.getProjectId().isEmpty()) {
                throw new CustomException("Invoice must have a project ID");
            }
            
            if (invoice.getItems() == null || invoice.getItems().isEmpty()) {
                throw new CustomException("Invoice must have at least one item");
            }

            CustomersEntity customer = customersService.getCustomerByProjectId(invoice.getProjectId());
            if (customer == null) {
                throw new CustomException("Customer not found for project: " + invoice.getProjectId());
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf, PageSize.A4);
            document.setMargins(20, 20, 20, 20);

            PdfFont bold = PdfFontFactory.createFont(
                    com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
            PdfFont normal = PdfFontFactory.createFont(
                    com.itextpdf.io.font.constants.StandardFonts.HELVETICA);

            // Add title
            addTitle(document, bold);
            
            // Add company and customer info
            addCompanyAndCustomerInfo(document, invoice, customer, bold, normal);
            
            // The invoice's money, computed ONCE and shared by both sections below.
            // ofStored() takes the three totals from the stored columns and rounds
            // nothing, so this PDF cannot print a total the database disagrees
            // with. Both sections used to derive their own, independently.
            InvoiceTotals.Totals totals = InvoiceTotals.ofStored(invoice.getItems(), invoice);

            // Add items table (EXACT FORMAT FROM SAMPLE)
            addItemsTableExact(document, invoice, totals, bold, normal);

            // Add tax summary
            addTaxSummaryExact(document, totals, bold, normal);
            
            // Add footer
            addFooter(document, invoice, bold, normal);

            document.close();
            
            log.info("PDF generated successfully for invoice: {}", invoice.getInvoiceNo());
            return baos.toByteArray();

        } catch (CustomException e) {
            log.error("Custom error generating invoice PDF: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error generating invoice PDF", e);
            throw new CustomException("Error generating invoice PDF: " + e.getMessage());
        }
    }

    private void addTitle(Document document, PdfFont bold) {
        Paragraph title = new Paragraph("Tax Invoice")
                .setFont(bold)
                .setFontSize(16)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(10);
        document.add(title);
    }

    private void addCompanyAndCustomerInfo(Document document, InvoiceEntity invoice, 
            CustomersEntity customer, PdfFont bold, PdfFont normal) {

        boolean isSesola = "SESOLA".equalsIgnoreCase(invoice.getCompany());
        
        String companyName = isSesola ? SESOLA_NAME : ISTL_NAME;
        String companyAddress = isSesola ? SESOLA_ADDRESS : ISTL_ADDRESS;
        String companyCity = isSesola ? SESOLA_CITY : ISTL_CITY;
        String companyGstin = isSesola ? SESOLA_GSTIN : ISTL_GSTIN;
        String companyPan = isSesola ? SESOLA_PAN : ISTL_PAN;
        String companyEmail = isSesola ? SESOLA_EMAIL : ISTL_EMAIL;
        String companyUdyam = isSesola ? SESOLA_UDYAM : ISTL_UDYAM;
        String companyCin = isSesola ? SESOLA_CIN : ISTL_CIN;

        // Main header table
        Table mainTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        mainTable.setBorder(new SolidBorder(ColorConstants.BLACK, 1));

        // Left - Company Details
        Cell companyCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 1))
                .setPadding(8);

        companyCell.add(new Paragraph(companyName)
                .setFont(bold)
                .setFontSize(10));
        companyCell.add(new Paragraph(companyAddress)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph(companyCity)
                .setFont(normal)
                .setFontSize(8));
        
        if (companyUdyam != null && !companyUdyam.isEmpty()) {
            companyCell.add(new Paragraph(companyUdyam)
                    .setFont(normal)
                    .setFontSize(8));
        }
        
        companyCell.add(new Paragraph("GSTIN/UIN: " + companyGstin)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph("State Name: " + STATE_NAME + ", Code: " + STATE_CODE)
                .setFont(normal)
                .setFontSize(8));
        
        if (companyCin != null && !companyCin.isEmpty()) {
            companyCell.add(new Paragraph("CIN: " + companyCin)
                    .setFont(normal)
                    .setFontSize(8));
        }
        
        companyCell.add(new Paragraph("E-Mail: " + companyEmail)
                .setFont(normal)
                .setFontSize(8));

        mainTable.addCell(companyCell);

        // Right - Invoice Details
        Cell invoiceDetailsCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(8);

        invoiceDetailsCell.add(new Paragraph("Invoice No.")
                .setFont(bold)
                .setFontSize(8));
        invoiceDetailsCell.add(new Paragraph(invoice.getInvoiceNo())
                .setFont(normal)
                .setFontSize(8)
                .setMarginBottom(3));
        
        invoiceDetailsCell.add(new Paragraph("Dated")
                .setFont(bold)
                .setFontSize(8));
        invoiceDetailsCell.add(new Paragraph(invoice.getInvoiceDate() != null ? 
                invoice.getInvoiceDate().format(DATE_FORMAT) : "")
                .setFont(normal)
                .setFontSize(8)
                .setMarginBottom(3));
        
        invoiceDetailsCell.add(new Paragraph("Mode/Terms of Payment")
                .setFont(bold)
                .setFontSize(8));
        invoiceDetailsCell.add(new Paragraph("As per terms")
                .setFont(normal)
                .setFontSize(8));

        mainTable.addCell(invoiceDetailsCell);

        document.add(mainTable);

        // Customer Details
        Table customerTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        customerTable.setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        customerTable.setMarginTop(0);

        // Buyer (Bill to)
        Cell billToCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 1))
                .setPadding(8);

        billToCell.add(new Paragraph("Buyer (Bill to)")
                .setFont(bold)
                .setFontSize(8));
        billToCell.add(new Paragraph(customer.getCompanyName() != null ? customer.getCompanyName() : customer.getName())
                .setFont(bold)
                .setFontSize(9));
        billToCell.add(new Paragraph(customer.getAddress() != null ? customer.getAddress() : "")
                .setFont(normal)
                .setFontSize(8));
        billToCell.add(new Paragraph((customer.getCity() != null ? customer.getCity() : "") + 
                ", " + (customer.getState() != null ? customer.getState() : "") + 
                (customer.getPincode() != null ? " - " + customer.getPincode() : ""))
                .setFont(normal)
                .setFontSize(8));
        billToCell.add(new Paragraph("GSTIN/UIN: " + 
                (customer.getGstNumber() != null ? customer.getGstNumber() : "N/A"))
                .setFont(normal)
                .setFontSize(8));
        billToCell.add(new Paragraph("State Name: " + 
                (customer.getState() != null ? customer.getState() : STATE_NAME) + ", Code: " + STATE_CODE)
                .setFont(normal)
                .setFontSize(8));

        customerTable.addCell(billToCell);

        // Consignee (Ship to)
        Cell shipToCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(8);

        shipToCell.add(new Paragraph("Consignee (Ship to)")
                .setFont(bold)
                .setFontSize(8));
        shipToCell.add(new Paragraph(customer.getCompanyName() != null ? customer.getCompanyName() : customer.getName())
                .setFont(bold)
                .setFontSize(9));
        shipToCell.add(new Paragraph(customer.getAddress() != null ? customer.getAddress() : "")
                .setFont(normal)
                .setFontSize(8));
        shipToCell.add(new Paragraph((customer.getCity() != null ? customer.getCity() : "") + 
                ", " + (customer.getState() != null ? customer.getState() : "") + 
                (customer.getPincode() != null ? " - " + customer.getPincode() : ""))
                .setFont(normal)
                .setFontSize(8));
        shipToCell.add(new Paragraph("GSTIN/UIN: " + 
                (customer.getGstNumber() != null ? customer.getGstNumber() : "N/A"))
                .setFont(normal)
                .setFontSize(8));
        shipToCell.add(new Paragraph("State Name: " + 
                (customer.getState() != null ? customer.getState() : STATE_NAME) + ", Code: " + STATE_CODE)
                .setFont(normal)
                .setFontSize(8));

        customerTable.addCell(shipToCell);

        document.add(customerTable);
    }

    /**
     * EXACT ITEMS TABLE FORMAT FROM SAMPLE
     */
    private void addItemsTableExact(Document document, InvoiceEntity invoice,
                                    InvoiceTotals.Totals totals,
                                    PdfFont bold, PdfFont normal) {
        
        // Table with exact column structure from sample
        Table itemsTable = new Table(new float[]{0.7f, 4f, 1.5f, 1.5f, 1.5f, 1.3f, 2f})
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(0);

        // Header row - matching sample exactly
        addTableHeader(itemsTable, "Sl\nNo.", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "Description of Goods", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "HSN/SAC", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "Quantity", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "Rate\nper", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "per", bold, TextAlignment.CENTER);
        addTableHeader(itemsTable, "Amount", bold, TextAlignment.CENTER);

        int slNo = 1;
        for (InvoiceItemEntity item : safeItems(invoice)) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            String unitType = item.getUnitType() != null ? item.getUnitType() : DEFAULT_UNIT_TYPE;
            BigDecimal lineAmount = quantity.multiply(unitPrice);

            // Row data - exact format
            itemsTable.addCell(createDataCell(String.valueOf(slNo++), normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(item.getDescription() != null ? item.getDescription() : "", normal, TextAlignment.LEFT));
            itemsTable.addCell(createDataCell(DEFAULT_HSN, normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(formatQuantity(quantity), normal, TextAlignment.RIGHT));
            itemsTable.addCell(createDataCell(formatAmount(unitPrice), normal, TextAlignment.RIGHT));
            itemsTable.addCell(createDataCell(unitType, normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(formatAmount(lineAmount), normal, TextAlignment.RIGHT));
        }

        // Tax rows — one CGST row and one SGST row PER RATE.
        //
        // This used to read the rate off items.get(0) and apply it to the whole
        // subtotal, so an invoice mixing 5% and 18% lines was taxed entirely at
        // whichever rate happened to be first, and an invoice with no items threw
        // IndexOutOfBoundsException. The rates and amounts now come from the same
        // breakdown the stored total was built from.
        for (InvoiceTotals.RateLine line : totals.byRate()) {
            BigDecimal halfRate = line.halfRatePercent();

            // CGST Row - with spacing exactly like sample
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));

            Cell cgstDescCell = new Cell(1, 2)
                    .add(new Paragraph("TG-CGST Output @ " + halfRate.toPlainString() + "%").setFont(normal).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(5)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
            itemsTable.addCell(cgstDescCell);

            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(halfRate.toPlainString() + " %", normal, TextAlignment.RIGHT));
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(formatAmount(line.cgst()), normal, TextAlignment.RIGHT));

            // SGST Row
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));

            Cell sgstDescCell = new Cell(1, 2)
                    .add(new Paragraph("TG-SGST Output @ " + halfRate.toPlainString() + "%").setFont(normal).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(5)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
            itemsTable.addCell(sgstDescCell);

            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(halfRate.toPlainString() + " %", normal, TextAlignment.RIGHT));
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(formatAmount(line.sgst()), normal, TextAlignment.RIGHT));
        }

        // Round Off row. Suppressed when there is nothing to show, so an invoice
        // that lands on a whole rupee by itself — and every invoice predating the
        // feature — does not print a meaningless "0.00" line.
        if (totals.roundOff().signum() != 0) {
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));

            Cell roundOffDescCell = new Cell(1, 2)
                    .add(new Paragraph("Round Off").setFont(normal).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(5)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
            itemsTable.addCell(roundOffDescCell);

            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell("", normal, TextAlignment.CENTER));
            itemsTable.addCell(createDataCell(formatSignedAmount(totals.roundOff()), normal, TextAlignment.RIGHT));
        }

        // Total Row - exact format. The STORED total, never a recomputed one.
        BigDecimal grandTotal = totals.finalTotal();
        BigDecimal totalQuantity = totals.totalQuantity();

        Cell totalLabelCell = new Cell(1, 3)
                .add(new Paragraph("Total").setFont(bold).setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        itemsTable.addCell(totalLabelCell);

        String unitType = totals.unitType();
        itemsTable.addCell(createDataCell(formatQuantity(totalQuantity), bold, TextAlignment.RIGHT));
        itemsTable.addCell(createDataCell("", bold, TextAlignment.CENTER));
        itemsTable.addCell(createDataCell(unitType, bold, TextAlignment.CENTER));
        itemsTable.addCell(createDataCell("₹ " + formatAmount(grandTotal), bold, TextAlignment.RIGHT));

        document.add(itemsTable);

        // Amount in words - with E & O.E
        Paragraph amountInWords = new Paragraph("Amount Chargeable (in words)")
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5);
        document.add(amountInWords);

        Table amountWordsTable = new Table(1).setWidth(UnitValue.createPercentValue(100));
        Cell amountWordsCell = new Cell()
                .add(new Paragraph(convertToWords(grandTotal)).setFont(bold).setFontSize(9).setItalic())
                .setTextAlignment(TextAlignment.LEFT)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        amountWordsTable.addCell(amountWordsCell);
        
        Cell eoeCell = new Cell()
                .add(new Paragraph("E & O.E").setFont(normal).setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT)
                .setPadding(3)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        amountWordsTable.addCell(eoeCell);
        
        document.add(amountWordsTable);
    }

    /**
     * EXACT TAX SUMMARY FROM SAMPLE
     */
    private void addTaxSummaryExact(Document document, InvoiceTotals.Totals totals,
                                   PdfFont bold, PdfFont normal) {

        // This section used to re-derive the subtotal and the tax from the line
        // items all over again — a second, independently drifting copy of the
        // items table's own arithmetic. It now shares one breakdown with it.
        //
        // Round-off is deliberately absent from this table: it is part of the
        // invoice total but not of taxable value or GST, so it must never reach a
        // tax return. See the footnote printed under the table.
        BigDecimal totalTax = totals.taxTotal();

        // Tax table - exact structure
        Table taxTable = new Table(new float[]{2f, 2f, 0.8f, 1.5f, 0.8f, 1.5f, 2f})
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(10);

        // Headers
        addTaxHeader(taxTable, "HSN/SAC", bold);
        addTaxHeader(taxTable, "Taxable\nValue", bold);
        
        // CGST header with Rate/Amount subheaders
        Cell cgstHeaderCell = new Cell(1, 2)
                .add(new Paragraph("CGST").setFont(bold).setFontSize(7))
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(3)
                .setBackgroundColor(new DeviceRgb(240, 240, 240))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        taxTable.addCell(cgstHeaderCell);
        
        // SGST header
        Cell sgstHeaderCell = new Cell(1, 2)
                .add(new Paragraph("SGST/UTGST").setFont(bold).setFontSize(7))
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(3)
                .setBackgroundColor(new DeviceRgb(240, 240, 240))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        taxTable.addCell(sgstHeaderCell);
        
        addTaxHeader(taxTable, "Total\nTax Amount", bold);

        // Subheaders for Rate and Amount
        taxTable.addCell(createBlankCell()); // HSN
        taxTable.addCell(createBlankCell()); // Taxable Value
        addTaxSubHeader(taxTable, "Rate", bold);
        addTaxSubHeader(taxTable, "Amount", bold);
        addTaxSubHeader(taxTable, "Rate", bold);
        addTaxSubHeader(taxTable, "Amount", bold);
        taxTable.addCell(createBlankCell()); // Total

        // One data row per GST rate on the invoice.
        BigDecimal sumCgst = BigDecimal.ZERO.setScale(2);
        BigDecimal sumSgst = BigDecimal.ZERO.setScale(2);
        for (InvoiceTotals.RateLine line : totals.byRate()) {
            BigDecimal halfRate = line.halfRatePercent();
            taxTable.addCell(createTaxCell(DEFAULT_HSN, normal, TextAlignment.CENTER));
            taxTable.addCell(createTaxCell(formatAmount(line.taxableValue()), normal, TextAlignment.RIGHT));
            taxTable.addCell(createTaxCell(halfRate.toPlainString() + "%", normal, TextAlignment.CENTER));
            taxTable.addCell(createTaxCell(formatAmount(line.cgst()), normal, TextAlignment.RIGHT));
            taxTable.addCell(createTaxCell(halfRate.toPlainString() + "%", normal, TextAlignment.CENTER));
            taxTable.addCell(createTaxCell(formatAmount(line.sgst()), normal, TextAlignment.RIGHT));
            taxTable.addCell(createTaxCell(formatAmount(line.tax()), normal, TextAlignment.RIGHT));

            sumCgst = sumCgst.add(line.cgst());
            sumSgst = sumSgst.add(line.sgst());
        }

        // Total row — genuine column sums.
        //
        // The label used to span the HSN and Taxable Value columns, which left the
        // taxable total with nowhere to print, and the CGST/SGST cells reprinted
        // the single rate's amounts rather than summing anything. With more than
        // one rate on the invoice that footer would not have added up.
        Cell totalLabelCell = new Cell()
                .add(new Paragraph("Total").setFont(bold).setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(3)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        taxTable.addCell(totalLabelCell);

        taxTable.addCell(createTaxCell(formatAmount(totals.taxableSubtotal()), bold, TextAlignment.RIGHT));
        taxTable.addCell(createTaxCell("", bold, TextAlignment.CENTER));
        taxTable.addCell(createTaxCell(formatAmount(sumCgst), bold, TextAlignment.RIGHT));
        taxTable.addCell(createTaxCell("", bold, TextAlignment.CENTER));
        taxTable.addCell(createTaxCell(formatAmount(sumSgst), bold, TextAlignment.RIGHT));
        taxTable.addCell(createTaxCell(formatAmount(totalTax), bold, TextAlignment.RIGHT));

        document.add(taxTable);

        // Why the numbers above do not add up to the invoice total.
        if (totals.roundOff().signum() != 0) {
            document.add(new Paragraph("Round Off of " + formatSignedAmount(totals.roundOff())
                            + " is included in the invoice total and excluded from taxable value and GST.")
                    .setFont(normal)
                    .setFontSize(7)
                    .setItalic()
                    .setMarginTop(2));
        }

        // Tax amount in words
        Paragraph taxInWords = new Paragraph("Tax Amount (in words): " + convertToWords(totalTax))
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5);
        document.add(taxInWords);
    }

    private void addFooter(Document document, InvoiceEntity invoice, PdfFont bold, PdfFont normal) {
        
        boolean isSesola = "SESOLA".equalsIgnoreCase(invoice.getCompany());
        String companyName = isSesola ? SESOLA_NAME : ISTL_NAME;
        String companyPan = isSesola ? SESOLA_PAN : ISTL_PAN;
        
        Paragraph pan = new Paragraph("Company's PAN: " + companyPan)
                .setFont(normal)
                .setFontSize(8)
                .setMarginTop(10);
        document.add(pan);

        Paragraph declaration = new Paragraph("Declaration")
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5);
        document.add(declaration);

        Paragraph declarationText = new Paragraph(
                "We declare that this invoice shows the actual price of the goods " +
                "described and that all particulars are true and correct.")
                .setFont(normal)
                .setFontSize(8);
        document.add(declarationText);

        // Signature table
        Table signatureTable = new Table(2).setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(20);

        Cell leftCell = new Cell()
                .add(new Paragraph("for " + companyName).setFont(normal).setFontSize(8))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.LEFT);
        signatureTable.addCell(leftCell);

        Cell rightCell = new Cell()
                .add(new Paragraph("Authorised Signatory").setFont(bold).setFontSize(9))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT);
        signatureTable.addCell(rightCell);

        document.add(signatureTable);

        Paragraph jurisdiction = new Paragraph("SUBJECT TO HYDERABAD JURISDICTION")
                .setFont(normal)
                .setFontSize(7)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(10);
        document.add(jurisdiction);

        Paragraph computerGenerated = new Paragraph("This is a Computer Generated Invoice")
                .setFont(normal)
                .setFontSize(7)
                .setTextAlignment(TextAlignment.CENTER)
                .setItalic();
        document.add(computerGenerated);
    }

    // Helper methods
    private void addTableHeader(Table table, String text, PdfFont font, TextAlignment alignment) {
        Cell cell = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(7))
                .setTextAlignment(alignment)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(5)
                .setBackgroundColor(new DeviceRgb(240, 240, 240))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addHeaderCell(cell);
    }

    private void addTaxHeader(Table table, String text, PdfFont font) {
        Cell cell = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(7))
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(3)
                .setBackgroundColor(new DeviceRgb(240, 240, 240))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addHeaderCell(cell);
    }

    private void addTaxSubHeader(Table table, String text, PdfFont font) {
        Cell cell = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(6))
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(2)
                .setBackgroundColor(new DeviceRgb(250, 250, 250))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addCell(cell);
    }

    private Cell createDataCell(String text, PdfFont font, TextAlignment alignment) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(8))
                .setTextAlignment(alignment)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
    }

    private Cell createTaxCell(String text, PdfFont font, TextAlignment alignment) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(7))
                .setTextAlignment(alignment)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(3)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
    }

    private Cell createBlankCell() {
        return new Cell()
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setBackgroundColor(new DeviceRgb(250, 250, 250))
                .setPadding(2);
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Round-off is signed, and a bare "-0.49" reads as a typo without the sign. */
    private String formatSignedAmount(BigDecimal amount) {
        if (amount == null || amount.signum() == 0) return "0.00";
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        return (scaled.signum() > 0 ? "+" : "-") + scaled.abs().toPlainString();
    }

    /**
     * Items, never null — an invoice with none must still render.
     *
     * java.util.List is spelled out because the unqualified List in this file is
     * iText's com.itextpdf.layout.element.List.
     */
    private java.util.List<InvoiceItemEntity> safeItems(InvoiceEntity invoice) {
        return invoice.getItems() != null ? invoice.getItems() : java.util.Collections.emptyList();
    }

    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) return "0";
        return quantity.stripTrailingZeros().toPlainString();
    }

    private String convertToWords(BigDecimal amount) {
        if (amount == null) return "Zero Rupees Only";

        // Normalise to the paisa before splitting. longValue() and
        // remainder().intValue() both truncate, so an input carrying more than two
        // decimals (or a negative) would lose or mis-state the paise.
        BigDecimal normalised = MoneyRounding.money(amount).abs();
        boolean negative = amount.signum() < 0;

        long rupees = normalised.longValue();
        int paise = normalised.remainder(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        String result = (negative ? "Minus " : "") + convertNumberToWords(rupees) + " Rupees";
        if (paise > 0) {
            result += " and " + convertNumberToWords(paise) + " Paise";
        }
        result += " Only";
        
        return result.toUpperCase();
    }

    private String convertNumberToWords(long number) {
        if (number == 0) return "Zero";
        
        String[] units = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"};
        String[] teens = {"Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", 
                         "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
        String[] tens = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};
        
        if (number < 10) return units[(int) number];
        if (number < 20) return teens[(int) number - 10];
        if (number < 100) return tens[(int) number / 10] + (number % 10 != 0 ? " " + units[(int) number % 10] : "");
        if (number < 1000) return units[(int) number / 100] + " Hundred" + (number % 100 != 0 ? " " + convertNumberToWords(number % 100) : "");
        if (number < 100000) return convertNumberToWords(number / 1000) + " Thousand" + (number % 1000 != 0 ? " " + convertNumberToWords(number % 1000) : "");
        if (number < 10000000) return convertNumberToWords(number / 100000) + " Lakh" + (number % 100000 != 0 ? " " + convertNumberToWords(number % 100000) : "");
        
        return convertNumberToWords(number / 10000000) + " Crore" + (number % 10000000 != 0 ? " " + convertNumberToWords(number % 10000000) : "");
    }
}