package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class LeadFilterRequestWrapper {
    private String searchTerm;
    private String status;
    private String priority;
    private String source;
    private String groupName;
    private String subGroupName;
    private Long assignedTo;
    private String fromDate;
    private String toDate;
}
