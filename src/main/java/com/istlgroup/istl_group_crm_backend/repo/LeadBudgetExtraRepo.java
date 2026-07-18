package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetExtraEntity;

public interface LeadBudgetExtraRepo extends JpaRepository<LeadBudgetExtraEntity, Long> {

    List<LeadBudgetExtraEntity> findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long leadId);
}
