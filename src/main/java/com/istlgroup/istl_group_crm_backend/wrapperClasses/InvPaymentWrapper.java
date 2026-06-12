package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.InvPaymentEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvPaymentWrapper {

    private Long   id;
    private String paymentNo;
    private Long   billId;
    private String billNo;          // enriched at read time
    private Long   vendorId;
    private String vendorName;
    private Long   warehouseId;
    private String warehouseName;   // enriched
    private String groupName;
    private String subGroupName;
    private String projectId;
    private LocalDate  paymentDate;
    private BigDecimal amount;
    private String     paymentMode;
    private String     referenceNumber;
    private String     notes;
    private Long       createdBy;
    private LocalDateTime createdAt;
    private BigDecimal appliedAmount;    // for ADVANCE: how much allocated so far
    private BigDecimal unappliedAmount;  // computed: amount - appliedAmount
    private Long       advanceId;        // for allocations: which advance funded this
    private String     advancePaymentNo; // payment_no of the source advance (enriched at read)

    // ── Factory ──────────────────────────────────────────────────────────────

    public static InvPaymentWrapper from(InvPaymentEntity e, String billNo, String warehouseName) {
        BigDecimal applied    = e.getAppliedAmount() != null ? e.getAppliedAmount() : BigDecimal.ZERO;
        BigDecimal unapplied  = e.getAmount() != null ? e.getAmount().subtract(applied) : BigDecimal.ZERO;
        if (unapplied.compareTo(BigDecimal.ZERO) < 0) unapplied = BigDecimal.ZERO;
        return InvPaymentWrapper.builder()
            .id(e.getId())
            .paymentNo(e.getPaymentNo())
            .billId(e.getBillId())
            .billNo(billNo)
            .vendorId(e.getVendorId())
            .vendorName(e.getVendorName())
            .warehouseId(e.getWarehouseId())
            .warehouseName(warehouseName)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .projectId(e.getProjectId())
            .paymentDate(e.getPaymentDate())
            .amount(e.getAmount())
            .paymentMode(e.getPaymentMode())
            .referenceNumber(e.getReferenceNumber())
            .notes(e.getNotes())
            .appliedAmount(applied)
            .unappliedAmount(unapplied)
            .advanceId(e.getAdvanceId())
            .advancePaymentNo(null)
            .createdBy(e.getCreatedBy())
            .createdAt(e.getCreatedAt())
            .build(); 
    }
}