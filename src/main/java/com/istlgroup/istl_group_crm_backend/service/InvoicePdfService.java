package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.CustomersEntity;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
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
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private final CustomersService customersService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    
    // Company details constants
    private static final String COMPANY_NAME = "ISCIENTIFIC TECHSOLUTIONS LABS PVT LTD";
    private static final String COMPANY_ADDRESS = "133/1/B, 1st Floor, Phase II, IDA Cherlapally";
    private static final String COMPANY_CITY = "Hyderabad, Telangana - 500051";
    private static final String COMPANY_GSTIN = "36AAGCI8913D1ZL";
    private static final String COMPANY_PAN = "AAGCI8913D";
    private static final String COMPANY_EMAIL = "accounts@istlabs.in";
    private static final String COMPANY_UDYAM = "UDYAM(MSME)- TS-20-0045223";
    private static final String COMPANY_CIN = "U31900KA2022PTC167257";
    private static final String STATE_CODE = "36";
    private static final String STATE_NAME = "Telangana";

    public byte[] generateInvoicePdf(InvoiceEntity invoice) throws CustomException {
        try {
            log.info("Generating GST compliant PDF for invoice: {}", invoice.getInvoiceNo());
            
            // Validate invoice data
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
            
            // Add company and customer details
            addCompanyAndCustomerInfo(document, invoice, customer, bold, normal);
            
            // Add invoice metadata
            addInvoiceMetadata(document, invoice, bold, normal);
            
            // Add items table
            addItemsTable(document, invoice, bold, normal);
            
            // Add tax summary
            addTaxSummary(document, invoice, bold, normal);
            
            // Add footer
            addFooter(document, bold, normal);

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
        
        Table mainTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        mainTable.setBorder(new SolidBorder(ColorConstants.BLACK, 1));

        // Left side - Company Details
        Cell companyCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 1))
                .setPadding(10);

        companyCell.add(new Paragraph(COMPANY_NAME)
                .setFont(bold)
                .setFontSize(11));
        companyCell.add(new Paragraph(COMPANY_ADDRESS)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph(COMPANY_CITY)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph(COMPANY_UDYAM)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph("GSTIN/UIN: " + COMPANY_GSTIN)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph("State Name: " + STATE_NAME + ", Code: " + STATE_CODE)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph("CIN: " + COMPANY_CIN)
                .setFont(normal)
                .setFontSize(8));
        companyCell.add(new Paragraph("E-Mail: " + COMPANY_EMAIL)
                .setFont(normal)
                .setFontSize(8));

        mainTable.addCell(companyCell);

        // Right side - Invoice Details
        Cell invoiceDetailsCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(10);

        invoiceDetailsCell.add(new Paragraph("Invoice No.")
                .setFont(bold)
                .setFontSize(9));
        invoiceDetailsCell.add(new Paragraph(invoice.getInvoiceNo())
                .setFont(normal)
                .setFontSize(9));
        invoiceDetailsCell.add(new Paragraph("Dated")
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5));
        invoiceDetailsCell.add(new Paragraph(invoice.getInvoiceDate() != null ? 
                invoice.getInvoiceDate().format(DATE_FORMAT) : "")
                .setFont(normal)
                .setFontSize(9));
        invoiceDetailsCell.add(new Paragraph("Mode/Terms of Payment")
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5));
        invoiceDetailsCell.add(new Paragraph("As per terms")
                .setFont(normal)
                .setFontSize(9));

        mainTable.addCell(invoiceDetailsCell);

        document.add(mainTable);

        // Customer Details Section
        Table customerTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        customerTable.setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        customerTable.setMarginTop(0);

        // Bill To
        Cell billToCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 1))
                .setPadding(10);

        billToCell.add(new Paragraph("Buyer (Bill to)")
                .setFont(bold)
                .setFontSize(9));
        billToCell.add(new Paragraph(customer.getCompanyName() != null ? customer.getCompanyName() : customer.getName())
                .setFont(bold)
                .setFontSize(9));
        billToCell.add(new Paragraph(customer.getAddress() != null ? customer.getAddress() : "")
                .setFont(normal)
                .setFontSize(8));
        billToCell.add(new Paragraph((customer.getCity() != null ? customer.getCity() : "") + 
                ", " + (customer.getState() != null ? customer.getState() : "") + 
                " - " + (customer.getPincode() != null ? customer.getPincode() : ""))
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

        // Ship To (same as Bill To for now)
        Cell shipToCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(10);

        shipToCell.add(new Paragraph("Consignee (Ship to)")
                .setFont(bold)
                .setFontSize(9));
        shipToCell.add(new Paragraph(customer.getCompanyName() != null ? customer.getCompanyName() : customer.getName())
                .setFont(bold)
                .setFontSize(9));
        shipToCell.add(new Paragraph(customer.getAddress() != null ? customer.getAddress() : "")
                .setFont(normal)
                .setFontSize(8));
        shipToCell.add(new Paragraph((customer.getCity() != null ? customer.getCity() : "") + 
                ", " + (customer.getState() != null ? customer.getState() : "") + 
                " - " + (customer.getPincode() != null ? customer.getPincode() : ""))
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

    private void addInvoiceMetadata(Document document, InvoiceEntity invoice, 
                                    PdfFont bold, PdfFont normal) {
        // This section can be expanded with more metadata if needed
    }

    private void addItemsTable(Document document, InvoiceEntity invoice, 
                               PdfFont bold, PdfFont normal) {
        
        Table itemsTable = new Table(new float[]{1, 5, 2, 2, 2, 2, 2})
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(0);

        // Header row
        String[] headers = {"Sl\nNo.", "Description of Goods", "HSN/SAC", "Quantity", 
                           "Rate\nper", "Amount"};
        
        for (String header : headers) {
            Cell headerCell = new Cell()
                    .add(new Paragraph(header).setFont(bold).setFontSize(8))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(5)
                    .setBackgroundColor(new DeviceRgb(240, 240, 240))
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
            itemsTable.addHeaderCell(headerCell);
        }

        int slNo = 1;
        BigDecimal subtotal = BigDecimal.ZERO;

        for (InvoiceItemEntity item : invoice.getItems()) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal lineAmount = quantity.multiply(unitPrice);
            subtotal = subtotal.add(lineAmount);

            // Sl No
            itemsTable.addCell(createCell(String.valueOf(slNo++), normal, TextAlignment.CENTER));
            
            // Description
            itemsTable.addCell(createCell(item.getDescription() != null ? item.getDescription() : "", 
                    normal, TextAlignment.LEFT));
            
            // HSN/SAC
            itemsTable.addCell(createCell("90283090", normal, TextAlignment.CENTER));
            
            // Quantity
            itemsTable.addCell(createCell(quantity.toPlainString() + " " + 
                    (item.getUnitType() != null ? item.getUnitType() : "Nos"), 
                    normal, TextAlignment.CENTER));
            
            // Rate
            itemsTable.addCell(createCell(formatAmount(unitPrice) + " " + 
                    (item.getUnitType() != null ? item.getUnitType() : ""), 
                    normal, TextAlignment.RIGHT));
            
            // Amount
            itemsTable.addCell(createCell(formatAmount(lineAmount), normal, TextAlignment.RIGHT));
        }

        // Add tax rows if GST applicable
        BigDecimal taxPercent = invoice.getItems().get(0).getTaxPercent() != null ? 
                invoice.getItems().get(0).getTaxPercent() : BigDecimal.valueOf(18);
        
        BigDecimal cgstRate = taxPercent.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal sgstRate = taxPercent.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        
        BigDecimal cgstAmount = subtotal.multiply(cgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal sgstAmount = subtotal.multiply(sgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // CGST Row
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell("TG-CGST Output @ " + cgstRate.toPlainString() + "%", 
                normal, TextAlignment.RIGHT));
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell(cgstRate.toPlainString() + " %", normal, TextAlignment.RIGHT));
        itemsTable.addCell(createCell(formatAmount(cgstAmount), normal, TextAlignment.RIGHT));

        // SGST Row
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell("TG-SGST Output @ " + sgstRate.toPlainString() + "%", 
                normal, TextAlignment.RIGHT));
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell("", normal, TextAlignment.CENTER));
        itemsTable.addCell(createCell(sgstRate.toPlainString() + " %", normal, TextAlignment.RIGHT));
        itemsTable.addCell(createCell(formatAmount(sgstAmount), normal, TextAlignment.RIGHT));

        // Total Row
        BigDecimal grandTotal = subtotal.add(cgstAmount).add(sgstAmount);
        
        Cell totalLabelCell = new Cell(1, 3)
                .add(new Paragraph("Total").setFont(bold).setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        itemsTable.addCell(totalLabelCell);

        String totalQty = invoice.getItems().stream()
                .map(InvoiceItemEntity::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString();
        
        itemsTable.addCell(createCell(totalQty + " " + 
                (invoice.getItems().get(0).getUnitType() != null ? invoice.getItems().get(0).getUnitType() : "Nos"), 
                bold, TextAlignment.CENTER));
        
        itemsTable.addCell(createCell("", bold, TextAlignment.RIGHT));
        
        Cell grandTotalCell = new Cell()
                .add(new Paragraph("₹ " + formatAmount(grandTotal)).setFont(bold).setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        itemsTable.addCell(grandTotalCell);

        document.add(itemsTable);

        // Amount in words
        Paragraph amountInWords = new Paragraph("Amount Chargeable (in words)")
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5);
        document.add(amountInWords);

        Paragraph amountWords = new Paragraph(convertToWords(grandTotal))
                .setFont(bold)
                .setFontSize(9)
                .setItalic();
        document.add(amountWords);
    }

    private void addTaxSummary(Document document, InvoiceEntity invoice, 
                               PdfFont bold, PdfFont normal) {
        
        BigDecimal subtotal = BigDecimal.ZERO;
        for (InvoiceItemEntity item : invoice.getItems()) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            subtotal = subtotal.add(quantity.multiply(unitPrice));
        }

        BigDecimal taxPercent = invoice.getItems().get(0).getTaxPercent() != null ? 
                invoice.getItems().get(0).getTaxPercent() : BigDecimal.valueOf(18);
        
        BigDecimal cgstRate = taxPercent.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        BigDecimal sgstRate = taxPercent.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        
        BigDecimal cgstAmount = subtotal.multiply(cgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal sgstAmount = subtotal.multiply(sgstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal totalTax = cgstAmount.add(sgstAmount);

        Table taxTable = new Table(new float[]{2, 2, 1, 2, 1, 2, 2})
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(10);

        // Headers
        String[] headers = {"HSN/SAC", "Taxable\nValue", "CGST", "", "SGST/UTGST", "", "Total\nTax Amount"};
        String[] subHeaders = {"", "", "Rate", "Amount", "Rate", "Amount", ""};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = new Cell()
                    .add(new Paragraph(headers[i]).setFont(bold).setFontSize(7))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(3)
                    .setBackgroundColor(new DeviceRgb(240, 240, 240))
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
            taxTable.addHeaderCell(cell);
        }

        // Data row
        taxTable.addCell(createCell("90283090", normal, TextAlignment.CENTER));
        taxTable.addCell(createCell(formatAmount(subtotal), normal, TextAlignment.RIGHT));
        taxTable.addCell(createCell(cgstRate.toPlainString() + "%", normal, TextAlignment.CENTER));
        taxTable.addCell(createCell(formatAmount(cgstAmount), normal, TextAlignment.RIGHT));
        taxTable.addCell(createCell(sgstRate.toPlainString() + "%", normal, TextAlignment.CENTER));
        taxTable.addCell(createCell(formatAmount(sgstAmount), normal, TextAlignment.RIGHT));
        taxTable.addCell(createCell(formatAmount(totalTax), normal, TextAlignment.RIGHT));

        // Total row
        Cell totalCell = new Cell(1, 2)
                .add(new Paragraph("Total").setFont(bold).setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT)
                .setPadding(3)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
        taxTable.addCell(totalCell);
        
        taxTable.addCell(createCell("", bold, TextAlignment.CENTER));
        taxTable.addCell(createCell(formatAmount(cgstAmount), bold, TextAlignment.RIGHT));
        taxTable.addCell(createCell("", bold, TextAlignment.CENTER));
        taxTable.addCell(createCell(formatAmount(sgstAmount), bold, TextAlignment.RIGHT));
        taxTable.addCell(createCell(formatAmount(totalTax), bold, TextAlignment.RIGHT));

        document.add(taxTable);

        // Tax amount in words
        Paragraph taxInWords = new Paragraph("Tax Amount (in words): " + convertToWords(totalTax))
                .setFont(bold)
                .setFontSize(9)
                .setMarginTop(5);
        document.add(taxInWords);
    }

    private void addFooter(Document document, PdfFont bold, PdfFont normal) {
        
        Paragraph companyPan = new Paragraph("Company's PAN: " + COMPANY_PAN)
                .setFont(normal)
                .setFontSize(8)
                .setMarginTop(10);
        document.add(companyPan);

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

        // Company name and signature
        Table signatureTable = new Table(2).setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(20);

        Cell leftCell = new Cell()
                .add(new Paragraph("for " + COMPANY_NAME).setFont(normal).setFontSize(8))
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
    private Cell createCell(String text, PdfFont font, TextAlignment alignment) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(8))
                .setTextAlignment(alignment)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(5)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1));
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String convertToWords(BigDecimal amount) {
        if (amount == null) return "Zero Rupees Only";
        
        long rupees = amount.longValue();
        int paise = amount.remainder(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .intValue();
        
        String result = convertNumberToWords(rupees) + " Rupees";
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
        if (number < 100) return tens[(int) number / 10] + " " + units[(int) number % 10];
        if (number < 1000) return units[(int) number / 100] + " Hundred " + convertNumberToWords(number % 100);
        if (number < 100000) return convertNumberToWords(number / 1000) + " Thousand " + convertNumberToWords(number % 1000);
        if (number < 10000000) return convertNumberToWords(number / 100000) + " Lakh " + convertNumberToWords(number % 100000);
        
        return convertNumberToWords(number / 10000000) + " Crore " + convertNumberToWords(number % 10000000);
    }
}