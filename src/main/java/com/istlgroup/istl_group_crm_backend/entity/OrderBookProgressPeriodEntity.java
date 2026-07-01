package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One period (week/month) of planned & actual physical progress for a leaf scope item.
 * Values are INCREMENTAL % for that period; cumulative is derived at read time.
 * A phase with sub-items is not stored here — its curve is the sum of its children.
 */
@Entity
@Table(name = "order_book_progress_periods")
@Data
public class OrderBookProgressPeriodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_book_id", nullable = false)
    private Long orderBookId;

    @Column(name = "phase_id", nullable = false)
    private Long phaseId;

    /** NULL = the phase itself is the leaf; otherwise the sub-item's name. */
    @Column(name = "sub_item_key")
    private String subItemKey;

    @Column(name = "period_no", nullable = false)
    private Integer periodNo;

    @Column(name = "planned_pct", precision = 7, scale = 4)
    private BigDecimal plannedPct = BigDecimal.ZERO;

    @Column(name = "actual_pct", precision = 7, scale = 4)
    private BigDecimal actualPct = BigDecimal.ZERO;
}