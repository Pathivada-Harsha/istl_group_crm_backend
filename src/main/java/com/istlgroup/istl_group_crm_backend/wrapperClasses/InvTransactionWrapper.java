package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvTransactionWrapper {

    // ── Response fields ───────────────────────────────────────────────────────
    private Long   id;
    private String txnNo;
    private String type;
    private Long   inventoryItemId;
    private String itemCode;
    private String itemName;
    private String unit;
    private BigDecimal qty;
    private Long   warehouseId;
    private String warehouseName;       // enriched at read time
    private Long   fromWarehouseId;
    private String fromWarehouseName;   // enriched for TRANSFER
    private String groupName;
    private String subGroupName;
    private String projectId;
    private String refNo;
    private String notes;

    // ── Vendor / PO linkage (for INWARD from PO delivery) ────────────────────
    private Long   vendorId;
    private String vendorName;
    private Long   poId;
    private String poNo;
    private java.math.BigDecimal unitCost;
    private LocalDate     transactionDate;
    private Long          createdBy;
    private String        createdByName;  // enriched
    private LocalDateTime createdAt;

    // ── Request-only convenience fields (not stored) ─────────────────────────
    /** Frontend itemId — maps to inventoryItemId */
    private Long   itemId;
    /** Frontend ref — maps to refNo */
    private String ref;
    /** Frontend note — maps to notes */
    private String note;

    // ── Batch-inward line items (multi-item INWARD from site) ─────────────────
    /**
     * Used for POST /inventory/transactions/batch-inward.
     * Each line is one PO item being received into warehouse from site.
     */
    private List<InwardLine> poLines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InwardLine {
        private Long   poId;
        private Long   poItemId;
        /** FK to inventory_items.id — null = auto-create */
        private Long   inventoryItemId;
        private String itemCode;
        private String itemName;
        private String unit;
        private BigDecimal qty;
        private BigDecimal unitCost;
        /**
         * Conflict resolution decision from user:
         *   null    = not yet decided (backend will return conflicts)
         *   "ADD"   = add qty to existing item (inventoryItemId = existingItemId)
         *   "CREATE"= create a new separate inventory item
         */
        private String conflictAction;
        /** Set by backend in conflict response — the existing item's id */
        private Long   existingItemId;
        /** Set by backend in conflict response — the existing item's code */
        private String existingItemCode;
        /** Set by backend in conflict response — existing item current qty */
        private java.math.BigDecimal existingCurrentQty;
    }

    /** How many new inventory items were created during batch-inward */
    private Integer newItemsCreated;
    /**
     * Used only for POST /inventory/transactions/batch-outward.
     * Each line is one item being issued from the warehouse.
     */
    private List<OutwardLine> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutwardLine {
        /** inventoryItemId for this line */
        private Long   inventoryItemId;
        /** Absolute quantity to deduct */
        private BigDecimal qty;
        /** Unit cost (from item.unitCost, editable by user) for bill amount calculation */
        private BigDecimal unitCost;
    }

    // ── Response wrapper for batch-outward ────────────────────────────────────
    /** Txn numbers created (one per line) — returned to frontend */
    private List<String> createdTxnNos;
    /** Auto-generated bill number (BILL-YYYY-NNNN) */
    private String       autoBillNo;

    // ── Factory ──────────────────────────────────────────────────────────────

    public static InvTransactionWrapper from(InvTransactionEntity e,
                                             String warehouseName,
                                             String fromWarehouseName,
                                             String createdByName) {
        return InvTransactionWrapper.builder()
            .id(e.getId())
            .txnNo(e.getTxnNo())
            .type(e.getType())
            .inventoryItemId(e.getInventoryItemId())
            .itemCode(e.getItemCode())
            .itemName(e.getItemName())
            .unit(e.getUnit())
            .qty(e.getQty())
            .warehouseId(e.getWarehouseId())
            .warehouseName(warehouseName)
            .fromWarehouseId(e.getFromWarehouseId())
            .fromWarehouseName(fromWarehouseName)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .projectId(e.getProjectId())
            .refNo(e.getRefNo())
            .notes(e.getNotes())
            .vendorId(e.getVendorId())
            .vendorName(e.getVendorName())
            .poId(e.getPoId())
            .poNo(e.getPoNo())
            .unitCost(e.getUnitCost())
            .transactionDate(e.getTransactionDate())
            .createdBy(e.getCreatedBy())
            .createdByName(createdByName)
            .createdAt(e.getCreatedAt())
            .build();
    }
}