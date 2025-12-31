package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class LeadWrapper {
    private Long id;
    private String leadCode;
    private Long customerId;
    private String name;
    private String email;
    private String phone;
    private String source;
    private String priority;
    private String status;
    private Long assignedTo;
    private String assignedToName;
    private String enquiry;
    private String groupName;
    private String subGroupName;
    private Long createdBy;
    private String createdByName;
    private String createdAt;
    private String updatedAt;
}

