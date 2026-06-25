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
}