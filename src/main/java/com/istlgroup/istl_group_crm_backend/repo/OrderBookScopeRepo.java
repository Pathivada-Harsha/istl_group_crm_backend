package com.istlgroup.istl_group_crm_backend.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookScopeEntity;

public interface OrderBookScopeRepo extends JpaRepository<OrderBookScopeEntity, Long> {
    Optional<OrderBookScopeEntity> findByOrderBookId(Long orderBookId);
    void deleteByOrderBookId(Long orderBookId);
}