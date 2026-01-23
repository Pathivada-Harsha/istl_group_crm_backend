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
public class FinancialDataDTO {
    
    // Budget
    private BigDecimal totalProjectValue;
    private BigDecimal budgetUtilized;
    private BigDecimal budgetUtilizationPercent;
    private BigDecimal remaining;
    
    // Revenue & Profit
    private BigDecimal projectedProfit;
    private BigDecimal profitMargin;
    
    // Client Billing (if applicable)
    private BigDecimal amountToBeReceived;
    private BigDecimal amountReceived;
    private BigDecimal pendingReceipts;
    private BigDecimal billingPercentage;
    
    // Vendor Payments
    private BigDecimal totalPayable;
    private BigDecimal amountPaid;
    private BigDecimal pendingPayments;
    private BigDecimal paymentPercentage;
    
    // Cash Flow
    private BigDecimal cashInHand;
    private BigDecimal burnRate;
}
