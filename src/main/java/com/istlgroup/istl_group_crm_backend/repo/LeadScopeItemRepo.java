package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadScopeItemEntity;

public interface LeadScopeItemRepo extends JpaRepository<LeadScopeItemEntity, Long> {

    List<LeadScopeItemEntity> findByLeadIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long leadId);
}
