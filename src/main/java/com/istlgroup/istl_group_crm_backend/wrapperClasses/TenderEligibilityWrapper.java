package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Eligibility criterion DTO. The JSON key "override" maps to overrideFlag
 *  (override is a Java keyword). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderEligibilityWrapper {
    private Long id;
    private String category;
    private String criterionName;
    private String requiredValue;
    private String ourValue;
    private String operator;

    @JsonProperty("override")
    private Boolean overrideFlag;

    private String overrideReason;
    private String overrideBy;
    private String overrideAt;
}
