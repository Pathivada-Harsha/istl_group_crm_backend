package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProjectExpenseStatsResponse {
    private String projectId;
    private String projectName;
    private BigDecimal totalExpenses;
    private BigDecimal approvedExpenses;
    private BigDecimal pendingExpenses;
    private BigDecimal travelAndSiteVisit;
    private BigDecimal totalCommission;
    private Long pendingApprovals;
    private BigDecimal approvedThisMonth;
    private BigDecimal totalAdvances;
    private BigDecimal unsettledAdvances;

    // Per-user breakdown
    private List<UserExpenseSummary> userBreakdown;

    // Category breakdown
    private List<CategorySummary> categoryBreakdown;

    @Data
    @Builder
    public static class UserExpenseSummary {
        private Long userId;
        private String userName;
        private BigDecimal totalAmount;
        private Long expenseCount;
        private BigDecimal approvedAmount;
        private BigDecimal pendingAmount;
    }

    @Data
    @Builder
    public static class CategorySummary {
        private String category;
        private BigDecimal totalAmount;
        private Long count;
    }
}
