package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ReceiptEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReceiptRepository extends JpaRepository<ReceiptEntity, Long> {
    
    Optional<ReceiptEntity> findByReceiptNo(String receiptNo);
    
    // Get the last receipt number using native query
    @Query(value = "SELECT receipt_no FROM receipts " +
                   "WHERE receipt_no LIKE :prefix " +
                   "ORDER BY receipt_no DESC " +
                   "LIMIT 1", 
           nativeQuery = true)
    String findLastReceiptNoByPrefix(@Param("prefix") String prefix);
    
    // Check if receipt number exists - returns Optional
    @Query("SELECT r FROM ReceiptEntity r WHERE r.receiptNo = :receiptNo")
    Optional<ReceiptEntity> findByReceiptNoIncludingDeleted(@Param("receiptNo") String receiptNo);
    
    // Admin queries
    @Query("SELECT r FROM ReceiptEntity r WHERE r.deletedAt IS NULL")
    Page<ReceiptEntity> findAllActive(Pageable pageable);
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.projectId = :projectId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupId(@Param("groupId") String groupId, Pageable pageable);
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.subGroupId = :subGroupId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupAndSubGroup(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        Pageable pageable
    );
    
    // User access queries
    @Query("SELECT r FROM ReceiptEntity r WHERE r.createdBy = :userId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.projectId = :projectId AND r.createdBy = :userId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ── Search + all-filters query — JPQL with subquery for customer name ────────
    // Using JPQL avoids @Transient field issues AND works with Pageable sorting.

    @Query("""
        SELECT r FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
        AND (:receiptType IS NULL OR r.receiptType = :receiptType)
        AND (:paymentMethod IS NULL OR r.paymentMethod = :paymentMethod)
        AND (:fromDate IS NULL OR r.createdAt >= :fromDate)
        AND (:toDate IS NULL OR r.createdAt <= :toDate)
        AND (:searchTerm IS NULL OR :searchTerm = ''
             OR LOWER(r.receiptNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR r.customerId IN (
                 SELECT c.id FROM CustomersEntity c
                 WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                 OR LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             ))
    """)
    Page<ReceiptEntity> findFiltered(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("receiptType") String receiptType,
        @Param("paymentMethod") String paymentMethod,
        @Param("searchTerm") String searchTerm,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );

    @Query("""
        SELECT r FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:receiptType IS NULL OR r.receiptType = :receiptType)
        AND (:paymentMethod IS NULL OR r.paymentMethod = :paymentMethod)
        AND (:fromDate IS NULL OR r.createdAt >= :fromDate)
        AND (:toDate IS NULL OR r.createdAt <= :toDate)
        AND (:searchTerm IS NULL OR :searchTerm = ''
             OR LOWER(r.receiptNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR r.customerId IN (
                 SELECT c.id FROM CustomersEntity c
                 WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                 OR LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             ))
    """)
    Page<ReceiptEntity> findFilteredAccessible(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("receiptType") String receiptType,
        @Param("paymentMethod") String paymentMethod,
        @Param("searchTerm") String searchTerm,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    // Find unapplied advances for a customer
    @Query("SELECT r FROM ReceiptEntity r WHERE " +
           "r.customerId = :customerId AND " +
           "r.receiptType = 'ADVANCE' AND " +
           "r.unappliedAmount > 0 AND " +
           "r.deletedAt IS NULL " +
           "ORDER BY r.receiptDate ASC")
    List<ReceiptEntity> findUnappliedAdvancesByCustomer(@Param("customerId") Long customerId);
    
    // Statistics
    @Query("SELECT COUNT(r) FROM ReceiptEntity r WHERE r.deletedAt IS NULL")
    long countAll();
    
    @Query("SELECT COUNT(r) FROM ReceiptEntity r WHERE r.receiptType = :type AND r.deletedAt IS NULL")
    long countByType(@Param("type") String type);
    
    @Query("SELECT SUM(r.amount) FROM ReceiptEntity r WHERE r.deletedAt IS NULL")
    BigDecimal sumTotalAmount();
    
    @Query("SELECT SUM(r.appliedAmount) FROM ReceiptEntity r WHERE r.deletedAt IS NULL")
    BigDecimal sumAppliedAmount();
    
    @Query("SELECT SUM(r.unappliedAmount) FROM ReceiptEntity r WHERE r.receiptType = 'ADVANCE' AND r.deletedAt IS NULL")
    BigDecimal sumUnappliedAmount();
    
    // Filtered summary queries
    @Query("""
        SELECT COUNT(r) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
    """)
    long countFilteredReceipts(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );
    
    @Query("""
        SELECT SUM(r.amount) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
    """)
    BigDecimal sumFilteredTotalAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );
    
    // Deleted receipts
    @Query("SELECT r FROM ReceiptEntity r WHERE r.deletedAt IS NOT NULL ORDER BY r.deletedAt DESC")
    List<ReceiptEntity> findDeletedReceipts();
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.deletedAt IS NOT NULL AND r.createdBy = :userId ORDER BY r.deletedAt DESC")
    List<ReceiptEntity> findDeletedReceiptsByUser(@Param("userId") Long userId);
    
    @Query("SELECT r FROM ReceiptEntity r WHERE r.id = :id")
    Optional<ReceiptEntity> findByIdIncludingDeleted(@Param("id") Long id);

    // Non-admin filtered queries (respects group/subGroup/project AND createdBy)
    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.subGroupId = :subGroupId AND r.createdBy = :userId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupAndSubGroupAndUserAccess(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("userId") Long userId,
        Pageable pageable
    );

    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.createdBy = :userId AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupIdAndUserAccess(
        @Param("groupId") String groupId,
        @Param("userId") Long userId,
        Pageable pageable
    );

        // Sum ALL receipt amounts for a project (ADVANCE + INVOICE_PAYMENT)
    // Single source of truth for project.paid_invoice_value
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM receipts WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    BigDecimal sumReceiptAmountByProjectId(@Param("projectId") String projectId);

    // Sum receipts for a project filtered by receipt_type (for breakdown: ADVANCE vs INVOICE_PAYMENT)
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM receipts WHERE project_id = :projectId AND receipt_type = :receiptType AND deleted_at IS NULL", nativeQuery = true)
    BigDecimal sumReceiptAmountByProjectIdAndType(@Param("projectId") String projectId, @Param("receiptType") String receiptType);

    // Filtered applied amount - respects group/subGroup/project filter for KPI cards
    @Query("""
        SELECT COALESCE(SUM(r.appliedAmount), 0) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
    """)
    BigDecimal sumFilteredAppliedAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );

    // Filtered unapplied amount - only ADVANCE type receipts have unapplied amounts
    @Query("""
        SELECT COALESCE(SUM(r.unappliedAmount), 0) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.receiptType = 'ADVANCE'
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
    """)
    BigDecimal sumFilteredUnappliedAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );

    // Filtered count by receipt type
    @Query("""
        SELECT COUNT(r) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.receiptType = :type
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR r.projectId = :projectId)
        AND (:createdBy IS NULL OR r.createdBy = :createdBy)
    """)
    long countFilteredByType(
        @Param("type") String type,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );

    // ── Accessible-project-IDs scoped queries ──

    @Query("SELECT r FROM ReceiptEntity r WHERE r.projectId IN :projectIds AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByAccessibleProjects(@Param("projectIds") List<String> projectIds, Pageable pageable);

    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.projectId IN :projectIds AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupIdAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    @Query("SELECT r FROM ReceiptEntity r WHERE r.groupId = :groupId AND r.subGroupId = :subGroupId AND r.projectId IN :projectIds AND r.deletedAt IS NULL")
    Page<ReceiptEntity> findByGroupSubGroupAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    // ── Accessible-project-IDs scoped KPI aggregate queries ──

    @Query("""
        SELECT COUNT(r) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
    """)
    long countAccessibleReceipts(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT COUNT(r) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.receiptType = :type
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
    """)
    long countAccessibleByType(
        @Param("type") String type,
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT SUM(r.amount) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
    """)
    BigDecimal sumAccessibleTotalAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT COALESCE(SUM(r.appliedAmount), 0) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
    """)
    BigDecimal sumAccessibleAppliedAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT COALESCE(SUM(r.unappliedAmount), 0) FROM ReceiptEntity r
        WHERE r.deletedAt IS NULL
        AND r.receiptType = 'ADVANCE'
        AND r.projectId IN :projectIds
        AND (:groupId IS NULL OR r.groupId = :groupId)
        AND (:subGroupId IS NULL OR r.subGroupId = :subGroupId)
    """)
    BigDecimal sumAccessibleUnappliedAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );


    // ── Universal summary queries — native SQL with customers JOIN ──────────────

    @Query(value =
        "SELECT COUNT(*) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR r.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR r.created_by = :createdBy) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countSummaryFiltered(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR r.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR r.created_by = :createdBy) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryTotalAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.applied_amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR r.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR r.created_by = :createdBy) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryAppliedAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.unapplied_amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL AND r.receipt_type = 'ADVANCE' " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR r.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR r.created_by = :createdBy) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryUnappliedAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    // ── Accessible-projects variants with search/filter support ─────────────────

    @Query(value =
        "SELECT COUNT(*) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL AND r.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countAccessibleSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL AND r.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessibleTotalAmountV2(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.applied_amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL AND r.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessibleAppliedAmountV2(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(r.unapplied_amount), 0) FROM receipts r " +
        "LEFT JOIN customers c ON r.customer_id = c.id " +
        "WHERE r.deleted_at IS NULL AND r.receipt_type = 'ADVANCE' AND r.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR r.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR r.sub_group_id = :subGroupId) " +
        "AND (:receiptTypeFilter IS NULL OR r.receipt_type = :receiptTypeFilter) " +
        "AND (:paymentMethodFilter IS NULL OR r.payment_method = :paymentMethodFilter) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(r.receipt_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessibleUnappliedAmountV2(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("receiptTypeFilter") String receiptTypeFilter,
        @Param("paymentMethodFilter") String paymentMethodFilter,
        @Param("searchTerm") String searchTerm
    );
}