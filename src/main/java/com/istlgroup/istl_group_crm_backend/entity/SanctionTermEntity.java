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
 * One facility tranche of a sanction's overall Limit — e.g. a Rs. 270 Cr
 * limit split into a Term Loan and a Letter of Credit, each with its own
 * disbursement dates and, downstream, its own repayment schedule (computed
 * on read from this row's own {@code actualDisbursementDate}, exactly like
 * the whole-sanction schedule already is — see BorrowerSanctionEntity's own
 * class comment). Plain FK column to the parent, no JPA relationship
 * annotation, matching {@code borrowerId}/{@code groupId} on
 * BorrowerSanctionEntity itself rather than the (unrelated) @ManyToOne style
 * used by QuotationItemEntity.
 */
@Entity
@Table(name = "sanction_terms")
@Data
public class SanctionTermEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sanction_id", nullable = false)
    private Long sanctionId;

    /** Display/save order — the list index at save time, not a reorderable rank. */
    @Column(name = "term_order", nullable = false)
    private Integer termOrder;

    @Column(name = "term_limit", nullable = false)
    private BigDecimal termLimit;

    @Column(name = "facility_type", length = 40)
    private String facilityType;

    @Column(name = "tentative_disbursement_date")
    private LocalDate tentativeDisbursementDate;

    @Column(name = "actual_disbursement_date")
    private LocalDate actualDisbursementDate;

    /**
     * This term's own JSON array of per-period repayment percentages — same
     * shape and same meaning as {@code BorrowerSanctionEntity.repaymentProfileJson}
     * (see that field's own comment), just scoped to this one term's own
     * schedule instead of the whole sanction's. NULL means no reviewer
     * override yet — the schedule then generates an equal 100/N split on
     * every read, same as before.
     */
    @Column(name = "repayment_profile_json", columnDefinition = "TEXT")
    private String repaymentProfileJson;
}
