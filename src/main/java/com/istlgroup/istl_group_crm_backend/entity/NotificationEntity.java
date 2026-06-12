package com.istlgroup.istl_group_crm_backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A single notification addressed to exactly one user (users.id).
 * Plain JPA entity with manual accessors to match the project's entity style.
 */
@Entity
@Table(name = "crm_notifications", indexes = {
        @Index(name = "idx_crm_notifications_user_id",            columnList = "user_id"),
        @Index(name = "idx_crm_notifications_is_read",            columnList = "is_read"),
        @Index(name = "idx_crm_notifications_created_at",         columnList = "created_at"),
        @Index(name = "idx_crm_notifications_module",             columnList = "module"),
        @Index(name = "idx_crm_notifications_user_read_created",  columnList = "user_id,is_read,created_at"),
        @Index(name = "idx_crm_notifications_dedupe",             columnList = "user_id,notification_type,reference_id,created_at")
})
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Recipient — references users.id. Every notification belongs to one user. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    /** LEAD | TASK | FOLLOWUP | INVOICE */
    @Column(name = "module", length = 50)
    private String module;

    /** id of the related record (lead/task/followup/invoice) for deep-linking. */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "notification_type", length = 100)
    private String notificationType;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (isRead == null) isRead = Boolean.FALSE;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters / Setters ────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long referenceId) { this.referenceId = referenceId; }

    public String getNotificationType() { return notificationType; }
    public void setNotificationType(String notificationType) { this.notificationType = notificationType; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
