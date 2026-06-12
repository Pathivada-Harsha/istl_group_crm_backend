package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProjectAdvance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ProjectAdvanceRepository extends JpaRepository<ProjectAdvance, Long> {

    Page<ProjectAdvance> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<ProjectAdvance> findByProjectId(String projectId);

    boolean existsByAdvanceCode(String advanceCode);

    @Query("""
        SELECT SUM(a.totalAdvanceAmount) FROM ProjectAdvance a
        WHERE a.projectId = :projectId
    """)
    BigDecimal sumTotalByProject(@Param("projectId") String projectId);

    @Query("""
        SELECT SUM(a.totalAdvanceAmount) FROM ProjectAdvance a
        WHERE a.projectId = :projectId AND a.status != 'Settled'
    """)
    BigDecimal sumUnsettledByProject(@Param("projectId") String projectId);
}