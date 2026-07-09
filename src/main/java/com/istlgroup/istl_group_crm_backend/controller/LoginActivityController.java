package com.istlgroup.istl_group_crm_backend.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.dto.ActiveSessionDto;
import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.UserSessionEntity;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.service.AuditLogService;
import com.istlgroup.istl_group_crm_backend.service.LoginActivityService;
import com.istlgroup.istl_group_crm_backend.service.SessionRegistryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * LoginActivityController — Office Use → Login & Activity Monitor.
 *
 * Access rules:
 *   • /track                → any authenticated user (page-view beacon)
 *   • every other endpoint  → ADMIN or SUPERADMIN only
 *   • archive=true          → ADMIN or SUPERADMIN only (enforced again)
 */
@RestController
@RequestMapping("/office-use/login-activity")
public class LoginActivityController {

    @Autowired
    private LoginActivityService loginActivityService;

    @Autowired
    private SessionRegistryService sessionRegistryService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private LoginRepo loginRepo;

    // ═════════════════════════════════════════════════════════════════════
    // Dashboard
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/dashboard-stats")
    public ResponseEntity<?> dashboardStats(HttpSession session) throws CustomException {
        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.dashboardStats());
    }

    @GetMapping("/filter-options")
    public ResponseEntity<?> filterOptions(HttpSession session) throws CustomException {
        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.filterOptions());
    }

    // ═════════════════════════════════════════════════════════════════════
    // Login history (hot + archive)
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/login-history")
    public ResponseEntity<?> loginHistory(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String browser,
            @RequestParam(required = false) String os,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "false") boolean archive,
            HttpSession session) throws CustomException {

        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.loginHistory(
                userId, startOf(dateFrom), endOf(dateTo), status, deviceType, browser,
                os, city, search, page, size, sortBy, sortDir, archive));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Active sessions
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/active-sessions")
    public ResponseEntity<?> activeSessions(HttpSession session) throws CustomException {
        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.activeSessions(session.getId()));
    }

    @DeleteMapping("/active-sessions/{id}")
    public ResponseEntity<?> terminateSession(@PathVariable Long id, HttpSession session)
            throws CustomException {
        LoginEntity admin = requireAdmin(session);
        UserSessionEntity terminated = sessionRegistryService.terminate(id);
        auditLogService.logSecurityEvent(admin.getId(), admin.getName(), "SECURITY_EVENT",
                "Admin terminated session #" + id + " of user " + terminated.getUsername());
        return ResponseEntity.ok(Map.of("message", "Session terminated"));
    }

    @DeleteMapping("/users/{userId}/sessions")
    public ResponseEntity<?> signOutAllDevices(@PathVariable Long userId, HttpSession session)
            throws CustomException {
        LoginEntity admin = requireAdmin(session);
        int closed = sessionRegistryService.terminateAllForUser(userId);
        auditLogService.logSecurityEvent(admin.getId(), admin.getName(), "SECURITY_EVENT",
                "Admin signed out all devices (" + closed + " sessions) for user #" + userId);
        return ResponseEntity.ok(Map.of("message", closed + " session(s) signed out"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Activity timeline (hot + archive)
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/activities")
    public ResponseEntity<?> activities(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "false") boolean archive,
            HttpSession session) throws CustomException {

        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.activities(
                userId, startOf(dateFrom), endOf(dateTo), module, operation, status,
                search, page, size, sortBy, sortDir, archive));
    }

    @GetMapping("/activities/{id}")
    public ResponseEntity<?> activityById(@PathVariable Long id,
                                          @RequestParam(defaultValue = "false") boolean archive,
                                          HttpSession session) throws CustomException {
        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.activityById(id, archive));
    }

    // ═════════════════════════════════════════════════════════════════════
    // User details drawer
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/users/{userId}/summary")
    public ResponseEntity<?> userSummary(@PathVariable Long userId, HttpSession session)
            throws CustomException {
        requireAdmin(session);
        return ResponseEntity.ok(loginActivityService.userSummary(userId, session.getId()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Page-view tracking beacon — any authenticated user
    // ═════════════════════════════════════════════════════════════════════

    @PostMapping("/track")
    public ResponseEntity<?> track(@RequestBody List<Map<String, String>> events,
                                   HttpServletRequest request, HttpSession session) {
        if (session == null || session.getAttribute("USER_ID") == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok(Map.of("tracked", 0));
        }
        int accepted = 0;
        for (Map<String, String> ev : events) {
            if (accepted >= 50) break; // per-request ceiling
            String page = ev.get("page");
            if (page == null || page.isBlank()) continue;
            AuditLogEntity e = auditLogService.base(
                    ev.getOrDefault("module", "NAVIGATION"),
                    "VIEW",
                    page,
                    "Opened " + page);
            auditLogService.enqueue(e);
            accepted++;
        }
        return ResponseEntity.ok(Map.of("tracked", accepted));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Late location report — any authenticated user.
    // Sent by the frontend once the browser location permission is granted
    // (the permission popup may still be open when login completes).
    // ═════════════════════════════════════════════════════════════════════

    @PostMapping("/geo")
    public ResponseEntity<?> reportGeo(@RequestBody Map<String, String> body,
                                       HttpSession session) {
        Object uid = (session != null) ? session.getAttribute("USER_ID") : null;
        if (uid == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Double lat = Double.valueOf(body.get("latitude"));
            Double lng = Double.valueOf(body.get("longitude"));
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid coordinates"));
            }
            sessionRegistryService.updateGeo(session.getId(), (Long) uid, lat, lng,
                    trim(body.get("city")), trim(body.get("state")), trim(body.get("country")));
            session.setAttribute("SESSION_LAT", lat);
            session.setAttribute("SESSION_LNG", lng);
            return ResponseEntity.ok(Map.of("message", "Location updated"));
        } catch (NumberFormatException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid coordinates"));
        }
    }

    private static String trim(String v) {
        if (v == null) return null;
        v = v.trim();
        return v.length() > 100 ? v.substring(0, 100) : (v.isEmpty() ? null : v);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Self-service "my sessions" — Profile page. Any authenticated user can
    // view/manage their OWN devices only; unlike /active-sessions above,
    // this is NOT admin-gated and never exposes other users' sessions.
    // ═════════════════════════════════════════════════════════════════════

    @GetMapping("/my-sessions")
    public ResponseEntity<?> mySessions(HttpSession session) {
        Long uid = currentUserId(session);
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<ActiveSessionDto> dtos = sessionRegistryService.findActiveForUser(uid).stream()
                .map(s -> sessionRegistryService.toDto(s, session.getId()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @DeleteMapping("/my-sessions/{id}")
    public ResponseEntity<?> terminateMySession(@PathVariable Long id, HttpSession session) {
        Long uid = currentUserId(session);
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean ok = sessionRegistryService.terminateOwnSession(id, uid);
        if (!ok) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Session not found"));
        return ResponseEntity.ok(Map.of("message", "Device signed out"));
    }

    @DeleteMapping("/my-sessions")
    public ResponseEntity<?> signOutOtherDevices(HttpSession session) {
        Long uid = currentUserId(session);
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int n = sessionRegistryService.terminateOthersForUser(uid, session.getId());
        return ResponseEntity.ok(Map.of("message", n + " device(s) signed out", "count", n));
    }

    private static Long currentUserId(HttpSession session) {
        if (session == null) return null;
        Object uid = session.getAttribute("USER_ID");
        return (uid instanceof Long) ? (Long) uid : null;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Guards + helpers
    // ═════════════════════════════════════════════════════════════════════

    private LoginEntity requireAdmin(HttpSession session) throws CustomException {
        if (session == null || session.getAttribute("USER_ID") == null) {
            throw new CustomException("Not authenticated");
        }
        Long userId = (Long) session.getAttribute("USER_ID");
        LoginEntity user = loginRepo.findById(userId)
                .orElseThrow(() -> new CustomException("Invalid User"));
        String role = user.getRole() == null ? "" : user.getRole().trim();
        if (!"SUPERADMIN".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
            throw new CustomException("You do not have permission to view Login Activity");
        }
        return user;
    }

    private static LocalDateTime startOf(LocalDate d) {
        return d == null ? null : d.atStartOfDay();
    }

    private static LocalDateTime endOf(LocalDate d) {
        return d == null ? null : d.plusDays(1).atStartOfDay();
    }
}