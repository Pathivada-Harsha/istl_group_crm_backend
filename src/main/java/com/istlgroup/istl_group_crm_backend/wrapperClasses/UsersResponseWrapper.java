package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

import lombok.Data;

@Data
public class UsersResponseWrapper {

    private List<UserWrapper> userWrapper;

    private int totalUsers;
    private int activeUsers;
    private int inactiveUsers;
    private List<String> roles;
    private int currentPage;
    private int totalPages;
    private int totalElements;
    private int pageSize;
}

