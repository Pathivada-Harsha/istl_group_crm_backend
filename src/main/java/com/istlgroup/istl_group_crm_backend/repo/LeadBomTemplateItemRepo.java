package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.LeadBomTemplateItemEntity;

public interface LeadBomTemplateItemRepo extends JpaRepository<LeadBomTemplateItemEntity, Long> {

    List<LeadBomTemplateItemEntity> findByTemplateIdAndDeletedAtIsNullOrderBySeqNoAscIdAsc(Long templateId);

    /** The basis-resolution index: every active BOM template line for a project type. */
    List<LeadBomTemplateItemEntity> findByProjectTypeAndDeletedAtIsNullOrderBySeqNoAscIdAsc(String projectType);
}
