package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItemEntity, Long> {

    /**
     * Find all items for a purchase order
     */
    List<PurchaseOrderItemEntity> findByPurchaseOrderId(Long purchaseOrderId);

    /**
     * Delete all items for a purchase order
     */
    @Modifying
    @Query("DELETE FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    void deleteByPurchaseOrderId(@Param("purchaseOrderId") Long purchaseOrderId);

    /**
     * Get total ordered quantity for a PO.
     * FIX: Return type changed from Integer → BigDecimal to match the DECIMAL(18,4)
     * column. Using Integer caused silent truncation which prevented the PO status
     * from ever being set to "Delivered".
     */
    @Query("SELECT SUM(poi.quantity) FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    BigDecimal getTotalOrderedItems(@Param("purchaseOrderId") Long purchaseOrderId);

    /**
     * Get total delivered quantity for a PO.
     * FIX: Same fix — BigDecimal instead of Integer.
     */
    @Query("SELECT SUM(poi.deliveredQty) FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    BigDecimal getTotalDeliveredItems(@Param("purchaseOrderId") Long purchaseOrderId);

    /**
     * Get all pending items (not fully delivered)
     */
    @Query("SELECT poi FROM PurchaseOrderItemEntity poi WHERE poi.quantity > poi.deliveredQty")
    List<PurchaseOrderItemEntity> findPendingItems();

    /**
     * Count items in a PO
     */
    @Query("SELECT COUNT(poi) FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    Long countByPurchaseOrderId(@Param("purchaseOrderId") Long purchaseOrderId);

    /**
     * Find all PO items matching an item name within a project.
     * Used to calculate how much of an order book item has already been assigned to POs.
     */
    @Query("SELECT poi FROM PurchaseOrderItemEntity poi " +
           "WHERE poi.itemName = :itemName " +
           "AND poi.purchaseOrder.projectId = :projectId " +
           "AND poi.purchaseOrder.deletedAt IS NULL " +
           "AND poi.purchaseOrder.status != 'Cancelled'")
    List<PurchaseOrderItemEntity> findByItemNameAndProjectId(
        @Param("itemName") String itemName,
        @Param("projectId") String projectId);

    /**
     * Returns ALL PO line items for a given project (across all POs under that project).
     * Excludes cancelled/deleted POs.
     * Used by the Inventory INWARD transaction modal item picker.
     */
    @Query("SELECT poi FROM PurchaseOrderItemEntity poi " +
           "JOIN poi.purchaseOrder po " +
           "WHERE po.projectId = :projectId " +
           "  AND po.deletedAt IS NULL " +
           "  AND (po.status IS NULL OR po.status <> 'Cancelled') " +
           "ORDER BY po.poNo ASC, poi.lineNo ASC")
    List<PurchaseOrderItemEntity> findAllItemsByProjectId(@Param("projectId") String projectId);

    /**
     * Sum ordered quantity per item name across ALL POs raised under a quotation.
     * Excludes cancelled/deleted POs. Used to enforce the cumulative per-line cap
     * (sum of ordered qty per line must not exceed the quoted qty). Matches by item
     * name scoped to the quotation (mirrors the order-book allocatedQty pattern).
     * Returns rows of [itemName (String), orderedQty (BigDecimal)].
     */
    @Query("SELECT poi.itemName, SUM(poi.quantity) FROM PurchaseOrderItemEntity poi " +
           "WHERE poi.purchaseOrder.quotationId = :quotationId " +
           "  AND poi.purchaseOrder.deletedAt IS NULL " +
           "  AND (poi.purchaseOrder.status IS NULL OR poi.purchaseOrder.status <> 'Cancelled') " +
           "GROUP BY poi.itemName")
    List<Object[]> sumOrderedQtyByItemForQuotation(@Param("quotationId") Long quotationId);

    // ─────────────────────────────────────────────────────────────────────────
    //  Project BOM budget — "how much of this BOM line has already been ordered?"
    //
    //  ONE row-level read, folded onto BOM lines in Java by BomProcurementGuard, so
    //  that the resolution used to CHECK an incoming line and the resolution used to
    //  ATTRIBUTE an already-ordered line are literally the same code (§A2). The two
    //  GROUP BY queries this replaced could not do that: one inner-joined project_bom
    //  (so a line pointing at a soft-deleted BOM line silently vanished from the
    //  budget) while the other matched legacy rows on item name alone.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Every live PO line on a project, with the bits of its PO header the BOM budget
     * and the planned-vs-actual screen need. One query per project, no lazy PO fetch.
     *
     * <p>"Live" = deleted_at IS NULL and status <> 'Cancelled'. DRAFT POs are included:
     * a draft has reserved the material. {@code excludePoId} is the PO being edited,
     * or -1 for none — an untyped NULL bind compares unreliably in native SQL.
     *
     * @return rows of [poId, poNo, poRefId, vendorId, vendorName, orderDate, status,
     *                  lineNo, itemName, make, unit, quantity, unitPrice, taxPercent,
     *                  bomLineId, bomItemId, variantId, bomMatch]
     */
    @Query(value =
        "SELECT po.id, po.po_no, po.po_ref_id, po.vendor_id, po.vendor_name, " +
        "       po.order_date, po.status, " +
        "       poi.line_no, poi.item_name, poi.make, poi.unit, " +
        "       poi.quantity, poi.unit_price, poi.tax_percent, " +
        "       poi.bom_line_id, poi.bom_item_id, poi.variant_id, poi.bom_match " +
        "FROM purchase_order_items poi " +
        "JOIN purchase_orders po ON po.id = poi.po_id " +
        "WHERE po.project_id  = :projectUniqueId " +
        "  AND po.deleted_at  IS NULL " +
        "  AND (po.status IS NULL OR po.status <> 'Cancelled') " +
        "  AND po.id <> :excludePoId " +
        "ORDER BY po.order_date, po.id, poi.line_no",
        nativeQuery = true)
    List<Object[]> findLivePoLinesForProject(@Param("projectUniqueId") String projectUniqueId,
                                            @Param("excludePoId")     Long   excludePoId);

    // ─────────────────────────────────────────────────────────────────────────
    //  BOM price hint — "what did we last pay for this item in this make?"
    //
    //  Global, NOT project-scoped: the estimator pricing a lead wants the last
    //  real number from anywhere, not from this job (which has none yet).
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Every purchase of the given catalog items that is trustworthy enough to quote
     * back as a price, newest first.
     *
     * <p><b>Where identity comes from.</b> A PO line may carry the catalog ids itself
     * ({@code bom_match} ID/VARIANT), or carry only {@code bom_line_id} — the project
     * BOM line it consumes. The latter is NOT a loose association: a fallback match
     * STOPS the save and the buyer must confirm or correct it in BomMatchConfirmDialog
     * before the PO is written (see {@code PurchaseOrders.js} #preCheckBom), so
     * {@code bom_line_id} is a human-confirmed pointer. It is also already what the
     * project's planned-vs-actual screen attributes procured money through, and a price
     * hint has no business being stricter about the same link than the money report is.
     *
     * <p>Identity is therefore resolved <b>atomically from ONE source</b>: if the line
     * has its own {@code bom_item_id} both ids come from the line, otherwise both come
     * from the BOM row. Never a COALESCE per column — independent COALESCEs would splice
     * the line's item onto a later-edited BOM row's make and invent an attribution that
     * never existed, which is precisely the wrong-but-confident price the spec forbids.
     * Note this is a lookup by stored id at every step; no make string is ever compared.
     *
     * <p>{@code project_bom.deleted_at} is deliberately NOT filtered — this is a
     * historical question, and a since-deleted BOM line still truthfully records what
     * was bought. Rows where neither source yields an item id drop out on their own,
     * which is what excludes pre-enforcement legacy lines and {@code bom_match='NONE'}.
     *
     * <p>Also excluded:
     * <ul>
     *   <li><b>WORK_ORDER</b> — same table as purchase orders, but its unit_price is a
     *       labour/service rate, not a material cost.</li>
     *   <li><b>zero price or zero quantity</b> — a placeholder or zeroed-out line. The
     *       spec is explicit that a zero must never surface as if it were a price.</li>
     * </ul>
     *
     * <p>DRAFT POs are <b>included</b>, matching {@link #findLivePoLinesForProject} and
     * BomProcurementGuard — procurement has exactly one liveness predicate
     * ({@code deleted_at IS NULL AND status <> 'Cancelled'}) and this does not fork it.
     * The caller surfaces each row's status so a draft-sourced price is visible as one.
     *
     * <p>The ORDER BY is the whole tie-break: newest order_date, then newest PO, then
     * the last line entered on it. Deterministic, and never an average — a blended rate
     * is not a price anything was actually bought at.
     *
     * <p>{@code rowCap} is a runaway guard, not paging. The caller warns if it is hit;
     * at that point this wants rewriting with ROW_NUMBER() partitioned by bom_item_id.
     */
    @Query(value =
        "SELECT CASE WHEN poi.bom_item_id IS NOT NULL THEN poi.bom_item_id " +
        "            ELSE pb.bom_item_id END AS itemId, " +
        "       CASE WHEN poi.bom_item_id IS NOT NULL THEN poi.variant_id " +
        "            ELSE pb.variant_id  END AS variantId, " +
        "       poi.unit_price  AS unitPrice, poi.unit AS unit, poi.quantity AS quantity, " +
        "       poi.tax_percent AS taxPercent, poi.line_no AS lineNo, poi.make AS makeText, " +
        "       po.id AS poId, po.po_no AS poNo, po.status AS poStatus, " +
        "       po.order_date AS orderDate, " +
        "       COALESCE(v.name, po.vendor_name) AS vendorName " +
        "FROM purchase_order_items poi " +
        "JOIN purchase_orders po ON po.id = poi.po_id " +
        "LEFT JOIN project_bom pb ON pb.id = poi.bom_line_id " +
        "LEFT JOIN vendors     v  ON v.id  = po.vendor_id " +
        "WHERE po.deleted_at IS NULL " +
        "  AND (po.status IS NULL OR po.status <> 'Cancelled') " +
        "  AND (po.document_type IS NULL OR po.document_type = 'PURCHASE_ORDER') " +
        "  AND po.order_date IS NOT NULL " +
        "  AND poi.unit_price IS NOT NULL AND poi.unit_price > 0 " +
        "  AND poi.quantity   IS NOT NULL AND poi.quantity   > 0 " +
        // Index-friendly pre-filter, then the exact effective-item test. Both are
        // needed: the OR form alone would admit a line whose EFFECTIVE item is not
        // in the set (own id absent, BOM row's id present but for another item).
        "  AND (poi.bom_item_id IN (:itemIds) OR pb.bom_item_id IN (:itemIds)) " +
        "  AND (CASE WHEN poi.bom_item_id IS NOT NULL THEN poi.bom_item_id " +
        "            ELSE pb.bom_item_id END) IN (:itemIds) " +
        "ORDER BY po.order_date DESC, po.id DESC, poi.line_no DESC " +
        "LIMIT :rowCap",
        nativeQuery = true)
    List<PurchaseHintRow> findPurchaseHistoryForItems(@Param("itemIds") Collection<Long> itemIds,
                                                      @Param("rowCap")  int              rowCap);
}
