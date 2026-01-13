package com.istlgroup.istl_group_crm_backend.wrapperClasses;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHistoryDTO {
    private Long id;
    private LocalDateTime paymentDate;
    private String paymentMode;
    private String referenceNumber;
    private BigDecimal amount;
    private String paidByName;
    private String notes;
}
