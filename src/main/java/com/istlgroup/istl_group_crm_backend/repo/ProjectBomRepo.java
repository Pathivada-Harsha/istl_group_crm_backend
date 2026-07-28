package com.istlgroup.istl_group_crm_backend.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.ProjectBomEntity;

public interface ProjectBomRepo extends JpaRepository<ProjectBomEntity, Long> {

    /** Live BOM lines for a project (soft-deleted rows excluded). Preferred read. */
    List<ProjectBomEntity> findByProjectIdAndDeletedAtIsNullOrderBySeqNo(Long projectId);

    /** Live BOM lines under one scope line (parent phase). */
    List<ProjectBomEntity> findByProjectIdAndScopeItemIdAndDeletedAtIsNullOrderBySeqNo(Long projectId, Long scopeItemId);

    /**
     * Soft-delete every live line for a project. Used by the replace-all save path
     * instead of a physical delete, so history and any FK references survive.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ProjectBomEntity b SET b.deletedAt = :now WHERE b.projectId = :projectId AND b.deletedAt IS NULL")
    int softDeleteByProjectId(@Param("projectId") Long projectId, @Param("now") LocalDateTime now);

    // ── Legacy (pre-soft-delete) methods — kept so existing callers don't break ──
    List<ProjectBomEntity> findByProjectIdOrderBySeqNo(Long projectId);

    @Transactional
    void deleteByProjectId(Long projectId);
}
