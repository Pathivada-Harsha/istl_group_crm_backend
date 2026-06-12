package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectAdvanceResponse {
    private Long id;
    private String advanceCode;
    private String projectId;
    private String projectName;
    private String groupName;
    private String subGroupName;
    private LocalDate advanceDate;
    private LocalDate expectedTripDate;
    private String tripPurpose;
    private Long requestedByUserId;
    private String requestedByName;
    private Long approvedByUserId;
    private String approvedByName;
    private BigDecimal totalAdvanceAmount;
    private String status;
    private Long relatedExpenseId;
    private List<PaymentDetail> advancePayments;
    private LocalDateTime createdAt;

    @Data
    @Builder
    public static class PaymentDetail {
        private Long id;
        private Integer advanceNumber;
        private BigDecimal amount;
        private String paymentMode;
        private LocalDate paymentDate;
        private String notes;
    }
}
