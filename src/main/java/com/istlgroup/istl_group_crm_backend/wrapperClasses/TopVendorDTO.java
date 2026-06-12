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
public class TopVendorDTO {
    private Long vendorId;
    private String vendorName;
    private Integer totalOrders;
    private BigDecimal totalSpend;
    private Double rating;
}
