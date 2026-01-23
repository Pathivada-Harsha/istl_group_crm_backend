package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class FollowupWrapper {
    private Long id;
    private String relatedType;
    private Long relatedId;
    private Long leadId;
    private String leadCode;
    private Long customerId;
    private String customerCode;
    private Long projectId;
    private String projectCode;
    private String groupName;
    private String subGroupName;
    private String followupType;
    private String scheduledAt;
    private Long createdBy;
    private String createdByName;
    private Long assignedTo;
    private String assignedToName;
    private String status;
    private String priority;
    private String completedAt;
    private String notes;
    private String outcome;
    private String createdAt;
    private String updatedAt;
}