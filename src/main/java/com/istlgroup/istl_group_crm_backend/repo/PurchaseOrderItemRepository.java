package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Get total ordered items count for a PO
     */
    @Query("SELECT SUM(poi.quantity) FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    Integer getTotalOrderedItems(@Param("purchaseOrderId") Long purchaseOrderId);
    
    /**
     * Get total delivered items count for a PO
     */
    @Query("SELECT SUM(poi.deliveredQty) FROM PurchaseOrderItemEntity poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    Integer getTotalDeliveredItems(@Param("purchaseOrderId") Long purchaseOrderId);
    
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
}