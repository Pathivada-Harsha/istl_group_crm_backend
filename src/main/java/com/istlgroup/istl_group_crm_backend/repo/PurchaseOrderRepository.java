package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, Long> {
    
    // ========== Basic Queries ==========
    
    Optional<PurchaseOrderEntity> findByPoNo(String poNo);
    
    List<PurchaseOrderEntity> findByDeletedAtIsNull();
    
    // ========== Project-based Filtering (Admin) ==========
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findAllActive(Pageable pageable);
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.groupName = :groupName AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByGroupName(@Param("groupName") String groupName, Pageable pageable);
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.groupName = :groupName AND po.subGroupName = :subGroupName AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        Pageable pageable
    );
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.projectId = :projectId AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
    
    // ========== Project-based Filtering (User) ==========
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE (po.createdBy = :userId OR po.approvedBy = :userId) AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.groupName = :groupName AND (po.createdBy = :userId OR po.approvedBy = :userId) AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByGroupNameAndUserAccess(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.groupName = :groupName AND po.subGroupName = :subGroupName AND (po.createdBy = :userId OR po.approvedBy = :userId) AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.projectId = :projectId AND (po.createdBy = :userId OR po.approvedBy = :userId) AND po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ========== Status-based Queries ==========
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.status = :status AND po.deletedAt IS NULL")
    List<PurchaseOrderEntity> findByStatus(@Param("status") String status);
    
    // ========== Vendor-based Queries ==========
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.vendorId = :vendorId AND po.deletedAt IS NULL ORDER BY po.orderDate DESC")
    List<PurchaseOrderEntity> findByVendorIdOrderByOrderDateDesc(@Param("vendorId") Long vendorId);
    
    // ========== Quotation-based ==========
    
    Optional<PurchaseOrderEntity> findByQuotationId(Long quotationId);
    
    // ========== Search ==========
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE " +
           "(LOWER(po.poNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(po.rfqId) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "po.deletedAt IS NULL")
    Page<PurchaseOrderEntity> search(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    // ========== Statistics ==========
    
    @Query("SELECT COUNT(po) FROM PurchaseOrderEntity po WHERE po.deletedAt IS NULL")
    long countActivePOs();
    
    @Query("SELECT COUNT(po) FROM PurchaseOrderEntity po WHERE po.status = :status AND po.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT SUM(po.totalValue) FROM PurchaseOrderEntity po WHERE po.deletedAt IS NULL")
    Double getTotalPOValue();
    
    
    
    
    /**
     * Get dropdown list of POs with vendor names
     */
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.deletedAt IS NULL ORDER BY po.orderDate DESC")
    List<PurchaseOrderEntity> findAllForDropdown();
    
    /**
     * Get POs filtered by group/subgroup/project for dropdown
     */
    @Query("SELECT po FROM PurchaseOrderEntity po " +
           "WHERE po.deletedAt IS NULL " +
           "AND (:groupName IS NULL OR po.groupName = :groupName) " +
           "AND (:subGroupName IS NULL OR po.subGroupName = :subGroupName) " +
           "AND (:projectId IS NULL OR po.projectId = :projectId) " +
           "ORDER BY po.orderDate DESC")
    List<PurchaseOrderEntity> findAllForDropdownFiltered(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId
    );
    
    /**
     * Find POs by vendor
     */
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.vendorId = :vendorId AND po.deletedAt IS NULL")
    List<PurchaseOrderEntity> findByVendorId(@Param("vendorId") Long vendorId);
}