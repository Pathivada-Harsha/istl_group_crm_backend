package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Workflow document-request DTO (department assignment). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenderDocRequestWrapper {
    private Long id;
    private String label;
    private String department;
    private String dueDate;
    private String status;
    private String notes;
}
