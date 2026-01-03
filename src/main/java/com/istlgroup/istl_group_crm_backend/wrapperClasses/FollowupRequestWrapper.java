package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class FollowupRequestWrapper {
    private String relatedType;  // Lead, Customer, etc.
    private Long relatedId;
    private String followupType;  // Call, Email, Meeting, Site Visit, Reminder, Note
    private String scheduledAt;   // ISO datetime string
    private Long assignedTo;
    private String status;        // Pending, Completed, Cancelled
    private String completedAt;   // ISO datetime string
    private String notes;
    private String priority;      // High, Medium, Low
}