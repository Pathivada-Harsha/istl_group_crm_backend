package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
 
@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, Long> {
    
    // Basic queries
    Optional<InvoiceEntity> findByInvoiceNo(String invoiceNo);
    
    // Get the last invoice number using native query
    @Query(value = "SELECT invoice_no FROM invoices " +
                   "WHERE invoice_no LIKE :prefix " +
                   "ORDER BY invoice_no DESC " +
                   "LIMIT 1", 
           nativeQuery = true)
    String findLastInvoiceNoByPrefix(@Param("prefix") String prefix);
    
    // Check if invoice number exists - returns Optional
    @Query("SELECT i FROM InvoiceEntity i WHERE i.invoiceNo = :invoiceNo")
    Optional<InvoiceEntity> findByInvoiceNoIncludingDeleted(@Param("invoiceNo") String invoiceNo);
    
    // ... rest of your existing queries ...
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.deletedAt IS NULL")
    Page<InvoiceEntity> findAllActive(Pageable pageable);
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupId(@Param("groupId") String groupId, Pageable pageable);
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.subGroupId = :subGroupId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupAndSubGroup(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        Pageable pageable
    );
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.projectId = :projectId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
    
    // User access queries
    @Query("SELECT i FROM InvoiceEntity i WHERE i.createdBy = :userId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.createdBy = :userId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupIdAndUserAccess(
        @Param("groupId") String groupId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.subGroupId = :subGroupId AND i.createdBy = :userId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT i FROM InvoiceEntity i WHERE i.projectId = :projectId AND i.createdBy = :userId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // ── Search + all-filters query — JPQL with subquery for customer name ────────
    // Using JPQL avoids @Transient field issues AND works with Pageable sorting.
    // Status is passed as UPPER-cased + underscore-replaced (e.g. 'PARTIALLY PAID').

    @Query("""
        SELECT i FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR i.projectId = :projectId)
        AND (:createdBy IS NULL OR i.createdBy = :createdBy)
        AND (:status IS NULL OR UPPER(i.status) = :status)
        AND (:fromDate IS NULL OR i.createdAt >= :fromDate)
        AND (:toDate IS NULL OR i.createdAt <= :toDate)
        AND (:searchTerm IS NULL OR :searchTerm = ''
             OR LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(COALESCE(i.invoiceNumber, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR i.customerId IN (
                 SELECT c.id FROM CustomersEntity c
                 WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                 OR LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             ))
    """)
    Page<InvoiceEntity> findFiltered(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("status") String status,
        @Param("searchTerm") String searchTerm,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );

    @Query("""
        SELECT i FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.projectId IN :projectIds
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:status IS NULL OR UPPER(i.status) = :status)
        AND (:fromDate IS NULL OR i.createdAt >= :fromDate)
        AND (:toDate IS NULL OR i.createdAt <= :toDate)
        AND (:searchTerm IS NULL OR :searchTerm = ''
             OR LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(COALESCE(i.invoiceNumber, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR i.customerId IN (
                 SELECT c.id FROM CustomersEntity c
                 WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                 OR LOWER(COALESCE(c.companyName, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             ))
    """)
    Page<InvoiceEntity> findFilteredAccessible(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("status") String status,
        @Param("searchTerm") String searchTerm,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
    
    // Statistics queries
    @Query("SELECT COUNT(i) FROM InvoiceEntity i WHERE i.deletedAt IS NULL")
    long countAll();
    
    @Query("SELECT COUNT(i) FROM InvoiceEntity i WHERE i.status = :status AND i.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT SUM(i.totalAmount) FROM InvoiceEntity i WHERE i.status = 'Paid' AND i.deletedAt IS NULL")
    BigDecimal sumPaidInvoices();
    
    @Query("SELECT SUM(i.balanceAmount) FROM InvoiceEntity i WHERE i.status IN ('Sent', 'Partially Paid') AND i.deletedAt IS NULL")
    BigDecimal sumPendingAmount();
    
    @Query("SELECT i FROM InvoiceEntity i " +
           "WHERE i.projectId = :projectId " +
           "AND i.deletedAt IS NULL " +
           "ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findByProjectIdAndDeletedAtIsNull(@Param("projectId") String projectId);
    
    // Filtered summary
    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR i.projectId = :projectId)
        AND (:createdBy IS NULL OR i.createdBy = :createdBy)
    """)
    long countFilteredInvoices(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );

    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.status = :status
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR i.projectId = :projectId)
        AND (:createdBy IS NULL OR i.createdBy = :createdBy)
    """)
    long countFilteredInvoicesByStatus(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("status") String status
    );

    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.status IN ('Sent', 'Partially Paid')
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR i.projectId = :projectId)
        AND (:createdBy IS NULL OR i.createdBy = :createdBy)
    """)
    long countFilteredPendingInvoices(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );

    @Query("""
        SELECT SUM(i.totalAmount) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
        AND (:projectId IS NULL OR i.projectId = :projectId)
        AND (:createdBy IS NULL OR i.createdBy = :createdBy)
    """)
    BigDecimal sumFilteredTotalAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy
    );
    
    // Unpaid invoices
    @Query("SELECT i FROM InvoiceEntity i " +
           "WHERE i.customerId = :customerId " +
           "AND i.status IN ('Sent', 'Partially Paid') " +
           "AND i.balanceAmount > 0 " +
           "AND i.deletedAt IS NULL " +
           "ORDER BY i.invoiceDate ASC")
    List<InvoiceEntity> findUnpaidInvoicesByCustomerId(@Param("customerId") Long customerId);
    
    @Query("SELECT i FROM InvoiceEntity i " +
           "WHERE i.projectId = :projectId " +
           "AND i.status IN ('Sent', 'Partially Paid') " +
           "AND i.balanceAmount > 0 " +
           "AND i.deletedAt IS NULL " +
           "ORDER BY i.invoiceDate ASC")
    List<InvoiceEntity> findUnpaidInvoicesByProjectId(@Param("projectId") String projectId);

    // ── Project-level aggregations for syncing projects table ────────────────

    @Query(value = "SELECT COALESCE(SUM(total_amount), 0) FROM invoices WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    BigDecimal sumTotalAmountByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COUNT(*) FROM invoices WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    Long countByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COALESCE(SUM(paid_amount), 0) FROM invoices WHERE project_id = :projectId AND deleted_at IS NULL", nativeQuery = true)
    BigDecimal sumPaidAmountByProjectId(@Param("projectId") String projectId);

    @Query(value = "SELECT COUNT(*) FROM invoices WHERE project_id = :projectId AND status = :status AND deleted_at IS NULL", nativeQuery = true)
    Long countByProjectIdAndStatus(@Param("projectId") String projectId, @Param("status") String status);

    @Query(value = "SELECT COALESCE(SUM(total_amount - paid_amount), 0) FROM invoices WHERE project_id = :projectId AND status IN ('Sent', 'Partially Paid') AND deleted_at IS NULL", nativeQuery = true)
    BigDecimal sumPendingAmountByProjectId(@Param("projectId") String projectId);

    // ── Accessible-project-IDs scoped queries (non-admin, no specific project selected) ──

    @Query("SELECT i FROM InvoiceEntity i WHERE i.projectId IN :projectIds AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByAccessibleProjects(@Param("projectIds") List<String> projectIds, Pageable pageable);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.projectId IN :projectIds AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupIdAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.subGroupId = :subGroupId AND i.projectId IN :projectIds AND i.deletedAt IS NULL")
    Page<InvoiceEntity> findByGroupSubGroupAndAccessibleProjects(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectIds") List<String> projectIds,
        Pageable pageable
    );

    // ── Accessible-project-IDs scoped KPI aggregate queries ──

    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.projectId IN :projectIds
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
    """)
    long countAccessibleInvoices(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.status = :status
        AND i.projectId IN :projectIds
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
    """)
    long countAccessibleInvoicesByStatus(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("status") String status
    );

    @Query("""
        SELECT COUNT(i) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.status IN ('Sent', 'Partially Paid')
        AND i.projectId IN :projectIds
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
    """)
    long countAccessiblePendingInvoices(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    @Query("""
        SELECT SUM(i.totalAmount) FROM InvoiceEntity i
        WHERE i.deletedAt IS NULL
        AND i.projectId IN :projectIds
        AND (:groupId IS NULL OR i.groupId = :groupId)
        AND (:subGroupId IS NULL OR i.subGroupId = :subGroupId)
    """)
    BigDecimal sumAccessibleTotalAmount(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId
    );

    // ── Universal KPI summary queries — native SQL with customers JOIN ──────────
    // Uses UPPER(status) for case-insensitive matching (DB has both 'Sent' and 'SENT').
    // The :searchTerm condition JOINs customers so partial customer-name search works.

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countSummaryFiltered(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND UPPER(i.status) = 'PAID' " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countSummaryPaid(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND UPPER(i.status) IN ('SENT', 'PARTIALLY PAID', 'PARTIALLY_PAID') " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countSummaryPending(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.total_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryTotalAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.paid_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryPaidAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.balance_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:projectId IS NULL OR i.project_id = :projectId) " +
        "AND (:createdBy IS NULL OR i.created_by = :createdBy) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumSummaryPendingAmount(
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("projectId") String projectId,
        @Param("createdBy") Long createdBy,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    // ── Accessible-projects variants with search/status support ─────────────────

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countAccessibleSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL AND UPPER(i.status) = 'PAID' " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countAccessiblePaidSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COUNT(*) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND UPPER(i.status) IN ('SENT', 'PARTIALLY PAID', 'PARTIALLY_PAID') " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    long countAccessiblePendingSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.total_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessibleTotalAmountV2(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.paid_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessiblePaidAmountSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    @Query(value =
        "SELECT COALESCE(SUM(i.balance_amount), 0) FROM invoices i " +
        "LEFT JOIN customers c ON i.customer_id = c.id " +
        "WHERE i.deleted_at IS NULL " +
        "AND i.project_id IN :projectIds " +
        "AND (:groupId IS NULL OR i.group_id = :groupId) " +
        "AND (:subGroupId IS NULL OR i.sub_group_id = :subGroupId) " +
        "AND (:statusFilter IS NULL OR UPPER(i.status) = UPPER(:statusFilter)) " +
        "AND (:searchTerm IS NULL OR :searchTerm = '' " +
        "     OR LOWER(i.invoice_no) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(i.invoice_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "     OR LOWER(COALESCE(c.company_name, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
        nativeQuery = true)
    BigDecimal sumAccessiblePendingAmountSummary(
        @Param("projectIds") List<String> projectIds,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("statusFilter") String statusFilter,
        @Param("searchTerm") String searchTerm
    );

    // =========================================================================
    // OUTSTANDINGS — no pagination, sorted by invoiceDate DESC (non-nullable)
    // Used exclusively by GET /invoices/outstandings
    // =========================================================================

    @Query("SELECT i FROM InvoiceEntity i WHERE i.projectId = :projectId AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsByProject(@Param("projectId") String projectId);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.subGroupId = :subGroupId AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsBySubGroup(@Param("groupId") String groupId, @Param("subGroupId") String subGroupId);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsByGroup(@Param("groupId") String groupId);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandings();

    @Query("SELECT i FROM InvoiceEntity i WHERE i.projectId IN :projectIds AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsByAccessibleProjects(@Param("projectIds") List<String> projectIds);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.projectId IN :projectIds AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsByGroupAndAccessibleProjects(@Param("groupId") String groupId, @Param("projectIds") List<String> projectIds);

    @Query("SELECT i FROM InvoiceEntity i WHERE i.groupId = :groupId AND i.subGroupId = :subGroupId AND i.projectId IN :projectIds AND i.deletedAt IS NULL ORDER BY i.invoiceDate DESC")
    List<InvoiceEntity> findAllForOutstandingsBySubGroupAndAccessibleProjects(@Param("groupId") String groupId, @Param("subGroupId") String subGroupId, @Param("projectIds") List<String> projectIds);

    // ── Batched roll-up for the Projects LIST ────────────────────────────────
    // (project_id, SUM(total_amount)) over live invoices — same filter as
    // findByProjectIdAndDeletedAtIsNull, done in one query for all projects.
    @Query(value = "SELECT project_id, COALESCE(SUM(total_amount), 0) FROM invoices "
                 + "WHERE deleted_at IS NULL AND project_id IS NOT NULL "
                 + "GROUP BY project_id", nativeQuery = true)
    List<Object[]> sumInvoiceValueGroupedByProject();
}