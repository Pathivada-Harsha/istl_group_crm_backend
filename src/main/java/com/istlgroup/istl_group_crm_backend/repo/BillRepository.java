package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillRepository extends JpaRepository<BillEntity, Long> {
    
    Optional<BillEntity> findByBillNo(String billNo);
    
    @Query("SELECT b FROM BillEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BillEntity> findByIdAndNotDeleted(@Param("id") Long id);
    
    // =========================================
    // ROLE-BASED QUERIES (String status instead of ENUM)
    // =========================================
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findAllWithFilters(
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.projectId = :projectId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findByProjectIdWithFilters(
            @Param("projectId") String projectId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.groupId = :groupId AND " +
           "b.subGroupId = :subGroupId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findBySubGroupWithFilters(
            @Param("groupId") String groupId,
            @Param("subGroupId") String subGroupId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
            Pageable pageable);
    
    @Query("SELECT b FROM BillEntity b WHERE " +
           "b.groupId = :groupId AND " +
           "(:status IS NULL OR :status = 'all' OR b.status = :status) AND " +
           "(:vendorId IS NULL OR b.vendorId = :vendorId) AND " +
           "(:poId IS NULL OR b.poId = :poId) AND " +
           "b.deletedAt IS NULL")
    Page<BillEntity> findByGroupWithFilters(
            @Param("groupId") String groupId,
            @Param("status") String status,
            @Param("vendorId") Long vendorId,
            @Param("poId") Long poId,
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
    java.math.BigDecimal sumOutstandingAmount(
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
            @Param("startOfMonth") LocalDateTime startOfMonth,
            @Param("endOfMonth") LocalDateTime endOfMonth);
    
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
}