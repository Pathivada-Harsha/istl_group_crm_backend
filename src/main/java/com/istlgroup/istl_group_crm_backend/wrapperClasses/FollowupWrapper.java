package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class FollowupWrapper {
    private Long id;
    private String relatedType;
    private Long relatedId;
    private String followupType;
    private String scheduledAt;
    private Long createdBy;
    private String createdByName;
    private Long assignedTo;
    private String assignedToName;
    private String status;
    private String completedAt;
    private String notes;
    private String priority;
    private String createdAt;
    private String updatedAt;
}