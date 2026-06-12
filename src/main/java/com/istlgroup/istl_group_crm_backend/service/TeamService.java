package com.istlgroup.istl_group_crm_backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.TeamEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.TeamRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TeamRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.TeamResponseWrapper;

@Service
public class TeamService {

    @Autowired
    private TeamRepository teamRepo;

    @Autowired
    private UsersRepo usersRepo;

    // ── GET ALL ───────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<TeamResponseWrapper> getAllTeams() {
        return teamRepo.findAllByOrderByNameAsc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── GET ONE ───────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public TeamResponseWrapper getTeamById(Long id) throws CustomException {
        TeamEntity team = teamRepo.findById(id)
                .orElseThrow(() -> new CustomException("Team not found with id: " + id));
        return toResponse(team);
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    @Transactional
    public TeamResponseWrapper createTeam(TeamRequestWrapper request, Long createdByUserId) throws CustomException {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new CustomException("Team name is required");
        }
        if (teamRepo.existsByNameIgnoreCase(request.getName().trim())) {
            throw new CustomException("A team with this name already exists");
        }

        TeamEntity team = new TeamEntity();
        team.setName(request.getName().trim());
        team.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        team.setCreatedBy(createdByUserId);

        Set<UsersEntity> members = resolveMembers(request.getMemberIds());
        team.setMembers(members);

        TeamEntity saved = teamRepo.save(team);

        // Sync users.team column for all new members
        syncUserTeamColumn(members, saved.getName());

        return toResponse(saved);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────
    @Transactional
    public TeamResponseWrapper updateTeam(Long id, TeamRequestWrapper request) throws CustomException {
        TeamEntity team = teamRepo.findById(id)
                .orElseThrow(() -> new CustomException("Team not found with id: " + id));

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new CustomException("Team name is required");
        }
        if (teamRepo.existsByNameIgnoreCaseExcludingId(request.getName().trim(), id)) {
            throw new CustomException("Another team with this name already exists");
        }

        String oldName = team.getName();
        String newName = request.getName().trim();

        // Capture old member IDs before replacing
        Set<Long> oldMemberIds = team.getMembers().stream()
                .map(UsersEntity::getId)
                .collect(Collectors.toSet());

        team.setName(newName);
        team.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);

        Set<UsersEntity> newMembers = resolveMembers(request.getMemberIds());
        Set<Long> newMemberIds = newMembers.stream()
                .map(UsersEntity::getId)
                .collect(Collectors.toSet());

        // Replace junction rows
        team.getMembers().clear();
        team.getMembers().addAll(newMembers);
        TeamEntity saved = teamRepo.save(team);

        // Users removed → clear their users.team if it still matches old name
        Set<Long> removedIds = new HashSet<>(oldMemberIds);
        removedIds.removeAll(newMemberIds);
        clearUserTeamColumn(removedIds, oldName);

        // Users now in the team → set users.team = new team name
        syncUserTeamColumn(newMembers, newName);

        // Team was renamed → update users who still had the old name
        if (!oldName.equals(newName)) {
            updateTeamNameOnUsers(newMemberIds, oldName, newName);
        }

        return toResponse(saved);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Transactional
    public void deleteTeam(Long id) throws CustomException {
        TeamEntity team = teamRepo.findById(id)
                .orElseThrow(() -> new CustomException("Team not found with id: " + id));

        String teamName = team.getName();

        Set<Long> memberIds = team.getMembers().stream()
                .map(UsersEntity::getId)
                .collect(Collectors.toSet());

        // Clear junction rows first
        team.getMembers().clear();
        teamRepo.save(team);
        teamRepo.deleteById(id);

        // Clear users.team for all ex-members
        clearUserTeamColumn(memberIds, teamName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Resolve list of user IDs to a set of UsersEntity, skipping missing ones. */
    private Set<UsersEntity> resolveMembers(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return new HashSet<>();
        return memberIds.stream()
                .map(uid -> usersRepo.findById(uid).orElse(null))
                .filter(u -> u != null)
                .collect(Collectors.toSet());
    }

    /** Set users.team = teamName for each user in the set. */
    private void syncUserTeamColumn(Set<UsersEntity> members, String teamName) {
        for (UsersEntity member : members) {
            member.setTeam(teamName);
            usersRepo.save(member);
        }
    }

    /**
     * For users removed from a team: clear users.team only if it currently
     * equals the old team name (protects users who were since moved to another team).
     */
    private void clearUserTeamColumn(Set<Long> userIds, String teamName) {
        for (Long uid : userIds) {
            usersRepo.findById(uid).ifPresent(u -> {
                if (teamName != null && teamName.equals(u.getTeam())) {
                    u.setTeam(null);
                    usersRepo.save(u);
                }
            });
        }
    }

    /** After a rename: update users who still carry the old team name. */
    private void updateTeamNameOnUsers(Set<Long> memberIds, String oldName, String newName) {
        for (Long uid : memberIds) {
            usersRepo.findById(uid).ifPresent(u -> {
                if (oldName.equals(u.getTeam())) {
                    u.setTeam(newName);
                    usersRepo.save(u);
                }
            });
        }
    }

    /** Convert TeamEntity to TeamResponseWrapper for the API response. */
    private TeamResponseWrapper toResponse(TeamEntity team) {
        TeamResponseWrapper resp = new TeamResponseWrapper();
        resp.setId(team.getId());
        resp.setName(team.getName());
        resp.setDescription(team.getDescription());
        resp.setCreatedBy(team.getCreatedBy());
        resp.setCreatedAt(team.getCreatedAt());
        resp.setUpdatedAt(team.getUpdatedAt());

        List<TeamResponseWrapper.TeamMemberSummary> memberList = team.getMembers().stream()
                .map(u -> {
                    TeamResponseWrapper.TeamMemberSummary s = new TeamResponseWrapper.TeamMemberSummary();
                    s.setId(u.getId());
                    s.setName(u.getName());
                    s.setRole(u.getRole());
                    return s;
                })
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .collect(Collectors.toList());

        resp.setMembers(memberList);
        resp.setMemberCount(memberList.size());

        // memberIds — plain ID list for frontend checkbox pre-selection
        resp.setMemberIds(
            memberList.stream()
                .map(TeamResponseWrapper.TeamMemberSummary::getId)
                .collect(Collectors.toList())
        );

        return resp;
    }
}