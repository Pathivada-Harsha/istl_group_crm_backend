package com.istlgroup.istl_group_crm_backend.repo;
 
import com.istlgroup.istl_group_crm_backend.entity.VendorAdvanceEntity;
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
public interface VendorAdvanceRepository extends JpaRepository<VendorAdvanceEntity, Long> {
 
    // ── List / filter ────────────────────────────────────────────────────────
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "(:vendorId IS NULL OR v.vendorId = :vendorId) AND " +
           "(:paymentType IS NULL OR v.paymentType = :paymentType) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' " +
           " OR LOWER(v.advanceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))" +
           " OR EXISTS (SELECT vn FROM VendorEntity vn WHERE vn.id = v.vendorId AND LOWER(vn.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:paymentDateFrom IS NULL OR v.advanceDate >= :paymentDateFrom) AND " +
           "(:paymentDateTo IS NULL OR v.advanceDate <= :paymentDateTo) AND " +
           "v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findAllWithFilters(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("vendorId") Long vendorId,
        @Param("paymentType") String paymentType,
        @Param("searchTerm") String searchTerm,
        @Param("paymentDateFrom") java.time.LocalDate paymentDateFrom,
        @Param("paymentDateTo") java.time.LocalDate paymentDateTo,
        Pageable pageable
    );

    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.deletedAt IS NULL ORDER BY v.advanceDate DESC")
    Page<VendorAdvanceEntity> findAllActive(Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.projectId = :projectId AND v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findByProjectId(@Param("projectId") String projectId, Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.groupId = :groupId AND v.subGroupId = :subGroupId AND v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findByGroupAndSubGroup(@Param("groupId") String groupId, @Param("subGroupId") String subGroupId, Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.groupId = :groupId AND v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findByGroupId(@Param("groupId") String groupId, Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE " +
           "(:paymentType IS NULL OR v.paymentType = :paymentType) AND " +
           "(:vendorId IS NULL OR v.vendorId = :vendorId) AND " +
           "v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findWithFilters(
        @Param("paymentType") String paymentType,
        @Param("vendorId") Long vendorId,
        Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE " +
           "v.projectId = :projectId AND " +
           "(:paymentType IS NULL OR v.paymentType = :paymentType) AND " +
           "(:vendorId IS NULL OR v.vendorId = :vendorId) AND " +
           "v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> findByProjectIdWithFilters(
        @Param("projectId") String projectId,
        @Param("paymentType") String paymentType,
        @Param("vendorId") Long vendorId,
        Pageable pageable);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE " +
           "(LOWER(v.advanceNo) LIKE LOWER(CONCAT('%',:q,'%')) OR " +
           "CAST(v.vendorId AS string) LIKE LOWER(CONCAT('%',:q,'%')) OR " +
           "EXISTS (SELECT vn FROM VendorEntity vn WHERE vn.id = v.vendorId AND LOWER(vn.name) LIKE LOWER(CONCAT('%',:q,'%')))) AND " +
           "v.deletedAt IS NULL")
    Page<VendorAdvanceEntity> searchAdvances(@Param("q") String query, Pageable pageable);
 
    // ── Unapplied advances for a vendor ────────────────────────────────────
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE " +
           "v.vendorId = :vendorId AND v.paymentType = 'ADVANCE' AND " +
           "v.appliedAmount < v.amount AND v.deletedAt IS NULL " +
           "ORDER BY v.advanceDate ASC")
    List<VendorAdvanceEntity> findUnappliedAdvancesByVendor(@Param("vendorId") Long vendorId);
 
    // ── Numbering ────────────────────────────────────────────────────────────
    // Uses numeric ordering: extracts the 4-digit suffix, finds the MAX integer value
    // Includes soft-deleted rows so gaps from deletions don't cause duplicate key errors
 
    @Query("SELECT v.advanceNo FROM VendorAdvanceEntity v " +
           "WHERE v.advanceNo LIKE :prefix " +
           "ORDER BY CAST(SUBSTRING(v.advanceNo, LENGTH(:barePrefix) + 1) AS integer) DESC " +
           "LIMIT 1")
    String findLastAdvanceNoByPrefix(@Param("prefix") String prefix,
                                     @Param("barePrefix") String barePrefix);
 
    Optional<VendorAdvanceEntity> findByAdvanceNo(String advanceNo);
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.advanceNo = :advanceNo")
    Optional<VendorAdvanceEntity> findByAdvanceNoIncludingDeleted(@Param("advanceNo") String advanceNo);
 
    // ── Summary stats ────────────────────────────────────────────────────────
 
    @Query("SELECT COUNT(v) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "v.deletedAt IS NULL")
    long countFiltered(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId);
 
    @Query("SELECT COALESCE(SUM(v.amount), 0) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "v.deletedAt IS NULL")
    BigDecimal sumTotalAmount(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId);
 
    @Query("SELECT COALESCE(SUM(v.appliedAmount), 0) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "v.deletedAt IS NULL")
    BigDecimal sumAppliedAmount(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId);
 
    // ── Soft-deleted list ────────────────────────────────────────────────────
 
    @Query("SELECT v FROM VendorAdvanceEntity v WHERE v.deletedAt IS NOT NULL ORDER BY v.deletedAt DESC")
    List<VendorAdvanceEntity> findDeleted();

    // Sum of ALL vendor advance/payment amounts for a project
    // Covers both ADVANCE and BILL_PAYMENT types — all cash paid out
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM vendor_advances "
                 + "WHERE project_id = :projectId AND deleted_at IS NULL",
           nativeQuery = true)
    java.math.BigDecimal sumAdvanceAmountByProjectId(@Param("projectId") String projectId);

    // ── Filter-aware summary queries (paymentType + searchTerm) ──

    @Query("SELECT COUNT(v) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "(:paymentTypeFilter IS NULL OR v.paymentType = :paymentTypeFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' " +
           " OR LOWER(v.advanceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))" +
           " OR EXISTS (SELECT vn FROM VendorEntity vn WHERE vn.id = v.vendorId AND LOWER(vn.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:paymentDateFrom IS NULL OR v.advanceDate >= :paymentDateFrom) AND " +
           "(:paymentDateTo IS NULL OR v.advanceDate <= :paymentDateTo) AND " +
           "v.deletedAt IS NULL")
    long countFilteredWithOptions(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("paymentTypeFilter") String paymentTypeFilter,
        @Param("searchTerm") String searchTerm,
        @Param("paymentDateFrom") java.time.LocalDate paymentDateFrom,
        @Param("paymentDateTo") java.time.LocalDate paymentDateTo
    );

    @Query("SELECT COALESCE(SUM(v.amount), 0) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "(:paymentTypeFilter IS NULL OR v.paymentType = :paymentTypeFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' " +
           " OR LOWER(v.advanceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))" +
           " OR EXISTS (SELECT vn FROM VendorEntity vn WHERE vn.id = v.vendorId AND LOWER(vn.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:paymentDateFrom IS NULL OR v.advanceDate >= :paymentDateFrom) AND " +
           "(:paymentDateTo IS NULL OR v.advanceDate <= :paymentDateTo) AND " +
           "v.deletedAt IS NULL")
    BigDecimal sumTotalAmountWithOptions(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("paymentTypeFilter") String paymentTypeFilter,
        @Param("searchTerm") String searchTerm,
        @Param("paymentDateFrom") java.time.LocalDate paymentDateFrom,
        @Param("paymentDateTo") java.time.LocalDate paymentDateTo
    );

    @Query("SELECT COALESCE(SUM(v.appliedAmount), 0) FROM VendorAdvanceEntity v WHERE " +
           "(:projectId IS NULL OR v.projectId = :projectId) AND " +
           "(:groupId IS NULL OR v.groupId = :groupId) AND " +
           "(:subGroupId IS NULL OR v.subGroupId = :subGroupId) AND " +
           "(:paymentTypeFilter IS NULL OR v.paymentType = :paymentTypeFilter) AND " +
           "(:searchTerm IS NULL OR :searchTerm = '' " +
           " OR LOWER(v.advanceNo) LIKE LOWER(CONCAT('%', :searchTerm, '%'))" +
           " OR EXISTS (SELECT vn FROM VendorEntity vn WHERE vn.id = v.vendorId AND LOWER(vn.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) AND " +
           "(:paymentDateFrom IS NULL OR v.advanceDate >= :paymentDateFrom) AND " +
           "(:paymentDateTo IS NULL OR v.advanceDate <= :paymentDateTo) AND " +
           "v.deletedAt IS NULL")
    BigDecimal sumAppliedAmountWithOptions(
        @Param("projectId") String projectId,
        @Param("groupId") String groupId,
        @Param("subGroupId") String subGroupId,
        @Param("paymentTypeFilter") String paymentTypeFilter,
        @Param("searchTerm") String searchTerm,
        @Param("paymentDateFrom") java.time.LocalDate paymentDateFrom,
        @Param("paymentDateTo") java.time.LocalDate paymentDateTo
    );

    // ── Batched roll-up for the Projects LIST ────────────────────────────────
    // (project_id, SUM(amount)) — batched twin of sumAdvanceAmountByProjectId.
    @Query(value = "SELECT project_id, COALESCE(SUM(amount), 0) FROM vendor_advances "
                 + "WHERE deleted_at IS NULL AND project_id IS NOT NULL "
                 + "GROUP BY project_id", nativeQuery = true)
    List<Object[]> sumAdvanceAmountGroupedByProject();
}