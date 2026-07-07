package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * LoginAttemptService — brute-force protection (Feature 6).
 *
 * In-memory sliding window: after {@code max-attempts} failed logins within
 * {@code window-minutes} for the same username+IP pair, further attempts are
 * blocked for {@code lock-minutes}. Lockouts are written to the audit trail
 * synchronously as SECURITY events.
 */
@Service
@Slf4j
public class LoginAttemptService {

    @Value("${login-activity.bruteforce.max-attempts:5}")
    private int maxAttempts;

    @Value("${login-activity.bruteforce.window-minutes:15}")
    private int windowMinutes;

    @Value("${login-activity.bruteforce.lock-minutes:15}")
    private int lockMinutes;

    @Autowired
    private AuditLogService auditLogService;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    private static String key(String username, String ip) {
        return (username == null ? "" : username.trim().toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    /** True when this username+IP pair is currently locked out. */
    public boolean isLocked(String username, String ip) {
        Long until = lockedUntil.get(key(username, ip));
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            lockedUntil.remove(key(username, ip));
            return false;
        }
        return true;
    }

    /** Remaining lock time in whole minutes (for the error message). */
    public long lockedMinutesRemaining(String username, String ip) {
        Long until = lockedUntil.get(key(username, ip));
        if (until == null) return 0;
        long ms = until - System.currentTimeMillis();
        return ms <= 0 ? 0 : Math.max(1, ms / 60_000);
    }

    /** Records a failure; triggers a lockout when the threshold is crossed. */
    public void onFailure(String username, String ip) {
        String k = key(username, ip);
        long now = System.currentTimeMillis();
        long windowStart = now - windowMinutes * 60_000L;

        Deque<Long> deque = failures.computeIfAbsent(k, x -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) deque.pollFirst();
            if (deque.size() >= maxAttempts) {
                lockedUntil.put(k, now + lockMinutes * 60_000L);
                deque.clear();
                log.warn("Login lockout: '{}' from {} for {} min", username, ip, lockMinutes);
                auditLogService.logSecurityEvent(null, username, "SECURITY_EVENT",
                        "Account temporarily locked after " + maxAttempts
                        + " failed login attempts from IP " + ip);
            }
        }
    }

    /** Clears the failure window after a successful login. */
    public void onSuccess(String username, String ip) {
        failures.remove(key(username, ip));
        lockedUntil.remove(key(username, ip));
    }

    /** Hourly cleanup of stale windows so the maps never grow unbounded. */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        long windowStart = System.currentTimeMillis() - windowMinutes * 60_000L;
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                while (!e.getValue().isEmpty() && e.getValue().peekFirst() < windowStart) {
                    e.getValue().pollFirst();
                }
                return e.getValue().isEmpty();
            }
        });
        long now = System.currentTimeMillis();
        lockedUntil.entrySet().removeIf(e -> e.getValue() < now);
    }
}
