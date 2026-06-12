package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class LoginCredentialsWrapper {

    private Long id;
    private String name;
    private String role;
    private String user_id;
    private String email;
    private String phone;
    private Long is_active;
    private LocalDateTime created_at;
    private LocalDateTime last_login_at;

    // "db" if a profile photo exists in user_profile_images, else null.
    // Frontend builds the image URL as: GET /users/avatar/{id}
    private String avatar_url;
}