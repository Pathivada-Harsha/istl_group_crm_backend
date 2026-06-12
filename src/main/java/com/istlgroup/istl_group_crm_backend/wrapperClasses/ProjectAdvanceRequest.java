package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ProjectAdvanceRequest {
    private String    projectId;
    private String    groupName;      // stored in project_advances.group_name
    private String    subGroupName;   // stored in project_advances.sub_group_name
    private LocalDate advanceDate;
    private LocalDate expectedTripDate;
    private String    tripPurpose;
    private Long      requestedByUserId;
    private String    requestedByName;   // ← was missing
    private Long      approvedByUserId;
    private String    approvedByName;    // ← was missing
    private String    status;
    private Long      relatedExpenseId;
    private List<AdvancePaymentRequest> advancePayments;

    @Data
    public static class AdvancePaymentRequest {
        private Integer    advanceNumber;
        private BigDecimal amount;
        private String     paymentMode;
        private LocalDate  paymentDate;
        private String     notes;
    }
}