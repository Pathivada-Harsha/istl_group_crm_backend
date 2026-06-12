package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "user_page_permissions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "permission_id"}))
public class UserPagePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "permission_id")
    private Integer permissionId;

    @Column(name = "has_permission")
    private Boolean hasPermission;
}