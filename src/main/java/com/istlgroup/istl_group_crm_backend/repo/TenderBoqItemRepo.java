package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderBoqItemEntity;

public interface TenderBoqItemRepo extends JpaRepository<TenderBoqItemEntity, Long> {
    List<TenderBoqItemEntity> findByTenderIdOrderBySortNo(Long tenderId);
    void deleteByTenderId(Long tenderId);
}
