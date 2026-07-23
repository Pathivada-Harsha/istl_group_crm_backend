package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** BOQ line item DTO. Numeric fields are String to tolerate the frontend's
 *  loose typing (numbers or empty); parsed in the service. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderBoqItemWrapper {
    private Long id;
    private String itemNo;
    private String scope;
    private String description;
    private String unit;
    private String quantity;
    private String tenderRate;
    private String materialRate;
    private String labourRate;
}
