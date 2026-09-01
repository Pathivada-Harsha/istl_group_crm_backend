package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.istlgroup.istl_group_crm_backend.entity.ProjectScopeEntity;

public interface ProjectScopeRepo extends JpaRepository<ProjectScopeEntity, Long> {
    Optional<ProjectScopeEntity> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);

    /**
     * Planned windows for every project whose Work Breakdown &amp; Schedule is
     * actually scheduled — both dates set, end after start (the same test the
     * schedule grid uses to decide it can draw a timeline at all).
     *
     * Rows are {@code [projectId, plannedStartDate, plannedEndDate]}. Scalar
     * projection, one query for the whole list: the projects list renders up to
     * a few hundred rows and must not fan out into a scope lookup per row.
     */
    @Query("SELECT s.projectId, s.plannedStartDate, s.plannedEndDate "
         + "FROM ProjectScopeEntity s "
         + "WHERE s.plannedStartDate IS NOT NULL "
         + "  AND s.plannedEndDate IS NOT NULL "
         + "  AND s.plannedEndDate > s.plannedStartDate")
    List<Object[]> findScheduledWindows();
}
