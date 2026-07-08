package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.repo.AuditLogRepo;
import com.istlgroup.istl_group_crm_backend.util.UserAgentParser;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

/**
 * AuditLogService — single entry point for recording audit events (Feature 3).
 *
 * Design: business operations must NEVER be slowed down or failed by audit
 * logging. Events are pushed to an in-memory queue and flushed to MySQL in
 * batches every 2 seconds. Security-critical events (lockouts, terminations,
 * permission changes) bypass the queue and are written synchronously.
 *
 * The table is append-only: this service exposes no update or delete — that
 * immutability is the core of audit-trail integrity.
 */
@Service
@Slf4j
public class AuditLogService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int FLUSH_BATCH = 500;
    private static final int QUEUE_HARD_LIMIT = 50_000; // memory safety valve

    /** Sensitive keys that must never appear in old/new value JSON. */
    private static final String[] MASKED_KEYS = {
            "password", "newPassword", "oldPassword", "otp", "token", "secret"
    };

    @Autowired
    private AuditLogRepo auditLogRepo;

    private final ConcurrentLinkedQueue<AuditLogEntity> queue = new ConcurrentLinkedQueue<>();

    // ═════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════

    /** General-purpose async event. Request context is captured automatically. */
    public void log(String module, String operation, String page, String description) {
        AuditLogEntity e = base(module, operation, page, description);
        enqueue(e);
    }

    /** Async event tied to a business entity, with optional old/new values. */
    public void logChange(String module, String operation, String entityType, Long entityId,
                          String description, String oldValueJson, String newValueJson) {
        AuditLogEntity e = base(module, operation, null, description);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setOldValue(mask(oldValueJson));
        e.setNewValue(mask(newValueJson));
        enqueue(e);
    }

    /** Async FAILURE event (validation error, API failure, rejected operation). */
    public void logFailure(String module, String operation, String description, String reason) {
        AuditLogEntity e = base(module, operation, null, description);
        e.setStatus("FAILURE");
        e.setFailureReason(truncate(reason, 500));
        enqueue(e);
    }

    /** SYNCHRONOUS write — for security events that must survive a crash. */
    public void logSecurityEvent(Long userId, String username, String operation,
                                 String description) {
        try {
            AuditLogEntity e = base("SECURITY", operation, null, description);
            if (userId != null)   e.setUserId(userId);
            if (username != null) e.setUsername(username);
            auditLogRepo.save(e);
        } catch (Exception ex) {
            log.error("Failed to write security audit event: {}", description, ex);
        }
    }

    /** Fully pre-built event (used by AuditedAspect and the /track endpoint). */
    public void enqueue(AuditLogEntity e) {
        if (queue.size() >= QUEUE_HARD_LIMIT) {
            log.warn("Audit queue full ({}), dropping event {}", QUEUE_HARD_LIMIT, e.getOperation());
            return;
        }
        queue.add(e);
    }

    /** Builds an event with request context (ip, browser, session, user) filled in. */
    public AuditLogEntity base(String module, String operation, String page, String description) {
        AuditLogEntity e = new AuditLogEntity();
        e.setCreatedAt(LocalDateTime.now(IST));
        e.setModule(module);
        e.setOperation(operation);
        e.setPage(truncate(page, 120));
        e.setDescription(truncate(description, 500));
        e.setStatus("SUCCESS");
        e.setRequestId(UUID.randomUUID().toString());
        fillRequestContext(e);
        return e;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Flush loop
    // ═════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelay = 2000)
    public void flush() {
        if (queue.isEmpty()) return;
        List<AuditLogEntity> batch = new ArrayList<>(FLUSH_BATCH);
        AuditLogEntity e;
        while (batch.size() < FLUSH_BATCH && (e = queue.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) return;
        try {
            auditLogRepo.saveAll(batch);
        } catch (Exception ex) {
            log.error("Audit flush of {} events failed — retrying next cycle", batch.size(), ex);
            queue.addAll(batch); // re-queue; hard limit prevents unbounded growth
        }
    }

    /** Drain remaining events on application shutdown. */
    @PreDestroy
    public void drainOnShutdown() {
        int cycles = 0;
        while (!queue.isEmpty() && cycles++ < 50) {
            flush();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private void fillRequestContext(AuditLogEntity e) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();

            e.setIpAddress(SessionRegistryService.clientIp(request));

            UserAgentParser.ParsedUa ua = UserAgentParser.parse(request.getHeader("User-Agent"));
            e.setBrowser(ua.browser);
            e.setOperatingSystem(ua.operatingSystem);
            e.setDeviceType(ua.deviceType);

            HttpSession session = request.getSession(false);
            if (session != null) {
                Object userId = session.getAttribute("USER_ID");
                if (userId instanceof Long l) e.setUserId(l);
                Object username = session.getAttribute("USER_NAME");
                if (username instanceof String s) e.setUsername(s);
                Object sessionRowId = session.getAttribute("SESSION_ROW_ID");
                if (sessionRowId instanceof Long l) e.setSessionRowId(l);
                Object lat = session.getAttribute("SESSION_LAT");
                if (lat instanceof Double d) e.setLatitude(d);
                Object lng = session.getAttribute("SESSION_LNG");
                if (lng instanceof Double d) e.setLongitude(d);
            }
        } catch (Exception ex) {
            // Context capture is best-effort — never break the caller
            log.debug("Audit request-context capture failed", ex);
        }
    }

    /** Blanks the values of sensitive keys inside a JSON string. */
    private static String mask(String json) {
        if (json == null) return null;
        String out = json;
        for (String key : MASKED_KEYS) {
            out = out.replaceAll("(\"" + key + "\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
        }
        return out;
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }
}
