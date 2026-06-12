package com.istlgroup.istl_group_crm_backend.dto;

/**
 * Payload for creating a notification.
 * Used by the internal {@code createNotification(...)} calls from the domain
 * services/scheduler, and by the POST /api/notifications endpoint.
 */
public class NotificationRequestDTO {

    private Long   userId;          // recipient (users.id) — REQUIRED
    private String title;           // REQUIRED
    private String message;
    private String module;          // LEAD | TASK | FOLLOWUP | INVOICE
    private Long   referenceId;     // id of the related record
    private String notificationType;

    public NotificationRequestDTO() { }

    public NotificationRequestDTO(Long userId, String title, String message,
                                  String module, Long referenceId, String notificationType) {
        this.userId = userId;
        this.title = title;
        this.message = message;
        this.module = module;
        this.referenceId = referenceId;
        this.notificationType = notificationType;
    }

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
}
