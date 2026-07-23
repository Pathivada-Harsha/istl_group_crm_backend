package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderEligibilityEntity;

public interface TenderEligibilityRepo extends JpaRepository<TenderEligibilityEntity, Long> {
    List<TenderEligibilityEntity> findByTenderIdOrderBySortNo(Long tenderId);
    void deleteByTenderId(Long tenderId);
}
