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
@Table(name = "users")
@Data
public class LoginEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String user_id;
    private Long created_by;
    private String email;
    private String password;
    private String name;
    private String phone;
    private Long is_active;
    private LocalDateTime created_at;
    private LocalDateTime updated_at;
    private String updated_type;
    private LocalDateTime last_login_at;
    private String role;
    private String avatar_url;

    // ── Hierarchy & team fields ──────────────────────────────────────────────
    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "team", length = 100)
    private String team;

    // FIX #7: designation — job title shown instead of role label
    @Column(name = "designation", length = 150)
    private String designation;
    // ─────────────────────────────────────────────────────────────────────────
}