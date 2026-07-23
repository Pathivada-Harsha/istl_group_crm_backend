package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.TenderApprovalLogEntity;

public interface TenderApprovalLogRepo extends JpaRepository<TenderApprovalLogEntity, Long> {
    List<TenderApprovalLogEntity> findByTenderIdOrderByCreatedAtDesc(Long tenderId);
    void deleteByTenderId(Long tenderId);
}
