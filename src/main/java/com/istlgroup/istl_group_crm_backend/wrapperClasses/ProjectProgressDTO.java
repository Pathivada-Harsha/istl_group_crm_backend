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
public class ProjectProgressDTO {
    private BigDecimal timeBasedProgress;
    private BigDecimal budgetBasedProgress;
    private Long daysElapsed;
    private Long daysRemaining;
    private Long totalDays;
    private Boolean isOverdue;
    private Boolean isOverBudget;
    private Boolean requiresAttention;
}
