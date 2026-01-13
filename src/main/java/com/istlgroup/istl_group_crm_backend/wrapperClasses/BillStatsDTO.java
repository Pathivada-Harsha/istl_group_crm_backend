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
public class BillStatsDTO {
    private long totalBills;
    private BigDecimal outstandingAmount;
    private long billsThisMonth;
    private long paidBills;
    private int linkedToPOPercentage;
}
