package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.istlgroup.istl_group_crm_backend.entity.InventoryItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.WarehouseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for inventory items.
 *
 * The `status` field is *derived* from current/min/max — the frontend
 * never has to compute it, and the value can never drift from the
 * underlying quantities. Same for `location`, which mirrors the linked
 * warehouse's name for display.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryItemWrapper {

    private Long       id;
    private Long       warehouseId;
    private String     warehouseCode;   // populated for display only
    private String     location;        // = warehouse.name

    private String     itemCode;
    private String     name;
    private String     category;
    private String     unit;

    private BigDecimal currentQty;
    private BigDecimal minQty;
    private BigDecimal maxQty;
    private BigDecimal unitCost;

    private String     groupName;
    private String     subGroupName;
    private String     projectId;

    private String     notes;
    private Boolean    isActive;

    /**
     * Base64-encoded image data (data URI: "data:image/png;base64,...").
     * Null when no image is stored. Frontend caps uploads at 5 MB.
     */
    private String     imageData;

    /** Derived: IN_STOCK | LOW_STOCK | OUT_OF_STOCK */
    private String     status;

    /** Derived: currentQty * unitCost (rounded by frontend if needed) */
    private BigDecimal totalValue;

    private LocalDateTime lastUpdated;

    public static InventoryItemWrapper from(InventoryItemEntity e, WarehouseEntity wh) {
        if (e == null) return null;
        BigDecimal cur = e.getCurrentQty() != null ? e.getCurrentQty() : BigDecimal.ZERO;
        BigDecimal min = e.getMinQty()     != null ? e.getMinQty()     : BigDecimal.ZERO;
        BigDecimal cost = e.getUnitCost()  != null ? e.getUnitCost()   : BigDecimal.ZERO;

        return InventoryItemWrapper.builder()
            .id(e.getId())
            .warehouseId(e.getWarehouseId())
            .warehouseCode(wh != null ? wh.getCode() : null)
            .location(wh != null ? wh.getName() : null)
            .itemCode(e.getItemCode())
            .name(e.getName())
            .category(e.getCategory())
            .unit(e.getUnit())
            .currentQty(cur)
            .minQty(min)
            .maxQty(e.getMaxQty())
            .unitCost(cost)
            .groupName(e.getGroupName())
            .subGroupName(e.getSubGroupName())
            .projectId(e.getProjectId())
            .notes(e.getNotes())
            .isActive(e.getIsActive() != null ? e.getIsActive() : Boolean.TRUE)
            .imageData(e.getImageData())
            .status(deriveStatus(cur, min))
            .totalValue(cur.multiply(cost))
            .lastUpdated(e.getUpdatedAt())
            .build();
    }

    /** Convenience overload when caller doesn't need warehouse display fields. */
    public static InventoryItemWrapper from(InventoryItemEntity e) {
        return from(e, null);
    }

    private static String deriveStatus(BigDecimal current, BigDecimal min) {
        if (current.signum() <= 0) return "OUT_OF_STOCK";
        if (current.compareTo(min) <= 0 && min.signum() > 0) return "LOW_STOCK";
        return "IN_STOCK";
    }
}