package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.Data;

@Data
public class CustomerWrapper {
    private Long id;
    private String customerCode;
    private String name;
    private String companyName;
    private String groupName;
    private String contactPerson;
    private String designation;
    private String email;
    private String phone;
    private String altPhone;
    private String website;
    private String gstNumber;
    private String pan;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String status;
    private Long assignedTo;
    private String assignedToName;
    private String createdAt;
    private String updatedAt;
    
    // Followup information
    private Boolean hasPendingFollowups;
    private Integer pendingFollowupsCount;
}