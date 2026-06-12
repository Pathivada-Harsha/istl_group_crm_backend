package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.InvBillEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvBillItemEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvBillWrapper {

    private Long   id;
    private String billNo;
    private Long   vendorId;
    private String vendorName;
    private Long   poId;
    private String poNo;            // enriched at read time
    private Long   warehouseId;
    private String warehouseName;   // enriched
    private String groupName;
    private String subGroupName;
    private String projectId;
    private LocalDate  billDate;
    private LocalDate  dueDate;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal balanceAmount;
    private String     status;
    private String     notes;
    private Long       createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder.Default
    private List<InvBillItemWrapper> items = new ArrayList<>();

    // ── Nested item wrapper ──────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InvBillItemWrapper {
        private Long       id;
        private Long       inventoryItemId;
        private String     itemCode;
        private String     itemName;
        private String     unit;
        private BigDecimal qty;
        private BigDecimal rate;
        private BigDecimal taxPct;
        private String     notes;

        public static InvBillItemWrapper from(InvBillItemEntity e) {
            return InvBillItemWrapper.builder()
                .id(e.getId())
                .inventoryItemId(e.getInventoryItemId())
                .itemCode(e.getItemCode())
                .itemName(e.getItemName())
                .unit(e.getUnit())
                .qty(e.getQty())
                .rate(e.getRate())
                .taxPct(e.getTaxPct())
                .notes(e.getNotes())
                .build();
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static InvBillWrapper from(InvBillEntity e, String poNo, String warehouseName) {
        List<InvBillItemWrapper> itemWrappers = e.getItems() == null ? new ArrayList<>()
            : e.getItems().stream().map(InvBillItemWrapper::from).collect(Collectors.toList());

        return InvBillWrapper.builder()
            .id(e.getId())
            .billNo(e.getBillNo())
            .vendorId(e.getVendorId())
            .vendorName(e.getVendorName())
            .poId(e.getPoId())
            .poNo(poNo)
            .warehouseId(e.getWarehouseId())
            .warehouseName(warehouseName)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .projectId(e.getProjectId())
            .billDate(e.getBillDate())
            .dueDate(e.getDueDate())
            .totalAmount(e.getTotalAmount())
            .paidAmount(e.getPaidAmount() != null ? e.getPaidAmount() : BigDecimal.ZERO)
            .balanceAmount(e.getBalanceAmount())
            .status(e.getStatus())
            .notes(e.getNotes())
            .createdBy(e.getCreatedBy())
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .items(itemWrappers)
            .build();
    }
}