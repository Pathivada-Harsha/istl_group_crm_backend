// InvoiceItemRepository.java
package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItemEntity, Long> {
    
    @Query("SELECT ii FROM InvoiceItemEntity ii WHERE ii.invoice.id = :invoiceId")
    List<InvoiceItemEntity> findByInvoiceId(@Param("invoiceId") Long invoiceId);
    
    void deleteByInvoiceId(Long invoiceId);
}