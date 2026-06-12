package com.istlgroup.istl_group_crm_backend.wrapperClasses;



public interface GetRoleWithStatsWrapper {
    Integer getId();
    String getName();
    String getDescription();
    Long getUserCount();
    Long getPermissionCount();
}