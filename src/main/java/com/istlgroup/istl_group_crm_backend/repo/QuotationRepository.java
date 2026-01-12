package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.deletedAt IS NULL")
    Page<QuotationEntity> findAllProcurement(Pageable pageable);
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByGroupName(@Param("groupName") String groupName, Pageable pageable);
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.subGroupName = :subGroupName AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByGroupAndSubGroup(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        Pageable pageable
    );
 // Find approved quotations without PO
    List<QuotationEntity> findByStatusAndPoIdIsNullAndDeletedAtIsNullOrderByUploadedAtDesc(String status);
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.projectId = :projectId AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
    
    // ========== Project-based Filtering (User) ==========
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND (q.preparedBy = :userId) AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByUserAccess(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND (q.preparedBy = :userId) AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByGroupNameAndUserAccess(
        @Param("groupName") String groupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.groupName = :groupName AND q.subGroupName = :subGroupName AND (q.preparedBy = :userId) AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByGroupSubGroupAndUserAccess(
        @Param("groupName") String groupName,
        @Param("subGroupName") String subGroupName,
        @Param("userId") Long userId,
        Pageable pageable
    );
    
    @Query("SELECT q FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.projectId = :projectId AND (q.preparedBy = :userId) AND q.deletedAt IS NULL")
    Page<QuotationEntity> findByProjectIdAndUserAccess(
        @Param("projectId") String projectId,
        @Param("userId") Long userId,
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
           "LOWER(q.rfqId) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) AND " +
           "q.deletedAt IS NULL")
    Page<QuotationEntity> searchProcurement(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    // ========== Statistics ==========
    
    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.deletedAt IS NULL")
    long countProcurementQuotations();
    
    @Query("SELECT COUNT(q) FROM QuotationEntity q WHERE q.type = 'Procurement' AND q.status = :status AND q.deletedAt IS NULL")
    long countByStatus(@Param("status") String status);
    
    


}