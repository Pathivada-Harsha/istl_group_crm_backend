package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvBillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvBillRepository extends JpaRepository<InvBillEntity, Long> {

    List<InvBillEntity> findByPoId(Long poId);

    @Query(
        "SELECT b FROM InvBillEntity b " +
        "WHERE (:warehouseId  IS NULL OR b.warehouseId  = :warehouseId) " +
        "  AND (:groupName    IS NULL OR :groupName    = '' OR b.groupName    = :groupName) " +
        "  AND (:subGroupName IS NULL OR :subGroupName = '' OR b.subGroupName = :subGroupName) " +
        "  AND (:projectId    IS NULL OR :projectId    = '' OR b.projectId    = :projectId) " +
        "  AND (:status       IS NULL OR :status       = '' OR b.status       = :status) " +
        "  AND (:vendorId     IS NULL OR b.vendorId    = :vendorId) " +
        "  AND (:search IS NULL OR :search = '' " +
        "       OR LOWER(b.billNo)       LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(b.vendorName)   LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY b.createdAt DESC"
    )
    Page<InvBillEntity> findFiltered(
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