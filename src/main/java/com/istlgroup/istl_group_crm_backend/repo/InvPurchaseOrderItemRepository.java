package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvPurchaseOrderItemRepository extends JpaRepository<InvPurchaseOrderItemEntity, Long> {

    List<InvPurchaseOrderItemEntity> findByPurchaseOrderId(Long purchaseOrderId);

    void deleteByPurchaseOrderId(Long purchaseOrderId);

    /**
     * Returns all PO line items for a given project (across all POs under that project).
     * Only includes non-cancelled POs so voided orders are excluded.
     */
    @Query(
        "SELECT i FROM InvPurchaseOrderItemEntity i " +
        "JOIN i.purchaseOrder p " +
        "WHERE p.projectId = :projectId " +
        "  AND p.deletedAt IS NULL " +
        "  AND (p.status IS NULL OR p.status <> 'CANCELLED') " +
        "ORDER BY p.poNo ASC, i.itemName ASC"
    )
    List<InvPurchaseOrderItemEntity> findAllItemsByProjectId(@Param("projectId") String projectId);
}