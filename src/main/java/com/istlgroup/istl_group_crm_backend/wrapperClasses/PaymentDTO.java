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
public class PaymentDTO {
    private LocalDateTime paymentDate;
    private String paymentMode; // Bank Transfer, UPI, Cheque, NEFT, RTGS, Cash
    private String referenceNumber;
    private BigDecimal amount;
    private String notes;
}
