package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.ProjectAccessEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectAccessRepository;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ProjectAccessService — simplified model.
 *
 * A user either has a grant for a project or they don't.
 * Which pages they can see is controlled by pagePermissions (menu access).
 *
 * Bypass rules (no grant needed):
 *   SUPERADMIN / ADMIN  → always have access to all projects
 *   ACCOUNTS_*          → always have access to all projects (financial oversight)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectAccessRepository accessRepo;
    private final RoleHierarchyRepo roleHierarchyRepo;

    // ── Bypass checks ─────────────────────────────────────────────────────────

    public boolean isAdmin(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return r.equals("SUPERADMIN") || r.equals("ADMIN");
    }

    public boolean isAccountsRole(String role) {
        return role != null && role.trim().toUpperCase().startsWith("ACCOUNTS_");
    }

    /** Returns true if the user bypasses the project_access table entirely. */
    public boolean isBypassRole(String role) {
        if (isAdmin(role) || isAccountsRole(role)) return true;
        // Also bypass for any role at hierarchy level <= 2 (top-level admin tier)
        if (role == null) return false;
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(role))
                .map(level -> level <= 2)
                .orElse(false);
    }

    // ── Core access check ─────────────────────────────────────────────────────

    /**
     * Returns true if the user may access this project's data.
     *
     * Logic:
     *   SUPERADMIN / ADMIN / ACCOUNTS_*  → always true (bypass)
     *   Everyone else                    → must have a row in project_access
     */
    public boolean canAccessProject(String projectId, Long userId, String userRole) {
        if (isBypassRole(userRole)) return true;
        boolean hasGrant = accessRepo.findByProjectIdAndUserId(projectId, userId).isPresent();
        log.debug("canAccessProject | project={} user={} role={} hasGrant={}",
                  projectId, userId, userRole, hasGrant);
        return hasGrant;
    }

    // ── Project list for dropdown ─────────────────────────────────────────────

    /**
     * Returns all project IDs this user has been granted access to.
     * Bypass roles (admin/accounts) return null — caller should fetch all projects.
     */
    public List<String> getAccessibleProjectIds(Long userId, String userRole) {
        if (isBypassRole(userRole)) return null; // null = no restriction, show all
        return accessRepo.findProjectIdsByUserId(userId);
    }

    // ── Grant / revoke ────────────────────────────────────────────────────────

    @Transactional
    public ProjectAccessEntity grantAccess(String projectId, Long userId,
                                            Long grantedBy, String note) {
        ProjectAccessEntity entity = accessRepo.findByProjectIdAndUserId(projectId, userId)
            .orElse(ProjectAccessEntity.builder()
                .projectId(projectId)
                .userId(userId)
                .accessRole("GRANTED")
                .grantedBy(grantedBy)
                .build());

        entity.setGrantedBy(grantedBy);
        entity.setNote(note);
        entity.setAccessRole("GRANTED");

        ProjectAccessEntity saved = accessRepo.save(entity);
        log.info("Project access granted | project={} user={} by={}", projectId, userId, grantedBy);
        return saved;
    }

    @Transactional
    public void revokeAccess(String projectId, Long userId) {
        accessRepo.deleteByProjectIdAndUserId(projectId, userId);
        log.info("Project access revoked | project={} user={}", projectId, userId);
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    public List<ProjectAccessEntity> listProjectAccess(String projectId) {
        return accessRepo.findByProjectIdOrderByGrantedAtDesc(projectId);
    }

    /** All grants for a specific user — used by Users View tab in Access Manager. */
    public List<ProjectAccessEntity> listUserAccess(Long userId) {
        return accessRepo.findByUserIdOrderByGrantedAtDesc(userId);
    }
}