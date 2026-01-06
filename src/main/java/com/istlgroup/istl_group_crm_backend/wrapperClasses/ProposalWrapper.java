package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ProposalWrapper {
    private Long id;
    private String proposalNo;
    private Long leadId;
    private String leadCode;
    private String leadName;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private String title;
    private String description;
    private Long preparedBy;
    private String preparedByName;
    private Integer version;
    private String status;
    private BigDecimal totalValue;
    private String groupName;
    private String subGroupName;
    
    // Template fields
    private String companyName;
    private String aboutUs;
    private String aboutSystem;
    private String systemPricing;
    private String paymentTerms;
    private String defectLiabilityPeriod;
    private String bomItems;
    
    private String createdAt;
    private String updatedAt;
}