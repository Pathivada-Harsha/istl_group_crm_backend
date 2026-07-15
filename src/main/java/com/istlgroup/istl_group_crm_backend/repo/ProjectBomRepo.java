package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;

public interface ProjectBomRepo extends JpaRepository<ProjectBomEntity, Long> {
    List<ProjectBomEntity> findByProjectIdOrderBySeqNo(Long projectId);
    @Transactional
    void deleteByProjectId(Long projectId);
}
