package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.WarehouseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseRepository extends JpaRepository<WarehouseEntity, Long> {

    Optional<WarehouseEntity> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Scoped read for the inventory page:
     *
     *   - For each non-blank filter (group, sub-group) we keep a warehouse
     *     only when it either matches the filter OR has NULL in that field
     *     (i.e. it's "global").
     *   - If `onlyActive` is true, INACTIVE rows are excluded.
     *
     * Warehouses are deliberately NOT scoped by project — projects are an
     * item-level concept.
     */
    @Query(
        "SELECT w FROM WarehouseEntity w " +
        "WHERE (:groupName    IS NULL OR :groupName    = '' OR w.groupName    IS NULL OR w.groupName    = :groupName) " +
        "  AND (:subGroupName IS NULL OR :subGroupName = '' OR w.subGroupName IS NULL OR w.subGroupName = :subGroupName) " +
        "  AND (:onlyActive   = FALSE OR w.isActive = TRUE) " +
        "ORDER BY w.name ASC"
    )
    List<WarehouseEntity> findScoped(
        @Param("groupName")    String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("onlyActive")   boolean onlyActive
    );

    /**
     * Paginated admin search — used by the Dropdown Management page.
     * Searches across code, name, city, and in_charge. Admin sees all rows
     * (including INACTIVE) so they can re-activate or hard-delete.
     */
    @Query(
        "SELECT w FROM WarehouseEntity w " +
        "WHERE (:search IS NULL OR :search = '' " +
        "       OR LOWER(w.code)     LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(w.name)     LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(w.city)     LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(w.inCharge) LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY w.id DESC"
    )
    Page<WarehouseEntity> searchPaged(@Param("search") String search, Pageable pageable);
}