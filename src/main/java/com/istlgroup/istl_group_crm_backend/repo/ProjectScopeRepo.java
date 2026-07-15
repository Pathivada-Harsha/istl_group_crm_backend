package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.ProjectScopeEntity;

public interface ProjectScopeRepo extends JpaRepository<ProjectScopeEntity, Long> {
    Optional<ProjectScopeEntity> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
