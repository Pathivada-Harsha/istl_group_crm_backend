package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.BillPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillPaymentRepository extends JpaRepository<BillPaymentEntity, Long> {

    // Find all bill_payments rows written by VendorAdvanceService for a given advance number
    // referenceNumber is set to the advance no (VADV-xxxx or VPAY-xxxx) by applyAmountToBill()
    List<BillPaymentEntity> findByReferenceNumber(String referenceNumber);

    // Find bill_payments rows for a specific bill — used to clean up on advance delete
    @Query("SELECT bp FROM BillPaymentEntity bp WHERE bp.bill.id = :billId AND bp.referenceNumber = :referenceNumber")
    List<BillPaymentEntity> findByBillIdAndReferenceNumber(
            @Param("billId") Long billId,
            @Param("referenceNumber") String referenceNumber);

    // Hard-delete all payment history rows for a given advance number across all bills
    @Modifying
    @Query("DELETE FROM BillPaymentEntity bp WHERE bp.referenceNumber = :referenceNumber")
    void deleteByReferenceNumber(@Param("referenceNumber") String referenceNumber);

    // Find payments by receipt/invoice id
    @Query("SELECT bp FROM BillPaymentEntity bp WHERE bp.bill.id = :billId")
    List<BillPaymentEntity> findByBillId(@Param("billId") Long billId);

    // Sum ALL bill payments for a project (joins via bill.projectId)
    @Query(value = "SELECT COALESCE(SUM(bp.amount), 0) FROM bill_payments bp " +
                   "INNER JOIN bills b ON bp.bill_id = b.id " +
                   "WHERE b.project_id = :projectId AND b.deleted_at IS NULL",
           nativeQuery = true)
    java.math.BigDecimal sumPaymentAmountByProjectId(@Param("projectId") String projectId);

    // Sum ONLY direct bill payments (from Bills page) — excludes rows written by
    // VendorAdvanceService.applyAmountToBill() which have reference_number = VADV-xxxx
    // or VPAY-xxxx. Those amounts are already counted via vendor_advances table.
    @Query(value = "SELECT COALESCE(SUM(bp.amount), 0) FROM bill_payments bp " +
                   "INNER JOIN bills b ON bp.bill_id = b.id " +
                   "WHERE b.project_id = :projectId AND b.deleted_at IS NULL " +
                   "AND (bp.reference_number IS NULL " +
                   "     OR (bp.reference_number NOT LIKE 'VADV-%' " +
                   "         AND bp.reference_number NOT LIKE 'VPAY-%'))",
           nativeQuery = true)
    java.math.BigDecimal sumDirectPaymentAmountByProjectId(@Param("projectId") String projectId);
}