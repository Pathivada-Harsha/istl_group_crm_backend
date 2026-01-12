package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;

import lombok.Data;

@Data
class QuotationItemDTO {
    private Long id;
    private String itemName;
    private String itemDescription;
    private String itemCategory;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal gst;
    private BigDecimal discount;
    private BigDecimal lineTotal;
}
