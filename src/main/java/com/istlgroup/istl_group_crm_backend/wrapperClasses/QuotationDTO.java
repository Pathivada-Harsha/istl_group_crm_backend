package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for Quotation responses - avoids circular reference issues
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationDTO {
    
    private Long id;
    private String quoteNo;
    private Long leadId;
    private Long proposalId;
    private Long customerId;
    private Long vendorId;
    private String rfqId;
    private String type;
    private String status;
    private LocalDate validTill;
    private BigDecimal totalValue;
    private Long preparedBy;
    private String groupName;
    private String subGroupName;
    private String projectId;
    private String vendorContact;
    private Double vendorRating;
    private String deliveryTime;
    private String paymentTerms;
    private String warranty;
    private String notes;
    private String category;
    private LocalDateTime uploadedAt;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private Long poId;
    
    // Items as simple DTOs (no circular reference)
    private List<QuotationItemDTO> items;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QuotationItemDTO {
        private Long id;
        private Integer lineNo;
        private String itemName;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal taxPercent;
        private BigDecimal lineTotal;
        private String deliveryLeadTime;
    }
}