package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;

import lombok.Data;

@Data
public class TeamRequestWrapper {

    private String name;
    private String description;
    // List of user IDs to set as members
    private List<Long> memberIds;
}