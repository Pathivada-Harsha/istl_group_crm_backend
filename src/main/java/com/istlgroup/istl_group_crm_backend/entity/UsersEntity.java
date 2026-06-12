package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "users")
public class UsersEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String user_id;
    private Long created_by;
    private String email;
    private String password;
    private String name;
    private String phone;
    private String role;
    private Long is_active;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "team", length = 100)
    private String team;

    @Column(name = "designation", length = 150)
    private String designation; 

    @Column(insertable = false, updatable = false)
    private LocalDateTime created_at;

    @Column(insertable = false)
    private LocalDateTime updated_at;

    @Column(insertable = false)
    private String updated_type;

    @Column(insertable = false)
    private LocalDateTime last_login_at;

    // ── Profile image flag ───────────────────────────────────────────────────
    // NULL = no photo, "db" = photo stored in user_profile_images.
    // MUST be writable (no insertable/updatable = false) so UploadAvatar can set it.
    @Column(name = "avatar_url", length = 10)
    private String avatar_url;
}