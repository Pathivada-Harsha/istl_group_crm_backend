package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetEntity;

public interface LeadBudgetRepo extends JpaRepository<LeadBudgetEntity, Long> {

    List<LeadBudgetEntity> findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long leadId);
}
