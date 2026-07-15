package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadBudgetItemEntity;

public interface LeadBudgetItemRepo extends JpaRepository<LeadBudgetItemEntity, Long> {

    List<LeadBudgetItemEntity> findByLeadIdAndDeletedAtIsNull(Long leadId);

    List<LeadBudgetItemEntity> findByBudgetIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long budgetId);
}
