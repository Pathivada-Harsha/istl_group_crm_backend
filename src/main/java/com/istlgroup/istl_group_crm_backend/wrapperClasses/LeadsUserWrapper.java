package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeadsUserWrapper {
    private Long   id;
    private String name;
    // "db" if user has a profile photo in user_profile_images, else null.
    // Used by frontend to build: GET /users/avatar/{id}
    private String avatar_url;
    private String phone;
}