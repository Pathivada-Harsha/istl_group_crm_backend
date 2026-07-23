package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderDocRequestEntity;

public interface TenderDocRequestRepo extends JpaRepository<TenderDocRequestEntity, Long> {
    List<TenderDocRequestEntity> findByTenderIdOrderBySortNo(Long tenderId);
    void deleteByTenderId(Long tenderId);
}
