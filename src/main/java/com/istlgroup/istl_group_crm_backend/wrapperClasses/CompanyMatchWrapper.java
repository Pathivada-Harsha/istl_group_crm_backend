package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

/**
 * One candidate returned by {@code POST /borrower/match}. {@code confidence}
 * is one of CIN / NAME — the only two identity signals the sanction-import
 * matching decision uses (see {@code BorrowerService#matchBorrower}; no
 * alias or fuzzy-similarity tier feeds this list). The caller decides what
 * to do with it; the server never attaches a sanction to a candidate on its
 * own.
 */
@Data
public class CompanyMatchWrapper {

    private Long borrowerId;
    private String borrowerName;
    private String cin;
    private String confidence;
    /** Always 1.0 — both CIN and NAME are exact matches, never a fuzzy score. */
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
