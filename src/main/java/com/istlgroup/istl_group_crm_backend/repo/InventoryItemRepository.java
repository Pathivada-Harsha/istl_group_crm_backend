package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InventoryItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItemEntity, Long> {

    Optional<InventoryItemEntity> findByWarehouseIdAndItemCode(Long warehouseId, String itemCode);

    boolean existsByWarehouseIdAndItemCode(Long warehouseId, String itemCode);

    /**
     * Paginated, server-side filtered query used by the Inventory Items tab.
     *
     * Filtering rules:
     *   - warehouseId  : strict match when supplied (null = all warehouses)
     *   - groupName    : STRICT match — items must explicitly belong to the
     *                    selected group. Unlike warehouses, items do NOT have
     *                    a "global" concept for group; if the caller sends a
     *                    groupName, only items with that exact value are returned.
     *   - subGroupName : same strict logic as groupName
     *   - category     : strict match when supplied
     *   - search       : case-insensitive LIKE on itemCode and name
     *   - onlyActive   : exclude soft-deleted rows when true
     */
    @Query(
        "SELECT i FROM InventoryItemEntity i " +
        "WHERE (:warehouseId  IS NULL OR i.warehouseId  = :warehouseId) " +
        "  AND (:groupName    IS NULL OR :groupName    = '' OR i.groupName    = :groupName) " +
        "  AND (:subGroupName IS NULL OR :subGroupName = '' OR i.subGroupName = :subGroupName) " +
        "  AND (:category     IS NULL OR :category     = '' OR i.category     = :category) " +
        "  AND (:onlyActive   = FALSE  OR i.isActive = TRUE) " +
        "  AND (:search IS NULL OR :search = '' " +
        "       OR LOWER(i.itemCode) LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(i.name)     LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY i.itemCode ASC"
    )
    Page<InventoryItemEntity> findScopedPaged(
        @Param("warehouseId")  Long    warehouseId,
        @Param("groupName")    String  groupName,
        @Param("subGroupName") String  subGroupName,
        @Param("category")     String  category,
        @Param("search")       String  search,
        @Param("onlyActive")   boolean onlyActive,
        Pageable pageable
    );

    /** Used by PO/Bill goods-receipt flow to find an item to top up. */
    long countByWarehouseId(Long warehouseId);

    /**
     * Find an item in a warehouse by name (case-insensitive).
     * Used during batch-inward from site to detect if the item already exists
     * and offer the user a choice: add qty to existing or create new.
     */
    Optional<InventoryItemEntity> findFirstByWarehouseIdAndNameIgnoreCase(Long warehouseId, String name);
}