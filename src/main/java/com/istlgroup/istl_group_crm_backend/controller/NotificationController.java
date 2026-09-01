package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.dto.NotificationRequestDTO;
import com.istlgroup.istl_group_crm_backend.dto.NotificationResponseDTO;
import com.istlgroup.istl_group_crm_backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification REST API.
 *
 * The current user is resolved from the authenticated session, NOT from the
 * {@code User-Id} request header this controller used to trust. That header is set
 * by the browser, and it was the only thing scoping these endpoints — so any
 * logged-in user could read, mark read, or DELETE another user's notifications just
 * by changing it. Ownership is still re-verified in the service layer on every
 * read/delete; this closes the hole above it.
 */
@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // GET /api/notifications?filter=all|read|unread&search=&page=1&size=15
    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @ActingUserId String userIdHeader,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page",   required = false, defaultValue = "1")  int page,
            @RequestParam(value = "size",   required = false, defaultValue = "15") int size) {

        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();

        Boolean readFilter = switch (filter == null ? "all" : filter.toLowerCase()) {
            case "read"   -> Boolean.TRUE;
            case "unread" -> Boolean.FALSE;
            default        -> null;          // all
        };

        Page<NotificationResponseDTO> result =
                notificationService.getNotifications(userId, readFilter, search, page, size);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", result.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    // GET /api/notifications/latest  — for the navbar dropdown (top 10)
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> getLatest(
            @ActingUserId String userIdHeader) {
        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();
        List<NotificationResponseDTO> latest = notificationService.getLatest(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", latest);
        body.put("unreadCount", notificationService.getUnreadCount(userId));
        return ResponseEntity.ok(body);
    }

    // GET /api/notifications/unread-count
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(
            @ActingUserId String userIdHeader) {
        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", notificationService.getUnreadCount(userId)));
    }

    // POST /api/notifications  — create (internal/admin/testing). Recipient = body.userId.
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @ActingUserId String userIdHeader,
            @RequestBody NotificationRequestDTO request) {
        if (parseId(userIdHeader) == null) return unauthorized();
        NotificationResponseDTO created = notificationService.createNotification(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", created != null,
                "data", created));
    }

    // PUT /api/notifications/{id}/read
    @PutMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(
            @ActingUserId String userIdHeader,
            @PathVariable Long id) {
        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();
        NotificationResponseDTO dto = notificationService.markAsRead(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", dto));
    }

    // PUT /api/notifications/mark-all-read
    @PutMapping("/mark-all-read")
    public ResponseEntity<Map<String, Object>> markAllRead(
            @ActingUserId String userIdHeader) {
        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();
        int updated = notificationService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("success", true, "updated", updated));
    }

    // DELETE /api/notifications/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @ActingUserId String userIdHeader,
            @PathVariable Long id) {
        Long userId = parseId(userIdHeader);
        if (userId == null) return unauthorized();
        notificationService.delete(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── helpers ──────────────────────────────────────────────────────
    private Long parseId(String raw) {
        try { return (raw == null || raw.isBlank()) ? null : Long.parseLong(raw.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "Missing or invalid User-Id."));
    }
}
