// AdvanceAllocationRepository.java
package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.AdvanceAllocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdvanceAllocationRepository extends JpaRepository<AdvanceAllocationEntity, Long> {
    
    List<AdvanceAllocationEntity> findByReceiptIdOrderByAllocationDateDesc(Long receiptId);
    
    List<AdvanceAllocationEntity> findByInvoiceIdOrderByAllocationDateDesc(Long invoiceId);
    
    @Query("SELECT aa FROM AdvanceAllocationEntity aa " +
           "WHERE aa.receipt.id = :receiptId " +
           "ORDER BY aa.allocationDate DESC")
    List<AdvanceAllocationEntity> findAllocationsByReceipt(@Param("receiptId") Long receiptId);
    
    @Query("SELECT aa FROM AdvanceAllocationEntity aa " +
           "WHERE aa.invoiceId = :invoiceId " +
           "ORDER BY aa.allocationDate DESC")
    List<AdvanceAllocationEntity> findAllocationsByInvoice(@Param("invoiceId") Long invoiceId);
    
 // Add to AdvanceAllocationRepository.java

    Optional<AdvanceAllocationEntity> findByReceiptIdAndInvoiceId(Long receiptId, Long invoiceId);
    
    List<AdvanceAllocationEntity> findByReceiptId(Long receiptId);

    @Modifying
    @Query("DELETE FROM AdvanceAllocationEntity a WHERE a.receipt.id = :receiptId AND a.invoiceId = :invoiceId")
    void deleteByReceiptIdAndInvoiceId(@Param("receiptId") Long receiptId, @Param("invoiceId") Long invoiceId);
}