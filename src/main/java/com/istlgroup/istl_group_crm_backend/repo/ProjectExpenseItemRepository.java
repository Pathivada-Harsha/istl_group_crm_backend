package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ProjectExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectExpenseItemRepository extends JpaRepository<ProjectExpenseItem, Long> {
    List<ProjectExpenseItem> findByExpenseId(Long expenseId);
    void deleteByExpenseId(Long expenseId);
}