package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
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
public interface BillRepository extends JpaRepository<BillEntity, Long> {
    
    Optional<BillEntity> findByBillNo(String billNo);
    
    @Query("SELECT b FROM BillEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BillEntity> findByIdAndNotDeleted(@Param("id") Long id);
    
    // =========================================
    // ROLE-BASED QUERIES
    // =========================================
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findAllWithFilters(
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            @Param("search") String search,
            @Param("billDateFrom") java.time.LocalDate billDateFrom,
            @Param("billDateTo") java.time.LocalDate billDateTo,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.projectId = :projectId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findByProjectIdWithFilters(
            @Param("projectId") String projectId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            @Param("search") String search,
            @Param("billDateFrom") java.time.LocalDate billDateFrom,
            @Param("billDateTo") java.time.LocalDate billDateTo,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.groupId = :groupId AND " +
           "b.subGroupId = :subGroupId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findBySubGroupWithFilters(
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            @Param("search") String search,
            @Param("billDateFrom") java.time.LocalDate billDateFrom,
            @Param("billDateTo") java.time.LocalDate billDateTo,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.groupId = :groupId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findByGroupWithFilters(
            @Param("groupId") String groupId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            @Param("search") String search,
            @Param("billDateFrom") java.time.LocalDate billDateFrom,
            @Param("billDateTo") java.time.LocalDate billDateTo,
            Pageable pageable);
    
    // =========================================
    // STATISTICS QUERIES
    // =========================================
    
    @Query("SELECT COUNT(b) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.deletedAt IS NULL")
    long countBills(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);
    
    @Query("SELECT COALESCE(SUM(b.totalAmount - b.paidAmount), 0) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.status != 'Paid' AND " +
           "b.deletedAt IS NULL")
    BigDecimal sumOutstandingAmount(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);
    
    @Query("SELECT COUNT(b) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.billDate >= :startOfMonth AND " +
           "b.billDate <= :endOfMonth AND " +
           "b.deletedAt IS NULL")
    long countBillsThisMonth(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId,
            @Param("startOfMonth") LocalDate startOfMonth,
            @Param("endOfMonth") LocalDate endOfMonth);
    
    @Query("SELECT COUNT(b) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.status = 'Paid' AND " +
           "b.deletedAt IS NULL")
    long countPaidBills(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);
    
    @Query("SELECT COUNT(b) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.poId IS NOT NULL AND " +
           "b.deletedAt IS NULL")
    long countLinkedToPO(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.deletedAt IS NULL")
    BigDecimal sumTotalAmount(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "b.deletedAt IS NULL")
    BigDecimal sumPaidAmount(
            @Param("projectId") String projectId,
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId);
    
    // =========================================
    // UTILITY QUERIES
    // =========================================
    
    @Query("SELECT b FROM BillEntity b WHERE b.poId = :poId AND b.deletedAt IS NULL")
    List<BillEntity> findByPoId(@Param("poId") Long poId);
    
    @Query("SELECT b FROM BillEntity b WHERE b.vendorId = :vendorId AND b.deletedAt IS NULL")
    List<BillEntity> findByVendorId(@Param("vendorId") Long vendorId);
    
    boolean existsByBillNo(String billNo);
    
    @Query("SELECT MAX(b.billNo) FROM BillEntity b WHERE b.billNo LIKE :prefix")
    String findMaxBillNoWithPrefix(@Param("prefix") String prefix);
    
    @Query(value = "SELECT COUNT(*) FROM bills WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    Long countByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COUNT(*) FROM bills WHERE project_id = :projectId AND status = :status AND deleted_at IS NULL", nativeQuery = true)
    Long countByProjectIdAndStatus(@Param("projectId") String projectId, @Param("status") String status);

    @Query(value = "SELECT COALESCE(SUM(total_amount), 0) FROM bills WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    Optional<BigDecimal> sumTotalAmountByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COALESCE(SUM(paid_amount), 0) FROM bills WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    Optional<BigDecimal> sumPaidAmountByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COALESCE(SUM(total_amount - paid_amount), 0) FROM bills WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    Optional<BigDecimal> sumBalanceAmountByProjectId(@Param("projectId") String projectId);

    @Query("SELECT COUNT(b) FROM BillEntity b WHERE b.projectId = :projectId AND b.dueDate < CURRENT_DATE AND b.status != 'Paid' AND b.deletedAt IS NULL")
    Long countOverdueBillsByProjectId(@Param("projectId") String projectId);

    @Query("SELECT COALESCE(SUM(b.balanceAmount), 0) FROM BillEntity b WHERE b.projectId = :projectId AND b.dueDate < CURRENT_DATE AND b.status != 'Paid' AND b.deletedAt IS NULL")
    Optional<BigDecimal> sumOverdueAmountByProjectId(@Param("projectId") String projectId);

    @Query("SELECT b FROM BillEntity b " +
    	       "WHERE b.projectId = :projectId " +
    	       "AND b.status != :status " +
    	       "AND b.deletedAt IS NULL " +
    	       "ORDER BY b.billDate DESC")
    	List<BillEntity> findByProjectIdAndStatusNot(
    	    @Param("projectId") String projectId, 
    	    @Param("status") String status
    	);

    // ── Accessible-project-IDs scoped queries ──

    @Query("SELECT b FROM BillEntity b WHERE b.projectId IN :projectIds AND b.deletedAt IS NULL")
    Page<BillEntity> findByAccessibleProjects(@Param("projectIds") List<String> projectIds, Pageable pageable);

    @Query("SELECT b FROM BillEntity b WHERE b.groupId = :groupId AND b.projectId IN :projectIds AND b.deletedAt IS NULL")
    Page<BillEntity> findByGroupIdAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    @Query("SELECT b FROM BillEntity b WHERE b.groupId = :groupId AND b.subGroupId = :subGroupId AND b.projectId IN :projectIds AND b.deletedAt IS NULL")
    Page<BillEntity> findByGroupSubGroupAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    @Query("SELECT b FROM BillEntity b WHERE b.createdBy = :userId AND b.deletedAt IS NULL")
    Page<BillEntity> findByCreatedBy(@Param("userId") Long userId, Pageable pageable);

    // ── Accessible-project-IDs scoped KPI aggregate queries ──

    @Query("SELECT COUNT(b) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId)")
    long countAccessibleBills(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId)")
    BigDecimal sumAccessibleTotalAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId)")
    BigDecimal sumAccessiblePaidAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    // ── Status-aware KPI queries (for filter-reactive KPI cards) ──

    @Query("SELECT COUNT(b) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "(:statusFilter IS NULL OR b.status = :statusFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    long countBillsWithStatus(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "(:statusFilter IS NULL OR b.status = :statusFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    BigDecimal sumTotalAmountWithStatus(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM BillEntity b WHERE " +
           "(:projectId IS NULL OR b.projectId = :projectId) AND " +
           "(:groupId IS NULL OR b.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR b.subGroupId = :subGroupId) AND " +
           "(:statusFilter IS NULL OR b.status = :statusFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo) AND " +
           "b.deletedAt IS NULL")
    BigDecimal sumPaidAmountWithStatus(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    @Query("SELECT COUNT(b) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId) " +
           "AND (:statusFilter IS NULL OR b.status = :statusFilter) " +
           "AND (:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo)")
    long countAccessibleBillsWithStatus(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId) " +
           "AND (:statusFilter IS NULL OR b.status = :statusFilter) " +
           "AND (:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo)")
    BigDecimal sumAccessibleTotalAmountWithStatus(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM BillEntity b WHERE b.deletedAt IS NULL AND b.projectId IN :projectIds " +
           "AND (:groupId IS NULL OR b.groupId = :groupId) AND (:subGroupId IS NULL OR b.subGroupId = :subGroupId) " +
           "AND (:statusFilter IS NULL OR b.status = :statusFilter) " +
           "AND (:searchTerm IS NULL OR :searchTerm = '' OR LOWER(b.billNo) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(COALESCE(b.billRefId, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "EXISTS (SELECT v FROM VendorEntity v WHERE v.id = b.vendorId AND LOWER(v.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:billDateFrom IS NULL OR b.billDate >= :billDateFrom) AND " +
           "(:billDateTo IS NULL OR b.billDate <= :billDateTo)")
    BigDecimal sumAccessiblePaidAmountWithStatus(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm,
        @Param("billDateFrom") java.time.LocalDate billDateFrom,
        @Param("billDateTo") java.time.LocalDate billDateTo
    );

    // =========================================
    // DEDICATED QUERIES FOR VENDOR PAYMENTS MODAL
    // Used ONLY by VendorAdvanceController /payable-bills endpoint.
    // Completely isolated from the Bills Received page — changes here
    // will never affect the bills listing, and vice-versa.
    // =========================================

    /**
     * Returns Pending + Partially Paid bills for a specific vendor scoped to
     * a specific project. Used by the "Payment Against Bill" picker and
     * the "Allocate Advance" modal in Vendor Payments.
     */
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.vendorId = :vendorId AND " +
           "b.projectId = :projectId AND " +
           "b.status IN ('Pending', 'Partially Paid') AND " +
           "b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findPayableBillsForVendorAndProject(
            @Param("vendorId") Long vendorId,
            @Param("projectId") String projectId);

    /**
     * Same as above but without projectId — fallback for advances with no
     * project scoping (legacy or cross-project advances).
     */
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.vendorId = :vendorId AND " +
           "b.status IN ('Pending', 'Partially Paid') AND " +
           "b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findPayableBillsForVendor(
            @Param("vendorId") Long vendorId);

    /**
     * Finds warehouse-sourced bills whose invTxnRef contains the given txnNo.
     * Used when deleting an OUTWARD transaction to cascade-delete its auto-bill.
     * invTxnRef may contain multiple txn numbers comma-separated (batch outward).
     */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.sourceType = 'WAREHOUSE' " +
           "  AND b.deletedAt IS NULL " +
           "  AND b.invTxnRef LIKE CONCAT('%', :txnNo, '%')")
    List<BillEntity> findWarehouseBillsByTxnRef(@Param("txnNo") String txnNo);

    // =========================================================================
    // OUTSTANDINGS — no pagination, sorted by billDate DESC (non-nullable)
    // Used exclusively by GET /bills/outstandings
    // =========================================================================

    /** All non-deleted bills for a specific project, sorted by billDate DESC */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.projectId = :projectId AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsByProject(@Param("projectId") String projectId);

    /** All non-deleted bills scoped to group+subGroup, sorted by billDate DESC */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.groupId = :groupId AND b.subGroupId = :subGroupId AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsBySubGroup(@Param("groupId") String groupId,
                                                       @Param("subGroupId") String subGroupId);

    /** All non-deleted bills scoped to a group, sorted by billDate DESC */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.groupId = :groupId AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsByGroup(@Param("groupId") String groupId);

    /** All non-deleted bills (admin / no filter), sorted by billDate DESC */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandings();

    /** All non-deleted bills scoped to accessible project IDs, sorted by billDate DESC */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.projectId IN :projectIds AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsByAccessibleProjects(@Param("projectIds") List<String> projectIds);

    /** Accessible projects + group filter */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.groupId = :groupId AND b.projectId IN :projectIds AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsByGroupAndAccessibleProjects(@Param("groupId") String groupId,
                                                                         @Param("projectIds") List<String> projectIds);

    /** Accessible projects + group + subGroup filter */
    @Query("SELECT b FROM BillEntity b " +
           "WHERE b.groupId = :groupId AND b.subGroupId = :subGroupId AND b.projectId IN :projectIds AND b.deletedAt IS NULL " +
           "ORDER BY b.billDate DESC")
    List<BillEntity> findAllForOutstandingsBySubGroupAndAccessibleProjects(@Param("groupId") String groupId,
                                                                            @Param("subGroupId") String subGroupId,
                                                                            @Param("projectIds") List<String> projectIds);

    // ── Batched roll-up for the Projects LIST ────────────────────────────────
    // (project_id, SUM(total_amount)) over live, non-cancelled bills — same
    // filter as findByProjectIdAndStatusNot(projectId, "Cancelled").
    @Query(value = "SELECT project_id, COALESCE(SUM(total_amount), 0) FROM bills "
                 + "WHERE deleted_at IS NULL AND project_id IS NOT NULL "
                 + "AND (status IS NULL OR status <> 'Cancelled') "
                 + "GROUP BY project_id", nativeQuery = true)
    List<Object[]> sumBillValueGroupedByProject();
}