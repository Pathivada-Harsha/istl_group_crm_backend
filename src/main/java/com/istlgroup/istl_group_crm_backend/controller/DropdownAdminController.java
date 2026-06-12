package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.DropdownGroupEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.DropdownSubGroupEntity;
import com.istlgroup.istl_group_crm_backend.service.DropdownAdminService;
import com.istlgroup.istl_group_crm_backend.service.DropdownProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/dropdowns")
@RequiredArgsConstructor
public class DropdownAdminController {

    private final DropdownAdminService   adminService;
    private final DropdownProjectService projectService;

    // ── GROUPS ──────────────────────────────────────────────────────────────

    /**
     * Paginated list — used by the Dropdown Management admin page.
     * GET /admin/dropdowns/groups?page=0&size=10&search=solar
     *
     * Returns: { content:[...], totalElements, totalPages, size, number }
     */
    @GetMapping("/groups")
    public ResponseEntity<?> getAllGroups(
            @RequestParam(value = "page",   required = false, defaultValue = "-1")  int page,
            @RequestParam(value = "size",   required = false, defaultValue = "10")  int size,
            @RequestParam(value = "search", required = false, defaultValue = "")    String search) {

        // Backward-compatible: if no page param → return full list (for filter dropdowns)
        if (page < 0) {
            return ResponseEntity.ok(adminService.getAllGroupsAdmin());
        }
        Map<String, Object> result = adminService.getGroupsPaged(search, page, size);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/groups/{id}")
    public ResponseEntity<DropdownGroupEntity> getGroupById(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getGroupById(id));
    }

    @PostMapping("/groups")
    public ResponseEntity<DropdownGroupEntity> createGroup(@RequestBody DropdownGroupEntity group) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createGroup(group));
    }

    @PutMapping("/groups/{id}")
    public ResponseEntity<DropdownGroupEntity> updateGroup(
            @PathVariable Long id, @RequestBody DropdownGroupEntity group) {
        return ResponseEntity.ok(adminService.updateGroup(id, group));
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        adminService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    // ── SUBGROUPS ────────────────────────────────────────────────────────────

    /**
     * Paginated list — used by the Dropdown Management admin page.
     * GET /admin/dropdowns/subgroups?page=0&size=10&search=rooftop
     */
    @GetMapping("/subgroups")
    public ResponseEntity<?> getAllSubGroups(
            @RequestParam(value = "page",    required = false, defaultValue = "-1") int page,
            @RequestParam(value = "size",    required = false, defaultValue = "10") int size,
            @RequestParam(value = "search",  required = false, defaultValue = "")   String search,
            @RequestParam(value = "groupId", required = false)                      Long groupId) {

        if (page < 0) {
            return ResponseEntity.ok(adminService.getAllSubGroupsAdmin());
        }
        Map<String, Object> result = adminService.getSubGroupsPaged(search, page, size, groupId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/subgroups/{id}")
    public ResponseEntity<DropdownSubGroupEntity> getSubGroupById(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getSubGroupById(id));
    }

    @PostMapping("/subgroups")
    public ResponseEntity<DropdownSubGroupEntity> createSubGroup(
            @RequestBody DropdownSubGroupEntity subGroup,
            @RequestParam Long groupId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(adminService.createSubGroup(subGroup, groupId));
    }

    @PutMapping("/subgroups/{id}")
    public ResponseEntity<DropdownSubGroupEntity> updateSubGroup(
            @PathVariable Long id, @RequestBody DropdownSubGroupEntity subGroup) {
        return ResponseEntity.ok(adminService.updateSubGroup(id, subGroup));
    }

    @DeleteMapping("/subgroups/{id}")
    public ResponseEntity<Void> deleteSubGroup(@PathVariable Long id) {
        adminService.deleteSubGroup(id);
        return ResponseEntity.noContent().build();
    }

    // ── PROJECTS ─────────────────────────────────────────────────────────────

    /**
     * Paginated list — used by the Dropdown Management admin page.
     * GET /admin/dropdowns/projects?page=0&size=10&search=ccms
     */
    @GetMapping("/projects")
    public ResponseEntity<?> getAllProjects(
            @RequestParam(value = "page",       required = false, defaultValue = "-1") int page,
            @RequestParam(value = "size",       required = false, defaultValue = "10") int size,
            @RequestParam(value = "search",     required = false, defaultValue = "")   String search,
            @RequestParam(value = "groupId",    required = false)                       Long groupId,
            @RequestParam(value = "subGroupId", required = false)                       Long subGroupId) {

        if (page < 0) {
            return ResponseEntity.ok(adminService.getAllProjectsAdmin());
        }
        Map<String, Object> result = adminService.getProjectsPaged(search, page, size, groupId, subGroupId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<DropdownProjectEntity> getProjectById(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getProjectById(id));
    }

    @PostMapping("/projects")
    public ResponseEntity<DropdownProjectEntity> createProject(
            @RequestBody DropdownProjectEntity project,
            @RequestParam Long subGroupId,
            @RequestParam Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(projectService.createProject(project, subGroupId, userId));
    }

    @PutMapping("/projects/{projectUniqueId}")
    public ResponseEntity<DropdownProjectEntity> updateProject(
            @PathVariable String projectUniqueId,
            @RequestBody DropdownProjectEntity project) {
        return ResponseEntity.ok(projectService.updateProject(projectUniqueId, project));
    }

    @DeleteMapping("/projects/{projectUniqueId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectUniqueId) {
        projectService.deleteProject(projectUniqueId);
        return ResponseEntity.noContent().build();
    }
}