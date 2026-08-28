package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.istlgroup.istl_group_crm_backend.entity.ProjectBomAuditEntity;

public interface ProjectBomAuditRepo extends JpaRepository<ProjectBomAuditEntity, Long> {

    /** Amendment history for a project, newest first. */
    List<ProjectBomAuditEntity> findByProjectIdOrderByChangedAtDesc(Long projectId);

    /** Amendment history for one BOM line, newest first. */
    List<ProjectBomAuditEntity> findByProjectBomIdOrderByChangedAtDesc(Long projectBomId);
}
