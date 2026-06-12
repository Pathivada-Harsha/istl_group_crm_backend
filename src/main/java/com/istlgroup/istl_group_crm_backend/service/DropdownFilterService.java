package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownProjectWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsUserWrapper;
import com.istlgroup.istl_group_crm_backend.entity.DropdownProjectEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.DropdownGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.DropdownSubGroupRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropdownFilterService {

    private final UsersRepo usersRepo;
    private final DropdownGroupRepository groupRepository;
    private final DropdownSubGroupRepository subGroupRepository;
    private final DropdownProjectRepository projectRepository;
    private final RoleHierarchyService roleHierarchyService;

    public List<DropdownGroupWrapper> getAllGroups() {
        return groupRepository.findByIsActiveTrue().stream()
            .map(g -> new DropdownGroupWrapper(g.getGroupName(), g.getGroupLabel()))
            .collect(Collectors.toList());
    }

    public List<DropdownSubGroupWrapper> getSubGroupsByGroup(String groupName) {
        return subGroupRepository.findByGroupNameAndIsActiveTrue(groupName).stream()
            .map(sg -> new DropdownSubGroupWrapper(sg.getSubGroupName(), sg.getSubGroupLabel()))
            .collect(Collectors.toList());
    }

    public List<DropdownProjectWrapper> getProjectsByGroupAndSubGroup(String groupName, String subGroupName) {
        return projectRepository.findByGroupAndSubGroup(groupName, subGroupName).stream()
            .map(p -> new DropdownProjectWrapper(
                p.getProjectUniqueId(), p.getProjectName(), p.getLocation(),
                p.getStatus() != null ? p.getStatus().name() : null))
            .collect(Collectors.toList());
    }

    public DropdownProjectEntity getProjectByUniqueId(String projectUniqueId) {
        return projectRepository.findByProjectUniqueId(projectUniqueId)
            .orElseThrow(() -> new RuntimeException("Project not found: " + projectUniqueId));
    }

    public List<Map<String, Object>> getAllActiveProjects() {
        return projectRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
            .sorted(Comparator.comparing(p -> p.getProjectName() != null ? p.getProjectName() : ""))
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projectUniqueId", p.getProjectUniqueId());
                m.put("projectName",     p.getProjectName());
                m.put("status",          p.getStatus() != null ? p.getStatus().name() : null);
                m.put("location",        p.getLocation());
                m.put("groupId",         p.getGroup_id());
                m.put("subGroupName",    p.getSubGroupName());
                return m;
            })
            .collect(Collectors.toList());
    }

    /**
     * Returns the users the caller can assign a follow-up / task / lead to.
     *
     * Decision tree:
     *  1. No role info → all active users (safe fallback for old clients)
     *  2. SUPERADMIN / ADMIN (levelOrder ≤ 2) → all active users, caller first
     *  3. canAssignRoles is EMPTY → self only
     *  4. canAssignRoles is NON-EMPTY:
     *       a. Caller has a team AND there are users with those roles in that team
     *          → return only same-team users (proper team scoping)
     *       b. Caller has a team BUT no matching users exist in that team
     *          → fall back to all users with those roles (team not fully configured yet)
     *       c. Caller has no team → all users with those roles
     *     Caller always included at position 0.
     *
     * All role comparisons are case-insensitive (UPPER() in SQL + parseRoleList uppercases JSON).
     */
    public List<LeadsUserWrapper> getLeadsUsers(Long currentUserId, String currentUserRole) {

        // ── 1. Fallback ───────────────────────────────────────────────────────
        if (currentUserRole == null || currentUserRole.isBlank()) {
            return usersRepo.findAllActiveUsers().stream()
                .map(this::toWrapper).collect(Collectors.toList());
        }

        String roleUpper = currentUserRole.toUpperCase();

        // ── 2. SUPERADMIN / ADMIN → everyone ─────────────────────────────────
        if (roleHierarchyService.isTopLevelRole(roleUpper)) {
            return usersRepo.findAllActiveUsers().stream()
                .map(this::toWrapper)
                .sorted(callerFirst(currentUserId))
                .collect(Collectors.toList());
        }

        // Get the raw canAssignRoles list (already UPPERCASED by parseRoleList)
        List<String> canAssign = roleHierarchyService.getCanAssignRoles(roleUpper);

        // ── 3. Empty list → self only ─────────────────────────────────────────
        if (canAssign.isEmpty()) {
            if (currentUserId == null) return List.of();
            return usersRepo.findById(currentUserId)
                .map(u -> List.of(toWrapper(u)))
                .orElse(List.of());
        }

        // ── 4. Has assignable roles ────────────────────────────────────────────
        Optional<UsersEntity> callerOpt = currentUserId != null
            ? usersRepo.findById(currentUserId) : Optional.empty();
        String callerTeam = callerOpt.map(UsersEntity::getTeam).orElse(null);

        List<UsersEntity> candidates;

        if (callerTeam != null && !callerTeam.isBlank()) {
            // Try team-scoped query first
            List<UsersEntity> teamScoped = new ArrayList<>(
                usersRepo.findActiveUsersByRolesAndTeam(canAssign, callerTeam));

            if (!teamScoped.isEmpty()) {
                // Team scoping worked → use it
                candidates = teamScoped;
            } else {
                // Team scoping returned nothing — target users may not have their team set yet
                // Fall back to role-only filter so the dropdown is never empty
                candidates = new ArrayList<>(usersRepo.findActiveUsersByRoles(canAssign));
            }
        } else {
            // Caller has no team → role-only filter (no team restriction)
            candidates = new ArrayList<>(usersRepo.findActiveUsersByRoles(canAssign));
        }

        // Always include the caller at position 0
        if (currentUserId != null) {
            final Long cid = currentUserId;
            boolean present = candidates.stream().anyMatch(u -> u.getId().equals(cid));
            if (!present) callerOpt.ifPresent(candidates::add);
        }

        return candidates.stream()
            .map(this::toWrapper)
            .sorted(callerFirst(currentUserId))
            .collect(Collectors.toList());
    }

    // Legacy no-arg (kept for old internal callers)
    public List<LeadsUserWrapper> getLeadsUsers() {
        return getLeadsUsers(null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LeadsUserWrapper toWrapper(UsersEntity u) {
        return new LeadsUserWrapper(
            u.getId(),
            u.getName() != null ? u.getName() : u.getUser_id(),
            u.getAvatar_url(),
            u.getPhone());
    }

    private Comparator<LeadsUserWrapper> callerFirst(Long callerId) {
        return Comparator.comparingInt(
            w -> (callerId != null && w.getId().equals(callerId)) ? 0 : 1);
    }
}