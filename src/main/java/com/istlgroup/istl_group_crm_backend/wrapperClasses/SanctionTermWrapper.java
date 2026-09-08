package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One row of a sanction's "Sanction Terms" table (Product section). Scalars
 * are String, same convention as {@link BorrowerSanctionWrapper} — the
 * frontend sends loose values, the service parses them into the entity's
 * typed columns. {@code pctOfLimit} is deliberately not a field here: it's
 * always derived (termLimit / the sanction's limitAmount) and never stored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SanctionTermWrapper {
    private Long id;
    private String termLimit;
    private String facilityType;
    private String tentativeDisbursementDate;
    private String actualDisbursementDate;
    /** This term's own per-period repayment-percentage overrides — see the entity field's own comment. */
    private String repaymentProfileJson;
}
