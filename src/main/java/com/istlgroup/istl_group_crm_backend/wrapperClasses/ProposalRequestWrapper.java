package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ProposalRequestWrapper {
    private Long leadId;
    private Long customerId;
    private String title;
    private String description;
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
    
    // For filtering
    private String searchTerm;
    private String filterStatus;
    private String filterGroup;
    private String filterSubGroup;
    private Long filterPreparedBy;
    private String fromDate;
    private String toDate;
    
    // Pagination
    private Integer page;
    private Integer size;
}