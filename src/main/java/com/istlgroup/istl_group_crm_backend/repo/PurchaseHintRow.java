package com.istlgroup.istl_group_crm_backend.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  One historical purchase of a catalog item, flattened for the BOM price hint.
 *
 *  A Spring Data interface projection rather than the {@code Object[]} used by
 *  {@link PurchaseOrderItemRepository#findLivePoLinesForProject} deliberately:
 *  native {@code Object[]} rows hand back BIGINT as whatever Connector/J feels
 *  like (Integer or Long depending on width) and DATETIME as {@code Timestamp},
 *  which is why a hand-rolled coercer exists in BomProcurementGuard. Declaring
 *  the types once here removes that whole class of ClassCastException.
 *
 *  Getter names MUST match the SELECT aliases in the query that populates this.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public interface PurchaseHintRow {

    /** bom_items_master.id — the catalog item. Never null (the query filters on it). */
    Long getItemId();

    /** bom_item_variants.id — the catalog make. NULL = item matched, make not catalogued. */
    Long getVariantId();

    /** Pre-tax unit rate, DECIMAL(18,6). The query guarantees > 0. */
    BigDecimal getUnitPrice();

    /** Unit the rate is quoted per, as typed on the PO. May be blank. */
    String getUnit();

    /** Quantity bought on this line, DECIMAL(18,6). The query guarantees > 0. */
    BigDecimal getQuantity();

    BigDecimal getTaxPercent();

    /** 1-based line position on the PO — part of the same-date tie-break. */
    Integer getLineNo();

    /** Free-text make snapshot on the PO line. Last-resort label when the catalog row is gone. */
    String getMakeText();

    Long getPoId();

    String getPoNo();

    /** Draft | Approved | Ordered | Partially Delivered | Delivered. Never 'Cancelled' (filtered). */
    String getPoStatus();

    /** purchase_orders.order_date — what "most recent" means. Never null (the query filters it). */
    LocalDateTime getOrderDate();

    /** Live vendors.name where the PO has a vendor_id, else the PO's denormalised snapshot. */
    String getVendorName();
}
