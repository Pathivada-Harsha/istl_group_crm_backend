package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InvPurchaseOrderRepository extends JpaRepository<InvPurchaseOrderEntity, Long> {

    boolean existsByPoNo(String poNo);

    @Query(
        "SELECT p FROM InvPurchaseOrderEntity p " +
        "WHERE p.deletedAt IS NULL " +
        "  AND (:warehouseId  IS NULL OR p.warehouseId  = :warehouseId) " +
        "  AND (:groupName    IS NULL OR :groupName    = '' OR p.groupName    = :groupName) " +
        "  AND (:subGroupName IS NULL OR :subGroupName = '' OR p.subGroupName = :subGroupName) " +
        "  AND (:projectId    IS NULL OR :projectId    = '' OR p.projectId    = :projectId) " +
        "  AND (:status       IS NULL OR :status       = '' OR p.status       = :status) " +
        "  AND (:vendorId     IS NULL OR p.vendorId    = :vendorId) " +
        "  AND (:search IS NULL OR :search = '' " +
        "       OR LOWER(p.poNo)        LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(p.vendorName)  LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY p.createdAt DESC"
    )
    Page<InvPurchaseOrderEntity> findFiltered(
        @Param("warehouseId")  Long    warehouseId,
        @Param("groupName")    String  groupName,
        @Param("subGroupName") String  subGroupName,
        @Param("projectId")    String  projectId,
        @Param("status")       String  status,
        @Param("vendorId")     Long    vendorId,
        @Param("search")       String  search,
        Pageable pageable
    );
}