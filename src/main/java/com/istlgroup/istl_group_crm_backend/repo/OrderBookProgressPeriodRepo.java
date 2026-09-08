package com.istlgroup.istl_group_crm_backend.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookProgressPeriodEntity;

public interface OrderBookProgressPeriodRepo extends JpaRepository<OrderBookProgressPeriodEntity, Long> {
    List<OrderBookProgressPeriodEntity> findByOrderBookId(Long orderBookId);

    /** The progress rows of one phase — see the note on the project-side twin. */
    List<OrderBookProgressPeriodEntity> findByPhaseId(Long phaseId);

    @Transactional
    void deleteByOrderBookId(Long orderBookId);
}