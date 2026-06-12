package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class LeadHistoryWrapper {
    private Long id;
    private Long leadId;
    private String actionType;
    private String fieldChanged;
    private String oldValue;
    private String newValue;
    private String description;
    private Long createdBy;
    private String createdByName;
    private String createdAt;
}