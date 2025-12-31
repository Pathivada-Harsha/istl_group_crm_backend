package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class LeadRequestWrapper {
    private Long customerId;
    private String name;
    private String email;
    private String phone;
    private String source;
    private String priority;
    private String status;
    private Long assignedTo;
    private String enquiry;
    private String groupName;
    private String subGroupName;
}
