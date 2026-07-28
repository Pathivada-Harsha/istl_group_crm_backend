package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvTransactionRepository extends JpaRepository<InvTransactionEntity, Long> {

    @Query(
        "SELECT t FROM InvTransactionEntity t " +
        "WHERE (:warehouseId     IS NULL OR t.warehouseId      = :warehouseId) " +
        "  AND (:groupName       IS NULL OR :groupName       = '' OR t.groupName       = :groupName) " +
        "  AND (:subGroupName    IS NULL OR :subGroupName    = '' OR t.subGroupName    = :subGroupName) " +
        "  AND (:projectId       IS NULL OR :projectId       = '' OR t.projectId       = :projectId) " +
        "  AND (:type            IS NULL OR :type            = '' OR t.type            = :type) " +
        "  AND (:inventoryItemId IS NULL OR t.inventoryItemId = :inventoryItemId) " +
        "  AND (:search IS NULL OR :search = '' " +
        "       OR LOWER(t.itemCode)  LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(t.itemName)  LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(t.refNo)     LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(t.txnNo)     LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY t.createdAt DESC"
    )
    Page<InvTransactionEntity> findFiltered(
        @Param("warehouseId")     Long    warehouseId,
        @Param("groupName")       String  groupName,
        @Param("subGroupName")    String  subGroupName,
        @Param("projectId")       String  projectId,
        @Param("type")            String  type,
        @Param("inventoryItemId") Long    inventoryItemId,
        @Param("search")          String  search,
        Pageable pageable
    );

    /**
     * Returns all transactions for a given project and transaction type.
     * Used by ProjectDashboardService to build warehouse issuance summaries.
     */
    @Query(
        "SELECT t FROM InvTransactionEntity t " +
        "WHERE t.projectId = :projectId " +
        "  AND t.type = :type " +
        "ORDER BY t.transactionDate DESC, t.createdAt DESC"
    )
    List<InvTransactionEntity> findByProjectIdAndType(
        @Param("projectId") String projectId,
        @Param("type")      String type
    );

    /**
     * Returns only SITE RETURN INWARD transactions — i.e. items coming back from
     * site to warehouse, NOT vendor/PO deliveries into the warehouse.
     *
     * Discriminator: po_id IS NULL means it was NOT auto-created from a PO receipt.
     * PO-linked INWARDs (vendor deliveries) always have po_id populated.
     * Site returns are recorded manually with no PO reference.
     *
     * Used by ProjectDashboardService and ReportService to compute inwardRecoveryValue
     * so that vendor PO receipts are NOT incorrectly counted as project credits.
     */
    @Query(
        "SELECT t FROM InvTransactionEntity t " +
        "WHERE t.projectId = :projectId " +
        "  AND t.type = :type " +
        "  AND t.poId IS NULL " +
        "ORDER BY t.transactionDate DESC, t.createdAt DESC"
    )
    List<InvTransactionEntity> findByProjectIdAndTypeAndPoIdIsNull(
        @Param("projectId") String projectId,
        @Param("type")      String type
    );

    /**
     * Per-item total of what was ISSUED from a warehouse to this project
     * (i.e. OUTWARD movements), aggregated across every issuance.
     *
     * qty is stored signed — OUTWARD rows are negative — so ABS() is needed
     * to get the quantity that physically left the warehouse.
     *
     * Row shape: [0] inventoryItemId (Long)
     *            [1] issuedQty       (BigDecimal, always positive)
     *            [2] lastIssuedDate  (LocalDate)
     *
     * Used by the Inventory INWARD modal so items sent out to site can be
     * picked and received back, alongside the PO line items.
     */
    @Query(
        "SELECT t.inventoryItemId, SUM(ABS(t.qty)), MAX(t.transactionDate) " +
        "FROM InvTransactionEntity t " +
        "WHERE t.projectId = :projectId " +
        "  AND t.type = 'OUTWARD' " +
        "  AND t.inventoryItemId IS NOT NULL " +
        "GROUP BY t.inventoryItemId"
    )
    List<Object[]> sumIssuedQtyByProject(@Param("projectId") String projectId);

    /**
     * Per-item total of what has already come back INWARD under this project.
     *
     * Row shape: [0] inventoryItemId (Long)
     *            [1] returnedQty     (BigDecimal, always positive)
     *
     * Caveat: inv_transactions never stores po_id (nothing populates it), so a
     * PO delivery received into the warehouse under the same project and the
     * same inventory item also lands in this total. It is therefore an
     * advisory figure used to pre-fill / display "already returned" — never a
     * hard limit on what can be received.
     */
    @Query(
        "SELECT t.inventoryItemId, SUM(ABS(t.qty)) " +
        "FROM InvTransactionEntity t " +
        "WHERE t.projectId = :projectId " +
        "  AND t.type = 'INWARD' " +
        "  AND t.inventoryItemId IS NOT NULL " +
        "GROUP BY t.inventoryItemId"
    )
    List<Object[]> sumReturnedQtyByProject(@Param("projectId") String projectId);
}