package com.istlgroup.istl_group_crm_backend.wrapperClasses;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillItemDTO {
    private Long id;
    private Long poItemId;
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal taxPercent;
    private BigDecimal lineTotal;
}
