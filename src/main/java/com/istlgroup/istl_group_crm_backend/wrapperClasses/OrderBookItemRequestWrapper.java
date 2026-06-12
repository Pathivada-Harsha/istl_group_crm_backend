package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class OrderBookItemRequestWrapper {
    private Long id;
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
}