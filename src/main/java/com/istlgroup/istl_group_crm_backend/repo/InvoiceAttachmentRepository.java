package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.InvoiceAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceAttachmentRepository extends JpaRepository<InvoiceAttachmentEntity, Long> {

    /** Get the latest attachment for a given invoice (most recent upload wins) */
    @Query("SELECT a FROM InvoiceAttachmentEntity a WHERE a.invoiceId = :invoiceId ORDER BY a.uploadedAt DESC")
    Optional<InvoiceAttachmentEntity> findLatestByInvoiceId(@Param("invoiceId") Long invoiceId);

    /** Check if an invoice already has an attachment */
    boolean existsByInvoiceId(Long invoiceId);

    /** Delete existing attachment for an invoice before replacing it */
    void deleteByInvoiceId(Long invoiceId); 
}