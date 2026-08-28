package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.service.DropdownFilterService;
import com.istlgroup.istl_group_crm_backend.service.DropdownProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class DropdownProjectController {
    
    private final DropdownProjectService projectService;
    private final DropdownFilterService filterService;

    /**
     * GET /api/projects?search=&groupId=&subGroupName=
     * Returns all active projects, optionally filtered by search term, group and subgroup.
     * Used by Project Access Control page and Task dropdowns.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllProjects(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String status,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {

        // Per-user visibility (mirrors the Order Book list): bypass roles
        // (SUPERADMIN / ADMIN / ACCOUNTS_* / level ≤ 2) see all; everyone else sees
        // only projects granted to them (project_access) or that they created / are
        // assigned to. Identity comes from the authenticated session, not from the
        // User-Id / User-Role headers the frontend still sends and the server ignores.
        List<Map<String, Object>> all = filterService.getAllActiveProjects(userId, userRole);

        return ResponseEntity.ok(all.stream()
            .filter(p -> {
                // Status filter — case-insensitive exact match on project status
                if (status != null && !status.isBlank()) {
                    String st = String.valueOf(p.getOrDefault("status", ""));
                    if (!status.equalsIgnoreCase(st)) return false;
                }
                // Search filter — checks projectName and projectUniqueId
                if (search != null && !search.isBlank()) {
                    String q    = search.toLowerCase();
                    String name = String.valueOf(p.getOrDefault("projectName", "")).toLowerCase();
                    String id   = String.valueOf(p.getOrDefault("projectUniqueId", "")).toLowerCase();
                    if (!name.contains(q) && !id.contains(q)) return false;
                }
                // Group filter — match either the raw group_id or the resolved
                // group name, so callers passing a group name (Projects list
                // dropdown) and callers passing a raw id (access/task dropdowns)
                // both work.
                if (groupId != null && !groupId.isBlank()) {
                    String g  = String.valueOf(p.getOrDefault("groupId", ""));
                    String gn = String.valueOf(p.getOrDefault("groupName", ""));
                    if (!groupId.equals(g) && !groupId.equalsIgnoreCase(gn)) return false;
                }
                // SubGroup filter
                if (subGroupName != null && !subGroupName.isBlank()) {
                    String sg = String.valueOf(p.getOrDefault("subGroupName", ""));
                    if (!subGroupName.equals(sg)) return false;
                }
                return true;
            })
            .collect(java.util.stream.Collectors.toList())
        );
    }

    /**
     * GET /api/projects/{projectUniqueId}
     * Returns a single project by its unique ID.
     */
    @GetMapping("/{projectUniqueId}")
    public ResponseEntity<DropdownProjectEntity> getProject(@PathVariable String projectUniqueId) {
        return ResponseEntity.ok(filterService.getProjectByUniqueId(projectUniqueId));
    }
    
    @PostMapping
    public ResponseEntity<DropdownProjectEntity> createProject(
            @RequestBody DropdownProjectEntity project,
            @RequestParam Long subGroupId,
            @RequestParam Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(projectService.createProject(project, subGroupId, userId));
    }
    
    /**
     * PATCH /api/projects/{id}/status-progress
     * Lightweight endpoint to update only status + progressPercentage.
     * Body: { "status": "IN_PROGRESS", "progressPercentage": 65.0 }
     */
    @PatchMapping("/{projectUniqueId}/status-progress")
    public ResponseEntity<DropdownProjectEntity> updateStatusAndProgress(
            @PathVariable String projectUniqueId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(projectService.updateStatusAndProgress(projectUniqueId, body));
    }

    @PutMapping("/{projectUniqueId}")
    public ResponseEntity<DropdownProjectEntity> updateProject(
            @PathVariable String projectUniqueId,
            @RequestBody DropdownProjectEntity project) {
        return ResponseEntity.ok(projectService.updateProject(projectUniqueId, project));
    }
    
    @DeleteMapping("/{projectUniqueId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectUniqueId) {
        projectService.deleteProject(projectUniqueId);
        return ResponseEntity.noContent().build();
    }
}