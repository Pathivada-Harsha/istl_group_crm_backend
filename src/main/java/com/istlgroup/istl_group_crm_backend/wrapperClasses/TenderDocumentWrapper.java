package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Document-checklist row DTO. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderDocumentWrapper {
    private Long id;
    private String documentName;
    private String status;
    private String link;
    private String notes;
}
