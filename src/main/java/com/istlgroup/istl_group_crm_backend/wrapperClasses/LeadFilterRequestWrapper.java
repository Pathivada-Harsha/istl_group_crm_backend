package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class LeadFilterRequestWrapper {
    private String searchTerm;
    private String status;
    private String telecallerStatus;   // INTERESTED | NOT_INTERESTED | NOT_RESPONDED
    private String priority;
    private String source;
    private String groupName;
    private String subGroupName;
    private Long assignedTo;
    // Date range filter (yyyy-MM-dd)
    private String fromDate;
    private String toDate;
    // Sort
    private String sortBy;
    private String sortDirection;
    // Export-all flag: when true, fetch all matching records (no page limit)
    private Boolean exportAll;
}