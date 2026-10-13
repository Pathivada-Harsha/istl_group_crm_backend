package com.istlgroup.istl_group_crm_backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;

/**
 * The record-visibility rule shared by Clients and Tenders (both carry a creator
 * and an assignee):
 * <ul>
 *   <li>hierarchy level 1-2, or an {@code ACCOUNTS_*} role: every record;</li>
 *   <li>level 3 (team lead): records created by / assigned to themselves or their team;</li>
 *   <li>everyone else: records they created or are assigned to.</li>
 * </ul>
 * Kept in one place so a tender and its generated client can never disagree about who may see them.
 */
@Component
public class RecordAccessPolicy {

    @Autowired private RoleHierarchyService roleHierarchyService;
    @Autowired private TeamRepository teamRepository;

    /** Resolved once per request, then asked about many records. */
    public record Scope(boolean all, Set<Long> userIds) {
        public boolean canSee(Long createdBy, Long assignedTo) {
            return all
                    || (createdBy != null && userIds.contains(createdBy))
                    || (assignedTo != null && userIds.contains(assignedTo));
        }
    }

    public Scope scopeFor(Long userId, String userRole) {
        boolean accounts = userRole != null && userRole.toUpperCase().startsWith("ACCOUNTS_");
        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level <= 2 || accounts) return new Scope(true, Set.of());
        Set<Long> ids = new HashSet<>();
        if (userId != null) ids.add(userId);
        if (level == 3) {
            List<Long> team = teamRepository.findTeamMemberIdsByUserId(userId);
            if (team != null) ids.addAll(team);
        }
        return new Scope(false, ids);
    }

    public boolean canAccess(Long createdBy, Long assignedTo, Long userId, String userRole) {
        return scopeFor(userId, userRole).canSee(createdBy, assignedTo);
    }
}
