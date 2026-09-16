package com.istlgroup.istl_group_crm_backend.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One disbursement tranche of a single Sanction Limit (see SanctionLimitEntity's
 * own class comment) — a portion of that limit's own {@code facilityLimitAmount},
 * disbursed against its own tentative/actual date rather than the whole limit
 * being drawn as one lump sum. Plain FK column to the parent limit, no JPA
 * relationship annotation, matching {@code sanction_id} on SanctionLimitEntity
 * itself.
 */
@Entity
@Table(name = "sanction_limit_tranches")
@Data
public class SanctionLimitTrancheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "limit_id", nullable = false)
    private Long limitId;

    /** Display/save order — the list index at save time, not a reorderable rank. */
    @Column(name = "tranche_order", nullable = false)
    private Integer trancheOrder;

    @Column(name = "tranche_amount", nullable = false)
    private BigDecimal trancheAmount;

    @Column(name = "tentative_disbursement_date")
    private LocalDate tentativeDisbursementDate;

    @Column(name = "actual_disbursement_date")
    private LocalDate actualDisbursementDate;
}
