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
public class ActivityDTO {
    private String type; // PO, Quotation, Bill, Vendor
    private String action;
    private String status;
    private BigDecimal amount;
    private LocalDateTime date;
    private String performedBy;
}
