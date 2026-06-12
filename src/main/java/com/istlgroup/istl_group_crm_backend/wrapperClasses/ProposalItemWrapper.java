package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProposalItemWrapper {
    private Long id;
    private Long proposalId;
    private Integer lineNo;
    private String itemName;
    private String specification;
    private String description;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal taxPercent;
    private BigDecimal lineTotal;
    private String createdAt;
}