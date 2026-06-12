package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.dto.NotificationRequestDTO;
import com.istlgroup.istl_group_crm_backend.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;

public interface NotificationService {

    /**
     * Core creation entry point. Persists the notification and pushes it in
     * real-time to the recipient over WebSocket. Safe to call from any domain
     * service (Task/Lead/Followup/Invoice) and from the scheduler.
     *
     * Never throws into the caller's transaction for delivery problems — a
     * failed push must not roll back the business operation that triggered it.
     */
    NotificationResponseDTO createNotification(NotificationRequestDTO request);

    /** Convenience overload for domain services. */
    NotificationResponseDTO createNotification(Long userId, String title, String message,
                                               String module, Long referenceId, String type);

    /** Latest 10 for the navbar dropdown. */
    java.util.List<NotificationResponseDTO> getLatest(Long userId);

    /** Paged list for the /notifications page. readFilter: null=ALL, true=READ, false=UNREAD. */
    Page<NotificationResponseDTO> getNotifications(Long userId, Boolean readFilter,
                                                   String search, int page, int size);

    long getUnreadCount(Long userId);

    /** Marks one notification read — verifies ownership first. */
    NotificationResponseDTO markAsRead(Long notificationId, Long userId);

    int markAllRead(Long userId);

    /** Deletes one notification — verifies ownership first. */
    void delete(Long notificationId, Long userId);
}
