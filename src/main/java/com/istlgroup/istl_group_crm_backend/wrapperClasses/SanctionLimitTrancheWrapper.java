package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One disbursement tranche of a Sanction Limit. Scalars are String, same
 * convention as {@link SanctionLimitWrapper} — the frontend sends loose
 * values, the service parses them into the entity's typed columns.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SanctionLimitTrancheWrapper {
    private Long id;
    private String trancheAmount;
    private String tentativeDisbursementDate;
    private String actualDisbursementDate;
}
