package com.istlgroup.istl_group_crm_backend.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;

import lombok.RequiredArgsConstructor;

/**
 * The one answer to "which users may this user act on?".
 *
 * <p>The rule, deliberately replacing the older role-allow-list + free-text-team
 * logic:
 * <ul>
 *   <li><b>Top-level user</b> (role hierarchy level 1 or 2 — today SUPERADMIN,
 *       ADMIN, ACCOUNTS_CFO) → every active user.</li>
 *   <li><b>Everyone else</b> → their <i>reporting subtree</i>: themselves plus
 *       every user who reports to them, transitively, all the way down.</li>
 * </ul>
 * A user with no reports therefore resolves to just themselves. Inactive users
 * are never in a set, and never conduct reporting either — an inactive manager
 * does not bridge their own reports up to their manager.
 *
 * <p><b>The acting user's role is read from the database, never from a request
 * header.</b> Headers are client-supplied: {@code ProjectCostExpenseManagement.js}
 * and {@code Procurement-Vendor-Management.js} both send a literal
 * {@code User-Role: SUPERADMIN} on purpose. That is harmless for a display-only
 * dropdown but would make the write-side guard trivially bypassable, so this
 * service resolves the role itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserScopeService {

    private final UsersRepo usersRepo;
    private final RoleHierarchyService roleHierarchyService;

    /** True when the acting user's stored role sits at hierarchy level 1 or 2. */
    public boolean isTopLevel(Long actingUserId) {
        if (actingUserId == null) return false;
        return usersRepo.findById(actingUserId)
                .map(u -> u.getRole() != null && roleHierarchyService.isTopLevelRole(u.getRole()))
                .orElse(false);
    }

    /**
     * The ids the acting user may act on. Empty only when the acting user does
     * not exist at all — a leaf user always gets back at least themselves.
     */
    public Set<Long> getActionableUserIds(Long actingUserId) {
        if (actingUserId == null) return Collections.emptySet();

        UsersEntity acting = usersRepo.findById(actingUserId).orElse(null);
        if (acting == null) return Collections.emptySet();

        if (acting.getRole() != null && roleHierarchyService.isTopLevelRole(acting.getRole())) {
            Set<Long> all = new LinkedHashSet<>();
            for (UsersEntity u : usersRepo.findAllActiveUsers()) all.add(u.getId());
            all.add(actingUserId);          // never drop the caller, even if flagged inactive
            return all;
        }

        return reportingSubtree(actingUserId);
    }

    /**
     * The acting user's actionable set as full user rows, caller first then by
     * name. Used to populate assignment dropdowns.
     */
    public List<UsersEntity> getActionableUsers(Long actingUserId) {
        Set<Long> ids = getActionableUserIds(actingUserId);
        if (ids.isEmpty()) return Collections.emptyList();

        List<UsersEntity> users = new ArrayList<>(usersRepo.findAllById(ids));
        users.sort((a, b) -> {
            boolean aSelf = a.getId().equals(actingUserId);
            boolean bSelf = b.getId().equals(actingUserId);
            if (aSelf != bSelf) return aSelf ? -1 : 1;
            String an = a.getName() == null ? "" : a.getName().toLowerCase();
            String bn = b.getName() == null ? "" : b.getName().toLowerCase();
            return an.compareTo(bn);
        });
        return users;
    }

    /**
     * Write-side guard: may {@code actingUserId} attribute something to
     * {@code targetUserId}? A null target means "no one" and is always allowed —
     * clearing an assignment is not an escalation.
     */
    public boolean canActOn(Long actingUserId, Long targetUserId) {
        if (targetUserId == null) return true;
        if (actingUserId != null && actingUserId.equals(targetUserId)) return true;
        return getActionableUserIds(actingUserId).contains(targetUserId);
    }

    /** Display name for a user id, for error messages. Falls back to the id. */
    public String displayName(Long userId) {
        if (userId == null) return "";
        return usersRepo.findById(userId)
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUser_id())
                .orElse("user #" + userId);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Breadth-first walk down the reporting graph from {@code rootId}, over
     * active users only. The visited set doubles as the cycle guard, so a
     * mis-entered manager_id loop terminates instead of hanging the request.
     */
    private Set<Long> reportingSubtree(Long rootId) {
        Map<Long, List<Long>> reportsByManager = new HashMap<>();
        for (Object[] edge : usersRepo.findActiveReportingEdges()) {
            Long id        = edge[0] == null ? null : ((Number) edge[0]).longValue();
            Long managerId = edge[1] == null ? null : ((Number) edge[1]).longValue();
            if (id == null || managerId == null) continue;
            reportsByManager.computeIfAbsent(managerId, k -> new ArrayList<>()).add(id);
        }

        Set<Long> subtree = new LinkedHashSet<>();
        Set<Long> seen    = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();

        subtree.add(rootId);
        seen.add(rootId);
        queue.add(rootId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long reportId : reportsByManager.getOrDefault(current, Collections.emptyList())) {
                if (seen.add(reportId)) {
                    subtree.add(reportId);
                    queue.add(reportId);
                }
            }
        }
        return subtree;
    }
}
