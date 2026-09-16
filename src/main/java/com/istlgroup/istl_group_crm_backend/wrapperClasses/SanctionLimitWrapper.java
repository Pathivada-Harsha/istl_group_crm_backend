package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One row of a sanction's "Limits" table (Product section). Scalars
 * are String, same convention as {@link BorrowerSanctionWrapper} — the
 * frontend sends loose values, the service parses them into the entity's
 * typed columns. {@code pctOfLimit} is deliberately not a field here: it's
 * always derived (facilityLimitAmount / the sanction's limitAmount) and never stored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SanctionLimitWrapper {
    private Long id;
    private String facilityLimitAmount;
    /**
     * "Fund Based Limit" / "Non Fund Based Limit - I" / etc. Read-only from
     * the frontend's point of view: whatever the client sends here is
     * ignored on save — the service always recomputes it from the limit's own
     * position (see {@code BorrowerService.limitLabelFor}), so it can never
     * drift from the row it labels.
     */
    private String limitLabel;
    private String facilityType;
    private String tentativeDisbursementDate;
    private String actualDisbursementDate;
    /** This limit's own per-period repayment-percentage overrides — see the entity field's own comment. */
    private String repaymentProfileJson;
    /** This limit's own disbursement tranches — optional; empty/absent means a single lump-sum disbursement. */
    private List<SanctionLimitTrancheWrapper> tranches;
}
