package com.istlgroup.istl_group_crm_backend.dto;

import java.time.LocalDateTime;

/**
 * Shape returned to the React client (list, dropdown, and WebSocket push).
 * The frontend uses {@code module} + {@code referenceId} to build the deep-link
 * route when a notification is clicked.
 */
public class NotificationResponseDTO {

    private Long          id;
    private Long          userId;
    private String        title;
    private String        message;
    private String        module;
    private Long          referenceId;
    private String        notificationType;
    private Boolean       isRead;
    private LocalDateTime createdAt;

    public NotificationResponseDTO() { }

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
}
