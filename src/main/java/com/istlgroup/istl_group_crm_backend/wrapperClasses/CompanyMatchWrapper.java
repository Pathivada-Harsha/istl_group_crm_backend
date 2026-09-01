package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

/**
 * One candidate returned by {@code POST /borrower/match}. {@code confidence}
 * is one of CIN / NAME / ALIAS / FUZZY, in descending trust order — CIN and
 * NAME are shown as "this is probably it", ALIAS and FUZZY as "possibly the
 * same company, please confirm". The caller decides what to do with it; the
 * server never attaches a sanction to a candidate on its own.
 */
@Data
public class CompanyMatchWrapper {

    private Long borrowerId;
    private String borrowerName;
    private String cin;
    private String confidence;
    /** 0..1, only meaningful for FUZZY candidates. */
    private Double score;

    private String companyType;
    private Long parentGroupId;
    private String parentGroupName;
    private Long subGroupId;
    private String subGroupName;
    /** How many sanction letters this candidate already has on file. */
    private Integer sanctionsCount;

    public CompanyMatchWrapper() { }

    public CompanyMatchWrapper(Long borrowerId, String borrowerName, String cin,
                                String confidence, Double score) {
        this.borrowerId = borrowerId;
        this.borrowerName = borrowerName;
        this.cin = cin;
        this.confidence = confidence;
        this.score = score;
    }
}
