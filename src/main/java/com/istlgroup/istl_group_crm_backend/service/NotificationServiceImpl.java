package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.customException.NotificationException;
import com.istlgroup.istl_group_crm_backend.dto.NotificationRequestDTO;
import com.istlgroup.istl_group_crm_backend.dto.NotificationResponseDTO;
import com.istlgroup.istl_group_crm_backend.entity.NotificationEntity;
import com.istlgroup.istl_group_crm_backend.mapper.NotificationMapper;
import com.istlgroup.istl_group_crm_backend.repo.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    /** STOMP destination each client subscribes to as /user/queue/notifications. */
    public static final String USER_QUEUE = "/queue/notifications";

    private final NotificationRepository repository;
    private final NotificationMapper mapper;
    private final SimpMessagingTemplate messagingTemplate;

    // ── CREATE ───────────────────────────────────────────────────────
    @Override
    @Transactional
    public NotificationResponseDTO createNotification(NotificationRequestDTO request) {
        if (request == null || request.getUserId() == null) {
            // Defensive: never raise an "orphan" notification with no recipient.
            log.warn("createNotification skipped — missing recipient userId");
            return null;
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            request.setTitle("Notification");
        }

        NotificationEntity saved = repository.save(mapper.toEntity(request));
        NotificationResponseDTO dto = mapper.toResponse(saved);

        // Real-time push to ONLY the intended user. Delivery failure must never
        // break the business transaction that triggered this notification.
        pushToUser(saved.getUserId(), dto);
        return dto;
    }

    @Override
    @Transactional
    public NotificationResponseDTO createNotification(Long userId, String title, String message,
                                                      String module, Long referenceId, String type) {
        return createNotification(
                new NotificationRequestDTO(userId, title, message, module, referenceId, type));
    }

    /** Sends the new notification + fresh unread count to the recipient only. */
    private void pushToUser(Long userId, NotificationResponseDTO dto) {
        try {
            String principal = String.valueOf(userId);
            // 1) the notification itself
            messagingTemplate.convertAndSendToUser(principal, USER_QUEUE,
                    Map.of("type", "NEW_NOTIFICATION", "payload", dto));
            // 2) the updated unread count so the navbar badge stays in sync
            messagingTemplate.convertAndSendToUser(principal, USER_QUEUE,
                    Map.of("type", "UNREAD_COUNT", "count", getUnreadCount(userId)));
        } catch (Exception ex) {
            log.error("Failed to push notification to user {}: {}", userId, ex.getMessage());
        }
    }

    // ── READ QUERIES ─────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponseDTO> getLatest(Long userId) {
        return repository.findTop10ByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(mapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> getNotifications(Long userId, Boolean readFilter,
                                                          String search, int page, int size) {
        // Sort by latest. page is 1-based from the client.
        int pageIndex = Math.max(page - 1, 0);
        Pageable pageable = PageRequest.of(pageIndex, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findForUser(userId, readFilter,
                        search == null ? "" : search.trim(), pageable)
                .map(mapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return repository.countByUserIdAndIsReadFalse(userId);
    }

    // ── MUTATIONS (ownership-checked) ────────────────────────────────
    @Override
    @Transactional
    public NotificationResponseDTO markAsRead(Long notificationId, Long userId) {
        NotificationEntity n = loadOwned(notificationId, userId);
        if (Boolean.FALSE.equals(n.getIsRead())) {
            n.setIsRead(Boolean.TRUE);
            n = repository.save(n);
            // keep the badge in sync after a read
            pushUnreadCount(userId);
        }
        return mapper.toResponse(n);
    }

    @Override
    @Transactional
    public int markAllRead(Long userId) {
        int updated = repository.markAllReadForUser(userId);
        pushUnreadCount(userId);
        return updated;
    }

    @Override
    @Transactional
    public void delete(Long notificationId, Long userId) {
        NotificationEntity n = loadOwned(notificationId, userId);
        repository.delete(n);
        pushUnreadCount(userId);
    }

    private void pushUnreadCount(Long userId) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), USER_QUEUE,
                    Map.of("type", "UNREAD_COUNT", "count", getUnreadCount(userId)));
        } catch (Exception ex) {
            log.debug("Unread-count push skipped for user {}: {}", userId, ex.getMessage());
        }
    }

    /**
     * Loads a notification and enforces that it belongs to {@code userId}.
     * This is the security boundary for read/delete — a user can never touch
     * another user's notification, regardless of headers sent.
     */
    private NotificationEntity loadOwned(Long notificationId, Long userId) {
        NotificationEntity n = repository.findById(notificationId)
                .orElseThrow(NotificationException::notFound);
        if (userId == null || !userId.equals(n.getUserId())) {
            throw NotificationException.forbidden();
        }
        return n;
    }
}
