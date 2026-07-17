package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadScopeTemplateItemEntity;

public interface LeadScopeTemplateItemRepo extends JpaRepository<LeadScopeTemplateItemEntity, Long> {

    List<LeadScopeTemplateItemEntity> findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long templateId);

    List<LeadScopeTemplateItemEntity> findByProjectTypeAndDeletedAtIsNullOrderBySeqNoAscIdAsc(String projectType);
}
