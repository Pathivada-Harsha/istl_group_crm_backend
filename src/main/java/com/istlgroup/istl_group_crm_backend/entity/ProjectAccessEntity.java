package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Per-project, per-user access grant.
 *
 * Simplified model: a user either has a grant for a project or they don't.
 * Which pages they can see within that project is controlled by their
 * pagePermissions (menu access) — not by an access_role column.
 *
 * access_role is kept in the DB as "GRANTED" for all rows so the column
 * stays non-null. SUPERADMIN and ADMIN always bypass this table.
 * ACCOUNTS_* roles bypass this table automatically (financial oversight).
 */
@Entity
@Table(name = "project_access",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_project_user",
        columnNames = {"project_id", "user_id"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectAccessEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Kept for DB compatibility — always stored as "GRANTED" in the new model. */
    @Column(name = "access_role", nullable = false, length = 30)
    @Builder.Default
    private String accessRole = "GRANTED";

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "note", length = 255)
    private String note;

    @PrePersist
    protected void onCreate() {
        if (grantedAt  == null) grantedAt  = LocalDateTime.now();
        if (accessRole == null) accessRole = "GRANTED";
    }
}