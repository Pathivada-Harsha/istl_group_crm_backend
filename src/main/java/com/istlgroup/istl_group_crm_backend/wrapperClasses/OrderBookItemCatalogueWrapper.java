package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemCatalogueEntity;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderBookItemCatalogueWrapper {

    private Long id;
    private String itemName;
    private String specification;
    private String description;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal taxPercent;
    private BigDecimal discountPercent;

    public static OrderBookItemCatalogueWrapper from(OrderBookItemCatalogueEntity e) {
        OrderBookItemCatalogueWrapper w = new OrderBookItemCatalogueWrapper();
        w.setId(e.getId());
        w.setItemName(e.getItemName());
        w.setSpecification(e.getSpecification());
        w.setDescription(e.getDescription());
        w.setUnit(e.getUnit());
        w.setUnitPrice(e.getUnitPrice());
        w.setTaxPercent(e.getTaxPercent());
        w.setDiscountPercent(e.getDiscountPercent());
        return w;
    }
}