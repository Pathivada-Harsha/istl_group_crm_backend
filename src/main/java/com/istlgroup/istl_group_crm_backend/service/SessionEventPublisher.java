package com.istlgroup.istl_group_crm_backend.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * SessionEventPublisher — pushes real-time "your session was terminated"
 * events over the existing STOMP/SockJS channel (/ws-notifications).
 *
 * Destination:  /user/queue/session-events   (per-user, keyed by users.id)
 *
 * Every browser session of the user receives the event; each client compares
 * the payload's sessionRowId with its own (from GET /session/whoami) and only
 * the terminated browser logs itself out and redirects to /login instantly —
 * no waiting for the next API call to hit the SessionFilter.
 *
 * Events are published AFTER the surrounding DB transaction commits, so the
 * kicked browser can never race ahead of the status change in user_sessions.
 */
@Service
@Slf4j
public class SessionEventPublisher {

    public static final String SESSION_QUEUE = "/queue/session-events";

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * @param userId       owner of the session (STOMP principal name)
     * @param sessionRowId user_sessions.id of the terminated session
     * @param status       EVICTED / ADMIN_TERMINATED / EXPIRED
     * @param reason       machine reason (NEW_DEVICE_LOGIN, USER_REMOTE_SIGNOUT, IDLE_TIMEOUT, ...)
     */
    public void publishTerminated(Long userId, Long sessionRowId, String status, String reason) {
        if (userId == null || sessionRowId == null) return;

        Map<String, Object> payload = Map.of(
                "type", "SESSION_TERMINATED",
                "sessionRowId", sessionRowId,
                "status", status == null ? "" : status,
                "reason", reason == null ? "" : reason,
                "message", messageFor(status, reason)
        );

        Runnable send = () -> {
            try {
                messagingTemplate.convertAndSendToUser(String.valueOf(userId), SESSION_QUEUE, payload);
                log.info("Published SESSION_TERMINATED for user {} session {} ({}/{})",
                        userId, sessionRowId, status, reason);
            } catch (Exception e) {
                // Real-time push is best-effort; SessionFilter still enforces on next API call
                log.warn("Could not publish session event for user {} session {}", userId, sessionRowId, e);
            }
        };

        // Publish only after the DB row is committed as terminated
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    private static String messageFor(String status, String reason) {
        if ("EXPIRED".equals(status)) {
            return "Your session expired due to inactivity. Please log in again.";
        }
        if ("EVICTED".equals(status)) {
            if ("USER_LOGOUT_ALL_ON_LOGIN".equals(reason)) {
                return "You were signed out because your account chose \"Logout from all devices\" during a new login.";
            }
            return "You were signed out because your account was used to sign in on another device.";
        }
        if ("ADMIN_TERMINATED".equals(status)) {
            if ("USER_REMOTE_SIGNOUT".equals(reason)) {
                return "This device was signed out remotely from your account.";
            }
            return "Your session was ended by an administrator.";
        }
        return "Your session has been ended. Please log in again.";
    }
}