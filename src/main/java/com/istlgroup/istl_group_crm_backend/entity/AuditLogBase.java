package com.istlgroup.istl_group_crm_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;

/**
 * AuditLogBase — shared columns for audit_logs_v2 and its archive twin.
 * Append-only enterprise audit trail. Never updated, never deleted by the
 * application — retention jobs are the only thing that moves rows out.
 */
@MappedSuperclass
@Data
public abstract class AuditLogBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    private String username;

    @Column(name = "session_row_id")
    private Long sessionRowId;

    @Column(length = 60)
    private String module;

    @Column(length = 120)
    private String page;

    @Column(length = 40, nullable = false)
    private String operation;

    @Column(name = "entity_type", length = 80)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(length = 500)
    private String description;

    @Column(name = "old_value", columnDefinition = "json")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "json")
    private String newValue;

    @Column(length = 10, nullable = false)
    private String status = "SUCCESS";

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(length = 60)
    private String browser;

    @Column(name = "operating_system", length = 60)
    private String operatingSystem;

    @Column(name = "device_type", length = 20)
    private String deviceType;

    private Double latitude;

    private Double longitude;

    @Column(name = "request_id", length = 36)
    private String requestId;
}
