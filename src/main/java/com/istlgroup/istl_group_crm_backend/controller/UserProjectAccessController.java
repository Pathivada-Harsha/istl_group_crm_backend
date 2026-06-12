package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.ProjectAccessEntity;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Separate controller for user-centric project access queries.
 * (The main ProjectAccessController is scoped to /projects/{projectId}/access)
 *
 * Endpoint:
 *   GET /projects/access/user/{userId}
 *       → Returns all project grants for a specific user.
 *       → Used by the Users View tab in ProjectAccessPage to show
 *          which projects each user currently has access to.
 *
 * Response shape:
 *   { success: true, data: [ { id, projectId, userId, grantedBy, grantedAt, note, accessRole }, ... ] }
 */
@RestController
@RequestMapping("/projects/access/user")
@RequiredArgsConstructor
public class UserProjectAccessController {

    private final ProjectAccessService accessService;

    @GetMapping("/{userId}")
    public ResponseEntity<?> listAccessForUser(
            @PathVariable Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (!accessService.isAdmin(userRole)) {
            return ResponseEntity.status(403)
                .body(Map.of("success", false, "message", "Only admins can view user access"));
        }

        List<ProjectAccessEntity> grants = accessService.listUserAccess(userId);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data",    grants,
            "count",   grants.size()
        ));
    }
}