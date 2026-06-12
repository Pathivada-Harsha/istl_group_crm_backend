package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.QuotationItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuotationItemRepository extends JpaRepository<QuotationItemEntity, Long> {
    
    // Find all items for a quotation
    @Query("SELECT qi FROM QuotationItemEntity qi WHERE qi.quotation.id = :quotationId ORDER BY qi.lineNo")
    List<QuotationItemEntity> findByQuotationId(@Param("quotationId") Long quotationId);
    
    // Delete all items for a quotation
    void deleteByQuotationId(Long quotationId);
}