package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class FollowupRequestWrapper {
    private String relatedType; // "LEAD", "CUSTOMER", "PROJECT"
    private Long relatedId;
    private Long leadId;
    private Long customerId;
    private Long projectId;
    private String groupName;
    private String subGroupName;
    private String followupType;
    private String scheduledAt; // Format: "yyyy-MM-dd HH:mm:ss"
    private Long assignedTo;
    private String status;
    private String priority;
    private String notes;
    private String outcome;
}