package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class OrderBookItemWrapper {
    private Long id;
    private Long orderBookId;
    private Integer lineNo;
    private String itemName;
    private String specification;
    private String description;
    private Long proposalItemId;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal taxPercent;
    private BigDecimal discountPercent;
    private String itemRemarks;
    
    // Calculated fields
    private BigDecimal lineSubtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxableAmount;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;
    private BigDecimal allocatedQty;  // total qty already assigned to POs for this item
    private BigDecimal invoicedQty;   // total qty already invoiced against this order book item
    
    private String createdAt;
    private String updatedAt;
}