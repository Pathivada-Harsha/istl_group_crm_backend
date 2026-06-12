package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationRepository extends JpaRepository<QuotationEntity, Long> {
    
    // ========== Basic Queries ==========
    
    Optional<QuotationEntity> findByQuoteNo(String quoteNo);
    
    List<QuotationEntity> findByDeletedAtIsNull();
    
    // ========== Type-based Queries ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = :type AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByType(@Param("type") String type, Pageable pageable);
    
    // ========== Project-based Filtering (Admin) ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findAllProcurement(
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupName(
        @Param("groupName") String groupName,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.subGroupName = :subGroupName AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
 // Find approved quotations without PO
    List<QuotationEntity> findByStatusAndPoIdIsNullAndDeletedAtIsNullOrderByUploadedAtDesc(String status);

    // Find all procurement quotations available for PO creation (New, Shortlisted, Approved)
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.status NOT IN ('Rejected', 'Expired', 'PO Created') AND q.deletedAt IS NULL ORDER BY q.uploadedAt DESC")
    List<QuotationEntity> findAvailableForPO();
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.projectId = :projectId AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByProjectId(
        @Param("projectId") String projectId,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    // ========== Project-based Filtering (User) ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND (q.preparedBy = :userId) AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByUserAccess(
        @Param("userId") Long userId,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND (q.preparedBy = :userId) AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupNameAndUserAccess(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.subGroupName = :subGroupName AND (q.preparedBy = :userId) AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("userId") Long userId,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.projectId = :projectId AND (q.preparedBy = :userId) AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
    
    // ========== Status-based Queries ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.status = :status AND q.deletedAt IS NULL")
    List<QuotationEntity> findByStatus(@Param("status") String status);
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.validTill < :date AND q.status NOT IN ('Expired', 'Approved', 'Rejected') AND q.deletedAt IS NULL")
    List<QuotationEntity> findExpiredQuotations(@Param("date") LocalDate date);
    
    // ========== Vendor-based Queries ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.vendorId = :vendorId AND q.deletedAt IS NULL ORDER BY q.uploadedAt DESC")
    List<QuotationEntity> findByVendorId(@Param("vendorId") Long vendorId);
    
    // ========== Search ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND (" +
           "LOWER(q.quoteNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.rfqId) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.vendorName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = q.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) AND " +
           "(:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo) AND " +
           "q.deletedAt IS NULL")
    Page<QuotationEntity> searchProcurement(
        @Param("searchTerm") String searchTerm,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );

    /**
     * Same search query as searchProcurement but returns all matching records (no pagination).
     * Used by getStatistics so KPI counts use the same vendor-JOIN logic as the list endpoint.
     */
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND (" +
           "LOWER(q.quoteNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.rfqId) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.vendorName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = q.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "q.deletedAt IS NULL")
    List<QuotationEntity> searchProcurementList(@Param("searchTerm") String searchTerm);
    
    // ========== Statistics ==========
    
    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.deletedAt IS NULL")
    long countProcurementQuotations();
    
    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.status = :status AND q.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    
    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.projectId = :projectId AND q.deletedAt IS NULL")
    Long countByProjectId(@Param("projectId") String projectId);

    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.projectId = :projectId AND q.status = :status AND q.deletedAt IS NULL")
    Long countByProjectIdAndStatus(@Param("projectId") String projectId, @Param("status") String status);

    @Query("SELECT COALESCE(SUM(q.totalValue), 0) FROM QuotationEntity q WHERE q.projectId = :projectId AND q.deletedAt IS NULL")
    Optional<BigDecimal> sumTotalValueByProjectId(@Param("projectId") String projectId);

    @Query("SELECT COALESCE(SUM(q.totalValue), 0) FROM QuotationEntity q WHERE q.projectId = :projectId AND q.status = :status AND q.deletedAt IS NULL")
    Optional<BigDecimal> sumTotalValueByProjectIdAndStatus(@Param("projectId") String projectId, @Param("status") String status);

    @Query("SELECT AVG(q.totalValue) FROM QuotationEntity q WHERE q.projectId = :projectId AND q.deletedAt IS NULL")
    Optional<BigDecimal> avgTotalValueByProjectId(@Param("projectId") String projectId);
    
    /**
     * Count quotations by project and group by status
     */
    @Query("SELECT q.status, COUNT(q) FROM QuotationEntity q " +
           "WHERE q.projectId = :projectId AND q.deletedAt IS NULL " +
           "GROUP BY q.status")
    List<Object[]> countByProjectIdAndGroupByStatus(@Param("projectId") String projectId);
    
    /**
     * Find top 5 recent quotations for a project
     */
    @Query("SELECT q FROM QuotationEntity q " +
           "WHERE q.projectId = :projectId AND q.deletedAt IS NULL " +
           "ORDER BY q.uploadedAt DESC")
    List<QuotationEntity> findTop5ByProjectIdOrderByUploadedAtDesc(@Param("projectId") String projectId);
    
    
 // ADD THESE METHODS TO YOUR QuotationRepository.java

 // ========== STATS FILTERING QUERIES - ADD THESE ==========

 // All quotations by type (no user filter)
 List<QuotationEntity> findByTypeAndDeletedAtIsNull(String type);

 // By type and user (for non-admin users)
 List<QuotationEntity> findByTypeAndPreparedByAndDeletedAtIsNull(String type, Long preparedBy);

 // By type and group
 List<QuotationEntity> findByTypeAndGroupNameAndDeletedAtIsNull(String type, String groupName);

 // By type, group and user
 List<QuotationEntity> findByTypeAndGroupNameAndPreparedByAndDeletedAtIsNull(
     String type, String groupName, Long preparedBy);

 // By type, group and subgroup
 List<QuotationEntity> findByTypeAndGroupNameAndSubGroupNameAndDeletedAtIsNull(
     String type, String groupName, String subGroupName);

 // By type, group, subgroup and user
 List<QuotationEntity> findByTypeAndGroupNameAndSubGroupNameAndPreparedByAndDeletedAtIsNull(
     String type, String groupName, String subGroupName, Long preparedBy);

 // By type and project
 List<QuotationEntity> findByTypeAndProjectIdAndDeletedAtIsNull(String type, String projectId);

 // By type, project and user
 List<QuotationEntity> findByTypeAndProjectIdAndPreparedByAndDeletedAtIsNull(
     String type, String projectId, Long preparedBy);
 
 @Query("""
		    SELECT q.quoteNo 
		    FROM QuotationEntity q
		    WHERE q.quoteNo LIKE :prefix%
		      AND q.deletedAt IS NULL
		    ORDER BY q.quoteNo DESC
		""")
		List<String> findLastQuoteNoByPrefix(@Param("prefix") String prefix, Pageable pageable);
 @Query("""
		    SELECT q.quoteNo
		    FROM QuotationEntity q
		    WHERE q.quoteNo LIKE CONCAT('QUO-', :year, '-%')
		      AND q.deletedAt IS NULL
		    ORDER BY LENGTH(q.quoteNo) DESC, q.quoteNo DESC
		""")
		List<String> findLastQuoteNoByYear(
		        @Param("year") int year,
		        Pageable pageable
		);

    // ── Accessible-project-IDs scoped queries ──

    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.projectId IN :projectIds AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByAccessibleProjects(
        @Param("projectIds") List<String> projectIds,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );

    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.projectId IN :projectIds AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupNameAndAccessibleProjects(
        @Param("groupName") String groupName,
        @Param("projectIds") List<String> projectIds,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );

    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.subGroupName = :subGroupName AND q.projectId IN :projectIds AND q.deletedAt IS NULL " +
           "AND (:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) " +
           "AND (:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo)")
    Page<QuotationEntity> findByGroupSubGroupAndAccessibleProjects(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("projectIds") List<String> projectIds,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );

    // ── Accessible-project-IDs scoped search query ───────────────────────────
    // Used by non-admin users when a search term is active.
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND " +
           "q.projectId IN :projectIds AND (" +
           "LOWER(q.quoteNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.rfqId) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(q.vendorName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = q.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:uploadedFrom IS NULL OR q.uploadedAt >= :uploadedFrom) AND " +
           "(:uploadedTo IS NULL OR q.uploadedAt <= :uploadedTo) AND " +
           "q.deletedAt IS NULL")
    Page<QuotationEntity> searchProcurementWithProjectIds(
        @Param("searchTerm") String searchTerm,
        @Param("projectIds") List<String> projectIds,
        @Param("uploadedFrom") java.time.LocalDateTime uploadedFrom,
        @Param("uploadedTo") java.time.LocalDateTime uploadedTo,
        Pageable pageable
    );
}