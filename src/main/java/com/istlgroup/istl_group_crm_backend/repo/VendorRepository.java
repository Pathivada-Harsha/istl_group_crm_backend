package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<VendorEntity, Long> {
    
    // ========== Basic Queries ==========
    
    Optional<VendorEntity> findByVendorCode(String vendorCode);
    
    Optional<VendorEntity> findByEmail(String email);
    
    List<VendorEntity> findByDeletedAtIsNull();
    
    // ========== Project-based Filtering (Admin) ==========
    // Only vendors we've placed POs with
    
    @Query("SELECT v FROM VendorEntity v WHERE v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findAllActiveVendors(Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupName(@Param("groupName") String groupName, Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.subGroupName = :subGroupName AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.projectId = :projectId AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
    
    // ========== Project-based Filtering (User) ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE (v.createdBy = :userId OR v.assignedTo = :userId) AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupNameAndUserAccess(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.subGroupName = :subGroupName AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.projectId = :projectId AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ========== Search ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "(LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(v.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(v.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> searchVendors(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "(LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(v.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(v.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "(v.createdBy = :userId OR v.assignedTo = :userId) AND " +
           "v.totalOrders > 0 AND v.deletedAt IS NULL")
    Page<VendorEntity> searchVendorsWithUserAccess(
        @Param("searchTerm") String searchTerm,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ========== Filter by Category/Type ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE v.category = :category AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    List<VendorEntity> findByCategory(@Param("category") String category);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.vendorType = :vendorType AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    List<VendorEntity> findByVendorType(@Param("vendorType") String vendorType);
    
    // ========== Statistics ==========
    
    @Query("SELECT COUNT(v) FROM VendorEntity v WHERE v.totalOrders > 0 AND v.deletedAt IS NULL")
    long countActiveVendors();
    
    @Query("SELECT COUNT(v) FROM VendorEntity v WHERE v.status = :status AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT AVG(v.rating) FROM VendorEntity v WHERE v.rating IS NOT NULL AND v.totalOrders > 0 AND v.deletedAt IS NULL")
    Double getAverageRating();
    
    @Query("SELECT SUM(v.totalPurchaseValue) FROM VendorEntity v WHERE v.totalOrders > 0 AND v.deletedAt IS NULL")
    Double getTotalPurchaseValue();
}