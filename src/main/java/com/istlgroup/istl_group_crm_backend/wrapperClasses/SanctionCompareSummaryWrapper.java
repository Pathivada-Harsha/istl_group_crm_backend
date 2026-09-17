package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One row in the Borrower Comparison picker's selection list — deliberately
 * thin (no limits/tranches/derived values) since this feeds a checkbox list
 * of up to hundreds of sanctions, not a detail view. The full detail for
 * whichever ids get selected is fetched separately, in one batch, only once
 * "Compare" is clicked (see BorrowerService#getSanctionsForComparison,
 * which returns the same BorrowerSanctionWrapper the single-borrower detail
 * view already uses — no second detail DTO needed).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SanctionCompareSummaryWrapper {

    private Long id;
    private Long borrowerId;
    /** Set only for a sanction associated directly with a Parent Group or Sub Group, instead of a borrowerId. */
    private Long groupId;

    /** The borrower or group name this sanction is directly attached to — "COMPANY" | "GROUP" | "SUB_GROUP" via associatedWithType. */
    private String associatedWithType;
    private String associatedWithName;
    private Long parentGroupId;
    private String parentGroupName;
    private Long subGroupId;
    private String subGroupName;

    private String cin;
    private String refNo;
    private String sanctionDate;
    private String sanctionedAmount;
    private String lenderName;
    private String projectName;
    private String status;
}
