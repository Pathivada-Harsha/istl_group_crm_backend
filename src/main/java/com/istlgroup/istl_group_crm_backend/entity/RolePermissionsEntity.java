package com.istlgroup.istl_group_crm_backend.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
@Data
public class RolePermissionsEntity {

    @Id
    private Integer role_id;
    @Id
    private Integer permission_id;
}
