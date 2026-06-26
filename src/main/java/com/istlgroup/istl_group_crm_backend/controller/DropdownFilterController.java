package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownProjectWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsUserWrapper;
import com.istlgroup.istl_group_crm_backend.service.DropdownFilterService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/filters")
@RequiredArgsConstructor
public class DropdownFilterController {

    private final DropdownFilterService filterService;
    private final ProjectAccessService  projectAccessService;

    @GetMapping("/groups")
    public ResponseEntity<List<DropdownGroupWrapper>> getAllGroups(
            @RequestHeader(value = "User-Id",   required = false) Long   userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        // Always return all groups regardless of project access.
        // Project-level access filtering is applied only at the /projects endpoint.
        return ResponseEntity.ok(filterService.getAllGroups());
    }

    @GetMapping("/subgroups")
    public ResponseEntity<List<DropdownSubGroupWrapper>> getSubGroups(
            @RequestParam String groupName,
            @RequestHeader(value = "User-Id",   required = false) Long   userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        // Always return all subgroups for the given group regardless of project access.
        // Project-level access filtering is applied only at the /projects endpoint.
        return ResponseEntity.ok(filterService.getSubGroupsByGroup(groupName));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<DropdownProjectWrapper>> getProjects(
            @RequestParam String groupName,
            @RequestParam String subGroupName,
            @RequestHeader(value = "User-Id",   required = false) Long   userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        List<DropdownProjectWrapper> allProjects =
            filterService.getProjectsByGroupAndSubGroup(groupName, subGroupName);

        // Admins and ACCOUNTS_* see all projects — no filtering
        // Also treat missing headers as bypass (unauthenticated internal call)
        if (userId == null || userRole == null || userRole.isBlank()
                || projectAccessService.isBypassRole(userRole)) {
            return ResponseEntity.ok(allProjects);
        }

        // Everyone else: only show projects they have a grant for
        List<String> accessibleIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
        if (accessibleIds == null || accessibleIds.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<DropdownProjectWrapper> filtered = allProjects.stream()
            .filter(p -> accessibleIds.contains(p.getId()))
            .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(filtered);
    }

    @GetMapping("/leads-groups")
    public ResponseEntity<List<LeadsGroupWrapper>> getLeadsGroups() {
        return ResponseEntity.ok(
            filterService.getAllGroups().stream()
                .map(g -> new LeadsGroupWrapper(g.getValue(), g.getLabel()))
                .collect(Collectors.toList())
        );
    }

    @GetMapping("/leads-subgroups")
    public ResponseEntity<List<LeadsSubGroupWrapper>> getLeadsSubGroups(@RequestParam String groupName) {
        return ResponseEntity.ok(
            filterService.getSubGroupsByGroup(groupName).stream()
                .map(sg -> new LeadsSubGroupWrapper(sg.getValue(), sg.getLabel()))
                .collect(Collectors.toList())
        );
    }

    /**
     * GET /filters/leads-users
     * ─────────────────────────────────────────────────────────────────────────
     * Returns the list of users the caller is allowed to assign a follow-up to,
     * scoped by role hierarchy AND team membership.
     *
     * Required headers: User-Id, User-Role
     *
     * Rules (enforced in DropdownFilterService):
     *   SUPERADMIN / ADMIN  → all active users
     *   Mid-level roles     → users with assignable roles, same team only
     *   Bottom-level roles  → only themselves
     */
    @GetMapping("/leads-users")
    public ResponseEntity<List<LeadsUserWrapper>> getLeadsUsers(
            @RequestHeader(value = "User-Id",   required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        return ResponseEntity.ok(filterService.getLeadsUsers(userId, userRole));
    }

    /**
     * GET /filters/all-users
     * ─────────────────────────────────────────────────────────────────────────
     * Returns ALL active users (id, name, avatar_url) with no role-scoping.
     * Intended for display-only purposes such as rendering lead owner avatars
     * in table columns where any user may be an owner regardless of the
     * caller's role.
     */
    @GetMapping("/all-users")
    public ResponseEntity<List<LeadsUserWrapper>> getAllUsers() {
        return ResponseEntity.ok(filterService.getLeadsUsers());
    }

    /**
     * GET /filters/followup-assignees
     * ─────────────────────────────────────────────────────────────────────────
     * Returns users the caller can assign a follow-up to.
     * Team-first scoping: if the caller belongs to a team, all active teammates
     * are returned (any role). SUPERADMIN / ADMIN get everyone.
     * Deliberately separate from /leads-users so other dropdowns are unaffected.
     *
     * Required headers: User-Id, User-Role
     */
    @GetMapping("/followup-assignees")
    public ResponseEntity<List<LeadsUserWrapper>> getFollowupAssignees(
            @RequestHeader(value = "User-Id",   required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        return ResponseEntity.ok(filterService.getFollowupAssignees(userId, userRole));
    }
}