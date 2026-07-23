package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Audit-trail entry DTO. JSON keys "by"/"at" map to actionBy/at to match the
 *  frontend's approvalLog shape. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderApprovalLogWrapper {
    private Long id;
    private String stage;
    private String action;

    @JsonProperty("by")
    private String by;

    private String remarks;

    @JsonProperty("at")
    private String at;
}
