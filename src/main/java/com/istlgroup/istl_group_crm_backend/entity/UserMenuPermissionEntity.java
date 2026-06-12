package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Entity
@Table(name = "user_menu_permissions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "menu_id"}))
@Data
public class UserMenuPermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "menu_id")
    private Long menuId;

    @Column(name = "has_permission")
    private Boolean hasPermission;
}