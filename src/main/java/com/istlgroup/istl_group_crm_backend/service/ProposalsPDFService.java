package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.ProposalsEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProposalsRepo;
import com.istlgroup.istl_group_crm_backend.repo.LeadsRepo;
import com.istlgroup.istl_group_crm_backend.repo.CustomersRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.layout.borders.SolidBorder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ProposalsPDFService {
    
    @Autowired
    private ProposalsRepo proposalsRepo;
    
    @Autowired
    private LeadsRepo leadsRepo;
    
    @Autowired
    private CustomersRepo customersRepo;
    
    @Autowired
    private UsersRepo usersRepo;
    
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public byte[] generateProposalPDF(Long proposalId, Long userId, String userRole) throws CustomException {
        ProposalsEntity proposal = proposalsRepo.findById(proposalId)
            .orElseThrow(() -> new CustomException("Proposal not found"));
        
        if (proposal.getDeletedAt() != null) {
            throw new CustomException("Proposal has been deleted");
        }
        
        if (!isAccessible(proposal, userId, userRole)) {
            throw new CustomException("You don't have permission to download this proposal");
        }
        
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document document = new Document(pdfDoc);
            document.setMargins(36, 36, 36, 36);
            
            PdfFont boldFont = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD);
            PdfFont normalFont = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            
            // PAGE 1: Title Page
            addTitlePage(document, proposal, boldFont, normalFont);
            document.add(new AreaBreak());
            
            // PAGE 2: About Us
            if (proposal.getAboutUs() != null && !proposal.getAboutUs().trim().isEmpty()) {
                addSectionWithTitle(document, "ABOUT US", proposal.getAboutUs(), boldFont, normalFont);
                document.add(new AreaBreak());
            }
            
            // PAGE 3: About System
            if (proposal.getAboutSystem() != null && !proposal.getAboutSystem().trim().isEmpty()) {
                addSectionWithTitle(document, "ABOUT THE SYSTEM", proposal.getAboutSystem(), boldFont, normalFont);
                document.add(new AreaBreak());
            }
            
            // PAGE 4: System Pricing
            if (proposal.getSystemPricing() != null && !proposal.getSystemPricing().trim().isEmpty()) {
                try {
                    addSystemPricing(document, proposal.getSystemPricing(), boldFont, normalFont);
                    document.add(new AreaBreak());
                } catch (Exception e) {
                    System.err.println("Error adding system pricing: " + e.getMessage());
                }
            }
            
            // PAGE 5: Payment Terms
            if (proposal.getPaymentTerms() != null && !proposal.getPaymentTerms().trim().isEmpty()) {
                addSectionWithTitle(document, "PAYMENT TERMS", proposal.getPaymentTerms(), boldFont, normalFont);
                document.add(new AreaBreak());
            }
            
            // PAGE 6: Defect Liability Period
            if (proposal.getDefectLiabilityPeriod() != null && !proposal.getDefectLiabilityPeriod().trim().isEmpty()) {
                addSectionWithTitle(document, "DEFECT LIABILITY PERIOD", proposal.getDefectLiabilityPeriod(), boldFont, normalFont);
                document.add(new AreaBreak());
            }
            
            // PAGE 7: Bill of Materials
            if (proposal.getBomItems() != null && !proposal.getBomItems().trim().isEmpty()) {
                try {
                    addBillOfMaterials(document, proposal.getBomItems(), boldFont, normalFont);
                } catch (Exception e) {
                    System.err.println("Error adding BOM: " + e.getMessage());
                }
            }
            
            document.close();
            return baos.toByteArray();
            
        } catch (Exception e) {
            throw new CustomException("Error generating PDF: " + e.getMessage());
        }
    }
    
    private void addTitlePage(Document document, ProposalsEntity proposal, PdfFont boldFont, PdfFont normalFont) throws Exception {
        String companyName = proposal.getCompanyName() != null ? proposal.getCompanyName() : "SESOLA POWER PROJECTS PROPOSAL PVT LTD";
        Paragraph company = new Paragraph(companyName)
            .setFont(boldFont)
            .setFontSize(20)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(new DeviceRgb(49, 130, 206))
            .setMarginBottom(20);
        document.add(company);
        
        Paragraph title = new Paragraph("BUSINESS PROPOSAL")
            .setFont(boldFont)
            .setFontSize(18)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(30);
        document.add(title);
        
        Table infoTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        infoTable.setBorder(new SolidBorder(ColorConstants.LIGHT_GRAY, 1));
        
        addInfoRow(infoTable, "Proposal No:", proposal.getProposalNo(), boldFont, normalFont);
        addInfoRow(infoTable, "Date:", proposal.getCreatedAt().format(dateFormatter), boldFont, normalFont);
        addInfoRow(infoTable, "Version:", "v" + proposal.getVersion(), boldFont, normalFont);
        addInfoRow(infoTable, "Status:", proposal.getStatus(), boldFont, normalFont);
        if (proposal.getGroupName() != null) {
            addInfoRow(infoTable, "Group:", proposal.getGroupName(), boldFont, normalFont);
        }
        if (proposal.getSubGroupName() != null) {
            addInfoRow(infoTable, "Category:", proposal.getSubGroupName(), boldFont, normalFont);
        }
        
        document.add(infoTable);
        document.add(new Paragraph("\n"));
        
        Paragraph toLabel = new Paragraph("TO:")
            .setFont(boldFont)
            .setFontSize(14)
            .setMarginBottom(10);
        document.add(toLabel);
        
        addClientInformation(document, proposal, boldFont, normalFont);
        
        if (proposal.getPreparedBy() != null) {
            usersRepo.findById(proposal.getPreparedBy()).ifPresent(user -> {
                Paragraph preparedBy = new Paragraph("Prepared By: " + user.getName())
                    .setFont(normalFont)
                    .setFontSize(11)
                    .setMarginTop(20);
                document.add(preparedBy);
            });
        }
        
        document.add(new Paragraph("\n"));
        Paragraph subject = new Paragraph("SUBJECT: " + proposal.getTitle())
            .setFont(boldFont)
            .setFontSize(14)
            .setMarginTop(10)
            .setMarginBottom(10);
        document.add(subject);
        
        if (proposal.getDescription() != null && !proposal.getDescription().isEmpty()) {
            Paragraph description = new Paragraph(proposal.getDescription())
                .setFont(normalFont)
                .setFontSize(11)
                .setMarginBottom(20);
            document.add(description);
        }
        
        if (proposal.getTotalValue() != null && proposal.getTotalValue().compareTo(BigDecimal.ZERO) > 0) {
            Paragraph totalValue = new Paragraph("Total Project Value: ₹" + String.format("%,.2f", proposal.getTotalValue()))
                .setFont(boldFont)
                .setFontSize(13)
                .setFontColor(new DeviceRgb(34, 139, 34))
                .setMarginTop(20);
            document.add(totalValue);
        }
    }
    
    private void addInfoRow(Table table, String label, String value, PdfFont boldFont, PdfFont normalFont) {
        table.addCell(new Cell().add(new Paragraph(label).setFont(boldFont).setFontSize(10))
            .setBorder(new SolidBorder(ColorConstants.LIGHT_GRAY, 0.5f))
            .setBackgroundColor(new DeviceRgb(247, 250, 252))
            .setPadding(8));
        table.addCell(new Cell().add(new Paragraph(value).setFont(normalFont).setFontSize(10))
            .setBorder(new SolidBorder(ColorConstants.LIGHT_GRAY, 0.5f))
            .setPadding(8));
    }
    
    private void addSectionWithTitle(Document document, String title, String content, PdfFont boldFont, PdfFont normalFont) {
        Paragraph sectionTitle = new Paragraph(title)
            .setFont(boldFont)
            .setFontSize(16)
            .setFontColor(new DeviceRgb(49, 130, 206))
            .setMarginBottom(15);
        document.add(sectionTitle);
        
        Paragraph sectionContent = new Paragraph(content)
            .setFont(normalFont)
            .setFontSize(11)
            .setTextAlignment(TextAlignment.JUSTIFIED);
        document.add(sectionContent);
    }
    
    private void addSystemPricing(Document document, String pricingJson, PdfFont boldFont, PdfFont normalFont) throws Exception {
        Paragraph title = new Paragraph("SYSTEM PRICING")
            .setFont(boldFont)
            .setFontSize(16)
            .setFontColor(new DeviceRgb(49, 130, 206))
            .setMarginBottom(15);
        document.add(title);
        
        List<Map<String, String>> pricingItems = objectMapper.readValue(pricingJson, new TypeReference<List<Map<String, String>>>() {});
        
        Table table = new Table(3).setWidth(UnitValue.createPercentValue(100));
        
        table.addHeaderCell(new Cell().add(new Paragraph("Item").setFont(boldFont).setFontSize(11))
            .setBackgroundColor(new DeviceRgb(49, 130, 206))
            .setFontColor(ColorConstants.WHITE)
            .setPadding(10));
        table.addHeaderCell(new Cell().add(new Paragraph("Description").setFont(boldFont).setFontSize(11))
            .setBackgroundColor(new DeviceRgb(49, 130, 206))
            .setFontColor(ColorConstants.WHITE)
            .setPadding(10));
        table.addHeaderCell(new Cell().add(new Paragraph("Amount (₹)").setFont(boldFont).setFontSize(11))
            .setBackgroundColor(new DeviceRgb(49, 130, 206))
            .setFontColor(ColorConstants.WHITE)
            .setPadding(10));
        
        double total = 0;
        for (Map<String, String> item : pricingItems) {
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("item", "")).setFont(normalFont).setFontSize(10)).setPadding(8));
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("description", "")).setFont(normalFont).setFontSize(10)).setPadding(8));
            double amount = Double.parseDouble(item.getOrDefault("amount", "0"));
            total += amount;
            table.addCell(new Cell().add(new Paragraph(String.format("%,.2f", amount)).setFont(normalFont).setFontSize(10)).setPadding(8));
        }
        
        table.addCell(new Cell(1, 2).add(new Paragraph("Total").setFont(boldFont).setFontSize(11))
            .setBackgroundColor(new DeviceRgb(247, 250, 252))
            .setTextAlignment(TextAlignment.RIGHT)
            .setPadding(10));
        table.addCell(new Cell().add(new Paragraph(String.format("₹%,.2f", total)).setFont(boldFont).setFontSize(11))
            .setBackgroundColor(new DeviceRgb(247, 250, 252))
            .setPadding(10));
        
        document.add(table);
    }
    
    private void addBillOfMaterials(Document document, String bomJson, PdfFont boldFont, PdfFont normalFont) throws Exception {
        Paragraph title = new Paragraph("BILL OF MATERIALS (BOM)")
            .setFont(boldFont)
            .setFontSize(16)
            .setFontColor(new DeviceRgb(49, 130, 206))
            .setMarginBottom(15);
        document.add(title);
        
        List<Map<String, String>> bomItems = objectMapper.readValue(bomJson, new TypeReference<List<Map<String, String>>>() {});
        
        Table table = new Table(6).setWidth(UnitValue.createPercentValue(100));
        
        String[] headers = {"Item", "Specification", "Quantity", "Unit", "Rate (₹)", "Amount (₹)"};
        for (String header : headers) {
            table.addHeaderCell(new Cell().add(new Paragraph(header).setFont(boldFont).setFontSize(10))
                .setBackgroundColor(new DeviceRgb(49, 130, 206))
                .setFontColor(ColorConstants.WHITE)
                .setPadding(8));
        }
        
        double total = 0;
        for (Map<String, String> item : bomItems) {
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("item", "")).setFont(normalFont).setFontSize(9)).setPadding(6));
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("specification", "")).setFont(normalFont).setFontSize(9)).setPadding(6));
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("quantity", "")).setFont(normalFont).setFontSize(9)).setPadding(6));
            table.addCell(new Cell().add(new Paragraph(item.getOrDefault("unit", "")).setFont(normalFont).setFontSize(9)).setPadding(6));
            double rate = Double.parseDouble(item.getOrDefault("rate", "0"));
            table.addCell(new Cell().add(new Paragraph(String.format("%,.2f", rate)).setFont(normalFont).setFontSize(9)).setPadding(6));
            double amount = Double.parseDouble(item.getOrDefault("amount", "0"));
            total += amount;
            table.addCell(new Cell().add(new Paragraph(String.format("%,.2f", amount)).setFont(normalFont).setFontSize(9)).setPadding(6));
        }
        
        table.addCell(new Cell(1, 5).add(new Paragraph("Total").setFont(boldFont).setFontSize(10))
            .setBackgroundColor(new DeviceRgb(247, 250, 252))
            .setTextAlignment(TextAlignment.RIGHT)
            .setPadding(8));
        table.addCell(new Cell().add(new Paragraph(String.format("₹%,.2f", total)).setFont(boldFont).setFontSize(10))
            .setBackgroundColor(new DeviceRgb(247, 250, 252))
            .setPadding(8));
        
        document.add(table);
    }
    
    private void addClientInformation(Document document, ProposalsEntity proposal, PdfFont boldFont, PdfFont normalFont) {
        Table clientTable = new Table(2).setWidth(UnitValue.createPercentValue(100));
        clientTable.setBorder(new SolidBorder(ColorConstants.LIGHT_GRAY, 1));
        
        if (proposal.getLeadId() != null) {
            leadsRepo.findById(proposal.getLeadId()).ifPresent(lead -> {
                addInfoRow(clientTable, "Lead:", lead.getName(), boldFont, normalFont);
                addInfoRow(clientTable, "Lead Code:", lead.getLeadCode(), boldFont, normalFont);
                if (lead.getEmail() != null) {
                    addInfoRow(clientTable, "Email:", lead.getEmail(), boldFont, normalFont);
                }
                if (lead.getPhone() != null) {
                    addInfoRow(clientTable, "Phone:", lead.getPhone(), boldFont, normalFont);
                }
            });
        }
        
        if (proposal.getCustomerId() != null) {
            customersRepo.findById(proposal.getCustomerId()).ifPresent(customer -> {
                addInfoRow(clientTable, "Customer:", customer.getName(), boldFont, normalFont);
                addInfoRow(clientTable, "Customer Code:", customer.getCustomerCode(), boldFont, normalFont);
                if (customer.getCompanyName() != null) {
                    addInfoRow(clientTable, "Company:", customer.getCompanyName(), boldFont, normalFont);
                }
                if (customer.getEmail() != null) {
                    addInfoRow(clientTable, "Email:", customer.getEmail(), boldFont, normalFont);
                }
                if (customer.getPhone() != null) {
                    addInfoRow(clientTable, "Phone:", customer.getPhone(), boldFont, normalFont);
                }
                if (customer.getAddress() != null) {
                    String fullAddress = customer.getAddress();
                    if (customer.getCity() != null) fullAddress += ", " + customer.getCity();
                    if (customer.getState() != null) fullAddress += ", " + customer.getState();
                    if (customer.getPincode() != null) fullAddress += " - " + customer.getPincode();
                    addInfoRow(clientTable, "Address:", fullAddress, boldFont, normalFont);
                }
            });
        }
        
        document.add(clientTable);
    }
    
    private boolean isAccessible(ProposalsEntity proposal, Long userId, String userRole) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return true;
        }
        return proposal.getPreparedBy().equals(userId);
    }
}