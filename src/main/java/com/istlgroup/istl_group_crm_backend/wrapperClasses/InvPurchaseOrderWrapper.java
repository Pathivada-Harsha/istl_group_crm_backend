package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderItemEntity;
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
public class InvPurchaseOrderWrapper {

    private Long   id;
    private String poNo;
    private Long   vendorId;
    private String vendorName;
    private String vendorContact;
    private Long   warehouseId;
    private String warehouseName;   // enriched at read time
    private String groupName;
    private String subGroupName;
    private String projectId;
    private LocalDate orderDate;
    private LocalDate expectedDelivery;
    private String status;
    private String paymentStatus;
    private BigDecimal totalValue;
    private Integer totalItemsOrdered;
    private Integer totalItemsReceived;
    private String paymentTerms;
    private String deliveryAddress;
    private String notes;
    private Long   createdBy;
    private Long   approvedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder.Default
    private List<InvPoItemWrapper> items = new ArrayList<>();

    // ── Nested item wrapper ──────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InvPoItemWrapper {
        private Long       id;
        private Long       inventoryItemId;
        private String     itemCode;
        private String     itemName;
        private String     unit;
        private BigDecimal orderedQty;
        private BigDecimal receivedQty;
        private BigDecimal rate;
        private BigDecimal taxPct;
        private BigDecimal subtotal;
        private BigDecimal taxAmount;
        private BigDecimal lineTotal;
        private String     notes;

        public static InvPoItemWrapper from(InvPurchaseOrderItemEntity e) {
            return InvPoItemWrapper.builder()
                .id(e.getId())
                .inventoryItemId(e.getInventoryItemId())
                .itemCode(e.getItemCode())
                .itemName(e.getItemName())
                .unit(e.getUnit())
                .orderedQty(e.getOrderedQty())
                .receivedQty(e.getReceivedQty())
                .rate(e.getRate())
                .taxPct(e.getTaxPct())
                .subtotal(e.getSubtotal())
                .taxAmount(e.getTaxAmount())
                .lineTotal(e.getLineTotal())
                .notes(e.getNotes())
                .build();
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static InvPurchaseOrderWrapper from(InvPurchaseOrderEntity e, String warehouseName) {
        List<InvPoItemWrapper> itemWrappers = e.getItems() == null ? new ArrayList<>()
            : e.getItems().stream().map(InvPoItemWrapper::from).collect(Collectors.toList());

        return InvPurchaseOrderWrapper.builder()
            .id(e.getId())
            .poNo(e.getPoNo())
            .vendorId(e.getVendorId())
            .vendorName(e.getVendorName())
            .vendorContact(e.getVendorContact())
            .warehouseId(e.getWarehouseId())
            .warehouseName(warehouseName)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .projectId(e.getProjectId())
            .orderDate(e.getOrderDate())
            .expectedDelivery(e.getExpectedDelivery())
            .status(e.getStatus())
            .paymentStatus(e.getPaymentStatus())
            .totalValue(e.getTotalValue())
            .totalItemsOrdered(e.getTotalItemsOrdered())
            .totalItemsReceived(e.getTotalItemsReceived())
            .paymentTerms(e.getPaymentTerms())
            .deliveryAddress(e.getDeliveryAddress())
            .notes(e.getNotes())
            .createdBy(e.getCreatedBy())
            .approvedBy(e.getApprovedBy())
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .items(itemWrappers)
            .build();
    }
}