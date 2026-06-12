package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for bulk item creation (POST /inventory/items/bulk).
 *
 * A single shared scope — warehouseId / groupName / subGroupName — applies to
 * every row in {@link #items}. Individual rows may still override groupName /
 * subGroupName if needed; when a row leaves them null the shared values (and
 * finally the warehouse's own group/subgroup) are used.
 *
 * Each entry in {@code items} reuses {@link InventoryItemWrapper}, so only the
 * per-item fields (itemCode, name, qtys, cost, project, notes…) need to be set.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryItemBulkWrapper {

    /** Shared warehouse for every row — required. */
    private Long warehouseId;

    /** Shared group for every row (falls back to the warehouse's group). */
    private String groupName;

    /** Shared sub-group for every row (falls back to the warehouse's sub-group). */
    private String subGroupName;

    /** The rows to create. */
    private List<InventoryItemWrapper> items;
}