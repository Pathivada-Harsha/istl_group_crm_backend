package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Block that gets embedded inside ProjectDashboardDTO.
 * Returned by ProjectExpenseService.getExpenseDashboardBlock()
 * and merged into getDashboardData() in ProjectDashboardService.
 */
@Data
@Builder
public class ExpenseDashboardBlock {
    private BigDecimal totalExpenses;
    private BigDecimal approvedExpenses;
    private BigDecimal pendingExpenses;
    private Long       pendingApprovals;
    private BigDecimal travelAndSiteVisit;
    private BigDecimal totalCommission;
    private BigDecimal approvedThisMonth;
    private BigDecimal totalAdvances;
    private BigDecimal unsettledAdvances;

    private List<ProjectExpenseStatsResponse.UserExpenseSummary> userBreakdown;
    private List<ProjectExpenseStatsResponse.CategorySummary>    categoryBreakdown;
    private List<ProjectExpenseResponse>                          recentExpenses;
}
