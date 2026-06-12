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
public class ProcurementDataDTO {
    
    // Purchase Orders
    private Integer totalPOs;
    private BigDecimal totalPOValue;
    private BigDecimal avgPOValue;
    private Integer deliveredPOs;
    private BigDecimal deliveredPOValue;
    private Integer pendingPOs;
    private BigDecimal pendingPOValue;
    private Integer cancelledPOs;
    private BigDecimal cancelledPOValue;
    private BigDecimal deliveryRate;
    
    // Quotations
    private Integer totalQuotations;
    private BigDecimal totalQuotationValue;
    private BigDecimal avgQuotationValue;
    private Integer approvedQuotations;
    private BigDecimal approvedQuotationValue;
    
    // Bills
    private Integer totalBills;
    private BigDecimal totalBillValue;
    private Integer paidBills;
    private BigDecimal paidBillValue;
    private BigDecimal pendingBillValue;
    
    // Status Breakdown
    private POStatusBreakdownDTO poStatusBreakdown;
    private QuotationStatusBreakdownDTO quotationStatusBreakdown;
}
