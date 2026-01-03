package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class CustomerFilterRequestWrapper {
    private String searchTerm;
    private String groupName;
    private String status;
    private String city;
    private String state;
    private Long assignedTo;
    private String fromDate;
    private String toDate;
    
    // Pagination fields
    private Integer page;  // 0-based page index
    private Integer size;  // Number of records per page
}