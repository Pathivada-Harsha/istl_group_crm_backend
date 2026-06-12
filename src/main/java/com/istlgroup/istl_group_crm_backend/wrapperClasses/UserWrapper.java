package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserWrapper {

    private Long id;
    private String user_id;
    private String email;
    private String name;
    private String phone;
    private Long is_active;
    private LocalDateTime created_at;
    private String role;

    private Long managerId;
    private String managerName;
    private String team;
    private String designation;

    private Long menuPermissionsCount;
    private Long pagePermissionsCount;

    // "db" if a profile photo exists, null otherwise.
    // Image URL: GET /users/avatar/{id}
    private String avatar_url;
}