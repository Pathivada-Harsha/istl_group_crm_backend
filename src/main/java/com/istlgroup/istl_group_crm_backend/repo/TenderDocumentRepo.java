package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderDocumentEntity;

public interface TenderDocumentRepo extends JpaRepository<TenderDocumentEntity, Long> {
    List<TenderDocumentEntity> findByTenderIdOrderBySortNo(Long tenderId);
    void deleteByTenderId(Long tenderId);
}
