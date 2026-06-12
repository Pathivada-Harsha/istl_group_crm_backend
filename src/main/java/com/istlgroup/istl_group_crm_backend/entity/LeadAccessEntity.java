package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Tracks who has access to a lead.
 * Created when:
 *   - A lead is created  → TELECALLER entry for assigned telecaller
 *   - Telecaller marks INTERESTED → BD entry for assigned BD member
 */
@Entity
@Table(name = "lead_access")
@Data
public class LeadAccessEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** TELECALLER | BD | MANAGER */
    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @PrePersist
    protected void onCreate() {
        this.grantedAt = LocalDateTime.now();
    }
}