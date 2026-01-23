// InvoiceRepository.java
package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<InvoiceEntity, Long> {
    
    // Basic queries
    Optional<InvoiceEntity> findByInvoiceNo(String invoiceNo);
    
    // Count for invoice number generation
    @Query("SELECT COUNT(i) FROM InvoiceEntity i WHERE i.invoiceNo LIKE :prefix")
    long countByInvoiceNoPrefix(@Param("prefix") String prefix);
    
    // Admin queries - All invoices
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
    
    // Search queries
    @Query("SELECT i FROM InvoiceEntity i WHERE " +
           "(LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "i.deletedAt IS NULL")
    Page<InvoiceEntity> searchInvoices(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    @Query("SELECT i FROM InvoiceEntity i WHERE " +
           "(LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "i.createdBy = :userId AND i.deletedAt IS NULL")
    Page<InvoiceEntity> searchInvoicesWithUserAccess(
        @Param("searchTerm") String searchTerm,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    // Statistics queries - CHANGED: Now using String status instead of enum
    @Query("SELECT COUNT(i) FROM InvoiceEntity i WHERE i.deletedAt IS NULL")
    long countAll();
    
    @Query("SELECT COUNT(i) FROM InvoiceEntity i WHERE i.status = :status AND i.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT SUM(i.totalAmount) FROM InvoiceEntity i WHERE i.status = 'Paid' AND i.deletedAt IS NULL")
    BigDecimal sumPaidInvoices();
    
    @Query("SELECT SUM(i.balanceAmount) FROM InvoiceEntity i WHERE i.status IN ('Sent', 'Partially Paid') AND i.deletedAt IS NULL")
    BigDecimal sumPendingAmount();

    /**
     * Find invoices by project (not deleted)
     */
    @Query("SELECT inv FROM InvoiceEntity inv " +
           "WHERE inv.projectId = :projectId " +
           "AND inv.deletedAt IS NULL " +
           "ORDER BY inv.invoiceDate DESC")
    List<InvoiceEntity> findByProjectIdAndDeletedAtIsNull(@Param("projectId") String projectId);
}