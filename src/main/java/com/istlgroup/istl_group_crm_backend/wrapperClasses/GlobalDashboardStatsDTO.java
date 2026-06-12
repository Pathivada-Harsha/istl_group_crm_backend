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
public class GlobalDashboardStatsDTO {
    private Integer totalProjects;
    private Integer activeProjects;
    private Integer completedProjects;
    private BigDecimal totalBudget;
    private BigDecimal totalUtilized;
    private BigDecimal totalPendingPayments;
    private Integer totalActiveVendors;
    private Integer projectsBehindSchedule;
    private Integer projectsOverBudget;
    private List<ProjectStatsSummaryDTO> topProjects;
}
