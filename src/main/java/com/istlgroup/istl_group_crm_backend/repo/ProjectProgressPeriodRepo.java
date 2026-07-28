package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import com.istlgroup.istl_group_crm_backend.entity.ProjectProgressPeriodEntity;

public interface ProjectProgressPeriodRepo extends JpaRepository<ProjectProgressPeriodEntity, Long> {
    List<ProjectProgressPeriodEntity> findByProjectId(Long projectId);

    @Transactional
    void deleteByProjectId(Long projectId);

    /** Remove the progress periods of a phase that was deleted from the scope. */
    @Transactional
    void deleteByPhaseId(Long phaseId);
}
