package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class OrderBookRequestWrapper {
    private Long customerId;
    private Long proposalId;
    private Long leadId;
    private String groupName;
    private String subGroupName;
    private String orderTitle;
    private String orderDescription;
    private String orderDate;
    private String expectedDeliveryDate;
    private String poNumber;
    private String poDate;
    private BigDecimal advanceAmount;
    private String status;
    private String remarks;
    
    // Items
    private List<OrderBookItemRequestWrapper> items;
}