package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<VendorEntity, Long> {
    
    // ========== Basic Queries ==========
    
    Optional<VendorEntity> findByVendorCode(String vendorCode);
    
    Optional<VendorEntity> findByEmail(String email);
    
    List<VendorEntity> findByDeletedAtIsNull();
    
    boolean existsById(Long id);
    
    // ========== Project-based Filtering (Admin) ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE v.deletedAt IS NULL")
    Page<VendorEntity> findAllActiveVendors(Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupName(@Param("groupName") String groupName, Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.subGroupName = :subGroupName AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.projectId = :projectId AND v.deletedAt IS NULL")
    Page<VendorEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);

    // ========== Project-OR-PO Queries (show vendor if registered under project OR has POs there) ==========

    /**
     * Find vendors that are either registered under the given project_id,
     * OR have at least one active purchase order under that project.
     * Used in Vendor Management page so vendors don't disappear just because
     * their registration project_id differs from where they actually supplied.
     */
    @Query("SELECT DISTINCT v FROM VendorEntity v " +
           "WHERE v.deletedAt IS NULL " +
           "AND (v.projectId = :projectId " +
           "     OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "                WHERE po.vendorId = v.id " +
           "                AND po.projectId = :projectId " +
           "                AND po.deletedAt IS NULL))")
    Page<VendorEntity> findByProjectIdOrHasPOs(
        @Param("projectId") String projectId,
        Pageable pageable
    );

    /**
     * Same as above, with additional category/status filters.
     */
    @Query("SELECT DISTINCT v FROM VendorEntity v " +
           "WHERE v.deletedAt IS NULL " +
           "AND (:category IS NULL OR v.category = :category) " +
           "AND (:status IS NULL OR v.status = :status) " +
           "AND (:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) " +
           "AND (:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) " +
           "AND (v.projectId = :projectId " +
           "     OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "                WHERE po.vendorId = v.id " +
           "                AND po.projectId = :projectId " +
           "                AND po.deletedAt IS NULL))")
    Page<VendorEntity> findByProjectIdAndFiltersOrHasPOs(
        @Param("projectId") String projectId,
        @Param("category") String category,
        @Param("status") String status,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );

    /**
     * Non-admin variant — also scoped to user access.
     */
    @Query("SELECT DISTINCT v FROM VendorEntity v " +
           "WHERE v.deletedAt IS NULL " +
           "AND (v.createdBy = :userId OR v.assignedTo = :userId) " +
           "AND (v.projectId = :projectId " +
           "     OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "                WHERE po.vendorId = v.id " +
           "                AND po.projectId = :projectId " +
           "                AND po.deletedAt IS NULL))")
    Page<VendorEntity> findByProjectIdAndUserAccessOrHasPOs(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ========== Project-based Filtering (User) ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE (v.createdBy = :userId OR v.assignedTo = :userId) AND v.deletedAt IS NULL")
    Page<VendorEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupNameAndUserAccess(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.subGroupName = :subGroupName AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE v.projectId = :projectId AND (v.createdBy = :userId OR v.assignedTo = :userId) AND v.deletedAt IS NULL")
    Page<VendorEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ========== Combined Filters (Admin) ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> findByFilters(
        @Param("category") String category,
        @Param("status") String status,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "v.groupName = :groupName AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupNameAndFilters(
        @Param("groupName") String groupName,
        @Param("category") String category,
        @Param("status") String status,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "v.groupName = :groupName AND " +
           "v.subGroupName = :subGroupName AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> findByGroupSubGroupAndFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("category") String category,
        @Param("status") String status,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );
    
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "v.projectId = :projectId AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> findByProjectIdAndFilters(
        @Param("projectId") String projectId,
        @Param("category") String category,
        @Param("status") String status,
        Pageable pageable
    );
    
    // ========== Search ==========

    /**
     * Search across all text fields AND apply status / category / group / project filters.
     * Used for admin users. Replaces the old searchVendors that ignored all other filters.
     */
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "(LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> searchVendors(
        @Param("searchTerm") String searchTerm,
        @Param("category") String category,
        @Param("status") String status,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );

    /**
     * Same as searchVendors but scoped to vendors the user created or is assigned to.
     * Used for non-admin users.
     */
    @Query("SELECT v FROM VendorEntity v WHERE " +
           "(LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(v.createdBy = :userId OR v.assignedTo = :userId) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorEntity> searchVendorsWithUserAccess(
        @Param("searchTerm") String searchTerm,
        @Param("category") String category,
        @Param("status") String status,
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );
    
    // ========== Filter by Category/Type ==========
    
    @Query("SELECT v FROM VendorEntity v WHERE v.category = :category AND v.deletedAt IS NULL")
    List<VendorEntity> findByCategory(@Param("category") String category);
    
    @Query("SELECT v FROM VendorEntity v WHERE v.vendorType = :vendorType AND v.deletedAt IS NULL")
    List<VendorEntity> findByVendorType(@Param("vendorType") String vendorType);
    
    // ========== Statistics Queries - Dynamic based on filters ==========
    
    @Query("SELECT COUNT(v) FROM VendorEntity v WHERE v.deletedAt IS NULL")
    long countActiveVendors();
    
    /**
     * Count vendors that are registered under the project OR have POs under it.
     */
    @Query("SELECT COUNT(DISTINCT v) FROM VendorEntity v WHERE " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId " +
           "   OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "              WHERE po.vendorId = v.id AND po.projectId = :projectId AND po.deletedAt IS NULL)) AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:searchTerm IS NULL OR " +
           "   LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    long countByFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("status") String status,
        @Param("category") String category,
        @Param("searchTerm") String searchTerm,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo
    );
    
    /**
     * Count active vendors registered under the project OR with POs under it.
     * Also respects the statusFilter so KPI is consistent with the filtered vendor list.
     */
    @Query("SELECT COUNT(DISTINCT v) FROM VendorEntity v WHERE " +
           "v.status = 'Active' AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId " +
           "   OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "              WHERE po.vendorId = v.id AND po.projectId = :projectId AND po.deletedAt IS NULL)) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:searchTerm IS NULL OR " +
           "   LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    long countActiveByFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("status") String status,
        @Param("category") String category,
        @Param("searchTerm") String searchTerm,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo
    );
    
    /**
     * Average rating for vendors registered under the project OR with POs under it.
     */
    @Query("SELECT AVG(v.rating) FROM VendorEntity v WHERE " +
           "v.rating IS NOT NULL AND v.rating > 0 AND " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId " +
           "   OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "              WHERE po.vendorId = v.id AND po.projectId = :projectId AND po.deletedAt IS NULL)) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:searchTerm IS NULL OR " +
           "   LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Double getAverageRatingByFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("status") String status,
        @Param("category") String category,
        @Param("searchTerm") String searchTerm,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo
    );
    
    /**
     * Total purchase value for vendors registered under the project OR with POs under it.
     */
    @Query("SELECT SUM(v.totalPurchaseValue) FROM VendorEntity v WHERE " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:groupName IS NULL OR v.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR v.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR v.projectId = :projectId " +
           "   OR EXISTS (SELECT 1 FROM PurchaseOrderEntity po " +
           "              WHERE po.vendorId = v.id AND po.projectId = :projectId AND po.deletedAt IS NULL)) AND " +
           "(:category IS NULL OR v.category = :category) AND " +
           "(:searchTerm IS NULL OR " +
           "   LOWER(v.name) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.email,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.phone,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.contactPerson,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.vendorCode,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.gstNumber,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.city,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%')) OR " +
           "   LOWER(COALESCE(v.state,'')) LIKE LOWER(CONCAT('%',:searchTerm,'%'))) AND " +
           "(:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) AND " +
           "(:createdAtTo IS NULL OR v.createdAt <= :createdAtTo) AND " +
           "v.deletedAt IS NULL")
    Double getTotalPurchaseValueByFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId,
        @Param("status") String status,
        @Param("category") String category,
        @Param("searchTerm") String searchTerm,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo
    );
    
    // Get count of pending quotations for vendors
    @Query("SELECT COUNT(DISTINCT q.id) FROM QuotationEntity q WHERE " +
           "q.status IN ('New', 'Under Review') AND " +
           "(:groupName IS NULL OR q.groupName = :groupName) AND " +
           "(:subGroupName IS NULL OR q.subGroupName = :subGroupName) AND " +
           "(:projectId IS NULL OR q.projectId = :projectId) AND " +
           "q.deletedAt IS NULL")
    long countPendingQuotationsByFilters(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectId") String projectId
    );
    
    
    
    /**
     * Find vendors by created_by and assigned_to for dropdown
     * Returns id, name, and phone for dropdown selection
     */
    @Query("SELECT new map(v.id as id, v.name as name, v.phone as phone) FROM VendorEntity v " +
            "WHERE v.deletedAt IS NULL " +
            "AND (v.createdBy = :userId OR v.assignedTo = :userId) " +
            "ORDER BY v.name ASC")
    List<Map<String, Object>> findVendorsByUserIdForDropdown(@Param("userId") Long userId);
     
    /**
     * Find vendors who have submitted quotations for other projects
     * Useful for suggesting vendors with quotation history
     * COMMENTED OUT FOR NOW - NOT BEING USED
     */
    /*
    @Query("SELECT DISTINCT new map(v.id as id, v.name as name, v.phone as phone, COUNT(q.id) as quotationCount) " +
           "FROM VendorEntity v " +
           "INNER JOIN QuotationEntity q ON q.vendorId = v.id " +
           "WHERE v.deletedAt IS NULL " +
           "AND (v.createdBy = :userId OR v.assignedTo = :userId) " +
           "AND q.projectId != :currentProjectId " +
           "GROUP BY v.id, v.name, v.phone " +
           "ORDER BY COUNT(q.id) DESC, v.name ASC")
    List<Map<String, Object>> findVendorsWithQuotationHistoryForDropdown(
            @Param("userId") Long userId, 
            @Param("currentProjectId") String currentProjectId);
    */
    
    
    
    
   

    @Query("SELECT COUNT(v) FROM VendorEntity v WHERE v.projectId = :projectId AND v.status = :status AND v.deletedAt IS NULL")
    Long countByProjectIdAndStatus(@Param("projectId") String projectId, @Param("status") String status);

    @Query("SELECT COALESCE(SUM(v.totalPurchaseValue), 0) FROM VendorEntity v WHERE v.projectId = :projectId AND v.deletedAt IS NULL")
    Optional<BigDecimal> sumTotalPurchaseValueByProjectId(@Param("projectId") String projectId);

    @Query("SELECT AVG(v.rating) FROM VendorEntity v WHERE v.projectId = :projectId AND v.rating IS NOT NULL AND v.rating > 0 AND v.deletedAt IS NULL")
    Optional<Double> avgRatingByProjectId(@Param("projectId") String projectId);

    @Query("SELECT COALESCE(SUM(v.totalOrders), 0) FROM VendorEntity v WHERE v.projectId = :projectId AND v.deletedAt IS NULL")
    Optional<Integer> sumTotalOrdersByProjectId(@Param("projectId") String projectId);
    
    /**
     * Count vendors by project
     */
    @Query("SELECT COUNT(v) FROM VendorEntity v " +
           "WHERE v.projectId = :projectId AND v.deletedAt IS NULL")
    Long countByProjectId(@Param("projectId") String projectId);
    
    /**
     * Get average vendor rating for a project
     */
    @Query("SELECT AVG(v.rating) FROM VendorEntity v " +
           "WHERE v.projectId = :projectId AND v.rating IS NOT NULL " +
           "AND v.deletedAt IS NULL")
    Double getAverageRatingByProjectId(@Param("projectId") String projectId);
    
    /**
     * Find top 5 vendors by total purchase value
     */
    @Query("SELECT v FROM VendorEntity v " +
           "WHERE v.projectId = :projectId AND v.deletedAt IS NULL " +
           "ORDER BY v.lastPurchaseAmount DESC")
    List<VendorEntity> findTop5ByProjectIdOrderByTotalPurchaseValueDesc(@Param("projectId") String projectId);

	List<VendorEntity> findByProjectId(String projectId);

	List<VendorEntity> findByGroupNameAndSubGroupName(String groupName, String subGroupName);

	List<VendorEntity> findByGroupName(String groupName);
	Optional<VendorEntity> findByName(String name);

	Optional<VendorEntity> findByPhone(String vendorContact);

    // ── List-returning helpers for project-scoped vendor lookups ──

    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.deletedAt IS NULL")
    List<VendorEntity> findByGroupNameList(@Param("groupName") String groupName);

    @Query("SELECT v FROM VendorEntity v WHERE v.groupName = :groupName AND v.subGroupName = :subGroupName AND v.deletedAt IS NULL")
    List<VendorEntity> findByGroupNameAndSubGroupNameList(@Param("groupName") String groupName, @Param("subGroupName") String subGroupName);

    // ── Accessible vendor IDs filter ──

    @Query("SELECT v FROM VendorEntity v WHERE v.id IN :vendorIds AND v.deletedAt IS NULL " +
           "AND (:categoryFilter IS NULL OR v.category = :categoryFilter) " +
           "AND (:statusFilter IS NULL OR v.status = :statusFilter) " +
           "AND (:createdAtFrom IS NULL OR v.createdAt >= :createdAtFrom) " +
           "AND (:createdAtTo IS NULL OR v.createdAt <= :createdAtTo)")
    Page<VendorEntity> findByIdsAndFilters(
        @Param("vendorIds") List<Long> vendorIds,
        @Param("categoryFilter") String categoryFilter,
        @Param("statusFilter") String statusFilter,
        @Param("createdAtFrom") java.time.LocalDateTime createdAtFrom,
        @Param("createdAtTo") java.time.LocalDateTime createdAtTo,
        Pageable pageable
    );

    // ── Vendors created by OR assigned to a user (List, for union in getVendors) ──
    // Used to ensure users always see vendors they registered, even before any PO is raised.
    @Query("SELECT v FROM VendorEntity v WHERE (v.createdBy = :userId OR v.assignedTo = :userId) AND v.deletedAt IS NULL")
    List<VendorEntity> findByCreatedByOrAssignedToAndDeletedAtIsNull(@Param("userId") Long userId);
}