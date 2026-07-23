package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Data;

/**
 * One eligibility criterion (Technical or Financial). pass/fail is evaluated on
 * the client from the operator; only the inputs + any override are stored.
 * ({@code override} is a Java keyword, so the field is {@code overrideFlag} and
 * the column is {@code override_flag}; the wrapper exposes it as "override".)
 */
@Entity
@Table(name = "tender_eligibility_criteria")
@Data
public class TenderEligibilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "sort_no")
    private Integer sortNo = 0;

    @Column(name = "category")
    private String category;

    @Column(name = "criterion_name")
    private String criterionName;

    @Column(name = "required_value")
    private String requiredValue;

    @Column(name = "our_value")
    private String ourValue;

    @Column(name = "operator")
    private String operator;

    @Column(name = "override_flag")
    private Boolean overrideFlag = false;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "override_by")
    private String overrideBy;

    @Column(name = "override_at")
    private LocalDate overrideAt;
}
