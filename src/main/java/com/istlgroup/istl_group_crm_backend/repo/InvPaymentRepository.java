package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvPaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface InvPaymentRepository extends JpaRepository<InvPaymentEntity, Long> {

    List<InvPaymentEntity> findByBillId(Long billId);

    /** Returns all allocation rows created from a given advance (advance_id = advanceId). */
    List<InvPaymentEntity> findByAdvanceId(Long advanceId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM InvPaymentEntity p WHERE p.billId = :billId")
    BigDecimal sumAmountByBillId(@Param("billId") Long billId);

    /**
     * Filtered, paginated list of inventory payments.
     *
     * @param paymentMode  optional mode filter (Bank Transfer, UPI, etc.). NULL = all.
     * @param isAdvance    true  = advance payments only (billId IS NULL)
     *                     false = bill payments only  (billId IS NOT NULL)
     *                     null  = all
     */
    @Query(
        "SELECT p FROM InvPaymentEntity p " +
        "WHERE (:groupName    IS NULL OR :groupName    = '' OR p.groupName    = :groupName) " +
        "  AND (:subGroupName IS NULL OR :subGroupName = '' OR p.subGroupName = :subGroupName) " +
        "  AND (:projectId    IS NULL OR :projectId    = '' OR p.projectId    = :projectId) " +
        "  AND (:vendorId     IS NULL OR p.vendorId    = :vendorId) " +
        "  AND (:paymentMode  IS NULL OR :paymentMode  = '' OR p.paymentMode  = :paymentMode) " +
        "  AND (:isAdvance    IS NULL " +
        "       OR (:isAdvance = TRUE  AND p.billId IS NULL) " +
        "       OR (:isAdvance = FALSE AND p.billId IS NOT NULL)) " +
        "  AND (:search IS NULL OR :search = '' " +
        "       OR LOWER(p.paymentNo)       LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(p.vendorName)      LIKE LOWER(CONCAT('%', :search, '%')) " +
        "       OR LOWER(p.referenceNumber) LIKE LOWER(CONCAT('%', :search, '%'))) " +
        "ORDER BY p.createdAt DESC"
    )
    Page<InvPaymentEntity> findFiltered(
        @Param("groupName")    String  groupName,
        @Param("subGroupName") String  subGroupName,
        @Param("projectId")    String  projectId,
        @Param("vendorId")     Long    vendorId,
        @Param("paymentMode")  String  paymentMode,
        @Param("isAdvance")    Boolean isAdvance,
        @Param("search")       String  search,
        Pageable pageable
    );
}