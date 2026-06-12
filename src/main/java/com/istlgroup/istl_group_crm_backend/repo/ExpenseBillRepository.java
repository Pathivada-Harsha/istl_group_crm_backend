package com.istlgroup.istl_group_crm_backend.repo;

import com.istlgroup.istl_group_crm_backend.entity.ExpenseBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExpenseBillRepository extends JpaRepository<ExpenseBill, Long> {

    Optional<ExpenseBill> findByExpenseId(Long expenseId);

    boolean existsByExpenseId(Long expenseId);

    void deleteByExpenseId(Long expenseId);
}