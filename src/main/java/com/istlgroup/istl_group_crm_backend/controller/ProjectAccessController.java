package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.entity.ProjectAccessEntity;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ProjectAccessController — simplified.
 *
 * Endpoints:
 *   GET    /projects/{projectId}/access          → list users who have access
 *   POST   /projects/{projectId}/access          → grant access to selected users
 *   DELETE /projects/{projectId}/access/{userId} → revoke a user's access
 *   GET    /projects/{projectId}/access/me       → check if I have access
 *
 * No access_role in request body — access is simply granted or revoked.
 * Which pages the user sees is controlled by their menu pagePermissions.
 */
@RestController
@RequestMapping("/projects/{projectId}/access")
@RequiredArgsConstructor
@Slf4j
public class ProjectAccessController {

    private final ProjectAccessService accessService;

    // ── List all grants for a project ─────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> listAccess(
            @PathVariable String projectId,
            @ActingUserRole String userRole) {

        if (!accessService.isAdmin(userRole)) {
            return forbidden("Only admins can list project access");
        }
        List<ProjectAccessEntity> grants = accessService.listProjectAccess(projectId);
        return ResponseEntity.ok(Map.of("success", true, "data", grants, "count", grants.size()));
    }

    // ── Grant access to one or more users ────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> grantAccess(
            @PathVariable String projectId,
            @ActingUserId Long   requesterId,
            @ActingUserRole String requesterRole,
            @RequestBody GrantRequest body) {

        if (!accessService.isAdmin(requesterRole)) {
            return forbidden("Only admins can grant project access");
        }
        if (body.getUserIds() == null || body.getUserIds().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "At least one userId is required"));
        }

        int count = 0;
        for (Long userId : body.getUserIds()) {
            accessService.grantAccess(projectId, userId, requesterId, body.getNote());
            count++;
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Access granted to " + count + " user(s)"
        ));
    }

    // ── Revoke access ─────────────────────────────────────────────────────────

    @DeleteMapping("/{userId}")
    public ResponseEntity<?> revokeAccess(
            @PathVariable String projectId,
            @PathVariable Long   userId,
            @ActingUserRole String requesterRole) {

        if (!accessService.isAdmin(requesterRole)) {
            return forbidden("Only admins can revoke project access");
        }
        accessService.revokeAccess(projectId, userId);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Access revoked for user " + userId
        ));
    }

    // ── Check my own access ───────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> myAccess(
            @PathVariable String projectId,
            @ActingUserId Long   userId,
            @ActingUserRole String userRole) {

        boolean hasAccess = accessService.canAccessProject(projectId, userId, userRole);
        if (!hasAccess) {
            return forbidden("You do not have access to this project");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId",  projectId);
        data.put("userId",     userId);
        data.put("hasAccess",  true);
        data.put("isBypass",   accessService.isBypassRole(userRole));

        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> forbidden(String msg) {
        return ResponseEntity.status(403)
            .body(Map.of("success", false, "message", msg));
    }

    // ── Request body ──────────────────────────────────────────────────────────

    public static class GrantRequest {
        private List<Long> userIds; // supports multi-user grant in one call
        private String     note;

        public List<Long> getUserIds() { return userIds; }
        public String     getNote()    { return note; }
        public void setUserIds(List<Long> u) { this.userIds = u; }
        public void setNote(String n)        { this.note = n; }
    }
}