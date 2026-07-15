package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.istlgroup.istl_group_crm_backend.entity.ProjectItemEntity;

public interface ProjectItemRepo extends JpaRepository<ProjectItemEntity, Long> {

    List<ProjectItemEntity> findByProjectIdOrderByLineNo(Long projectId);

    void deleteByProjectId(Long projectId);

    @Query("SELECT i FROM ProjectItemEntity i WHERE i.projectId = :projectId ORDER BY i.lineNo")
    List<ProjectItemEntity> findByProjectIdWithCalculatedFields(@Param("projectId") Long projectId);
}
