package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
}