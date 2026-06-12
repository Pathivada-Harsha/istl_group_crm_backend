package com.istlgroup.istl_group_crm_backend.wrapperClasses;


import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectStatsSummaryDTO {
    private String projectId;
    private String projectName;
    private String status;
    private BigDecimal budget;
    private BigDecimal utilized;
    private BigDecimal utilizationPercent;
    private Integer totalPOs;
    private BigDecimal totalPOValue;
    private Integer deliveredPOs;
    private BigDecimal pendingPayments;
    private Boolean requiresAttention;
}
