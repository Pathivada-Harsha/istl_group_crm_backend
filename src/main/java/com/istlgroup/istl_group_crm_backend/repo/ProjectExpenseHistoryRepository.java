package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProjectExpenseHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectExpenseHistoryRepository extends JpaRepository<ProjectExpenseHistory, Long> {

    List<ProjectExpenseHistory> findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(
        ProjectExpenseHistory.ReferenceType referenceType, Long referenceId
    );

    Page<ProjectExpenseHistory> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);
}