package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookBudgetEntity;

public interface OrderBookBudgetRepo extends JpaRepository<OrderBookBudgetEntity, Long> {
    List<OrderBookBudgetEntity> findByOrderBookIdOrderBySeqNo(Long orderBookId);
    @Transactional
    void deleteByOrderBookId(Long orderBookId);
}