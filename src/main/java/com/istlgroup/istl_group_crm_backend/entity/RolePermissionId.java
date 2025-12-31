package com.istlgroup.istl_group_crm_backend.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class RolePermissionId implements Serializable {

    private Integer role_id;
    private Integer permission_id;
}
