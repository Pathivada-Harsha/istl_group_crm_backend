package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProjectAccessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectAccessRepository extends JpaRepository<ProjectAccessEntity, Long> {

    /** All grants for a given project (admin UI — list who has access) */
    List<ProjectAccessEntity> findByProjectIdOrderByGrantedAtDesc(String projectId);

    /** All grants for a given user (used to list "my projects") */
    List<ProjectAccessEntity> findByUserIdOrderByGrantedAtDesc(Long userId);

    /** Check if a specific user has any grant for a project */
    Optional<ProjectAccessEntity> findByProjectIdAndUserId(String projectId, Long userId);

    /** Remove a specific grant */
    void deleteByProjectIdAndUserId(String projectId, Long userId);

    /** Count how many users have access to a project */
    long countByProjectId(String projectId);

    /** All project IDs that a user has access to */
    @Query("SELECT pa.projectId FROM ProjectAccessEntity pa WHERE pa.userId = :userId")
    List<String> findProjectIdsByUserId(@Param("userId") Long userId);
}