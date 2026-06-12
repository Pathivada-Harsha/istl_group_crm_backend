// InvoiceItemRepository.java
package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItemEntity, Long> {
    
    @Query("SELECT ii FROM InvoiceItemEntity ii WHERE ii.invoice.id = :invoiceId")
    List<InvoiceItemEntity> findByInvoiceId(@Param("invoiceId") Long invoiceId);
    
    void deleteByInvoiceId(Long invoiceId);

    /**
     * Sum all invoiced quantities for a given order_book_item_id,
     * excluding soft-deleted invoices (deleted_at IS NULL).
     */
    /**
     * Hybrid sum: matches either by order_book_item_id (new invoices)
     * OR by description = item_name + same project_id (legacy invoices with NULL order_book_item_id).
     * Avoids double-counting by using DISTINCT invoice_item id.
     */
    @Query(value =
        "SELECT COALESCE(SUM(sub.qty), 0) FROM ( " +
        "  SELECT ii.id, ii.quantity AS qty " +
        "  FROM invoice_items ii " +
        "  JOIN invoices inv ON ii.invoice_id = inv.id " +
        "  JOIN order_book_items obi ON obi.id = :obItemId " +
        "  JOIN order_book ob ON ob.id = obi.order_book_id " +
        "  WHERE inv.deleted_at IS NULL " +
        "  AND ( " +
        "    ii.order_book_item_id = :obItemId " +
        "    OR (ii.order_book_item_id IS NULL " +
        "        AND ii.description = obi.item_name " +
        "        AND inv.project_id = ob.project_id) " +
        "  ) GROUP BY ii.id " +
        ") sub",
        nativeQuery = true)
    java.math.BigDecimal sumInvoicedQtyByOrderBookItemId(@Param("obItemId") Long obItemId);

    /**
     * Same hybrid sum but excluding a specific invoice (for update validation).
     */
    @Query(value =
        "SELECT COALESCE(SUM(sub.qty), 0) FROM ( " +
        "  SELECT ii.id, ii.quantity AS qty " +
        "  FROM invoice_items ii " +
        "  JOIN invoices inv ON ii.invoice_id = inv.id " +
        "  JOIN order_book_items obi ON obi.id = :obItemId " +
        "  JOIN order_book ob ON ob.id = obi.order_book_id " +
        "  WHERE inv.deleted_at IS NULL " +
        "  AND inv.id != :excludeInvoiceId " +
        "  AND ( " +
        "    ii.order_book_item_id = :obItemId " +
        "    OR (ii.order_book_item_id IS NULL " +
        "        AND ii.description = obi.item_name " +
        "        AND inv.project_id = ob.project_id) " +
        "  ) GROUP BY ii.id " +
        ") sub",
        nativeQuery = true)
    java.math.BigDecimal sumInvoicedQtyForItemInInvoice(
        @Param("obItemId") Long obItemId,
        @Param("excludeInvoiceId") Long excludeInvoiceId);
}