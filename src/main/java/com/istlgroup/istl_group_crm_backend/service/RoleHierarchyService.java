package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.RoleHierarchyEntity;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;

@Service
public class RoleHierarchyService {

    @Autowired
    private RoleHierarchyRepo hierarchyRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<RoleHierarchyEntity> getAllHierarchies() {
        return hierarchyRepo.findAllOrderedByLevel();
    }

    public RoleHierarchyEntity getByRoleName(String roleName) throws CustomException {
        return hierarchyRepo.findByRoleName(roleName)
                .orElseThrow(() -> new CustomException("Role hierarchy not found for: " + roleName));
    }

    /**
     * Creates or updates a role hierarchy entry.
     * If a row with the same roleName already exists, it is updated.
     * Otherwise a new row is inserted.
     *
     * BUG FIX: Always set updatedAt before saving so the NOT NULL constraint
     * on the updated_at column is never violated.
     */
    public RoleHierarchyEntity saveHierarchy(RoleHierarchyEntity entity) throws CustomException {
        if (entity.getRoleName() == null || entity.getRoleName().isBlank())
            throw new CustomException("Role name is required");
        if (entity.getLevelOrder() == null || entity.getLevelOrder() < 1)
            throw new CustomException("Level order must be >= 1");

        // Normalise the role name itself
        entity.setRoleName(RoleNormalizer.normalizeRequired(entity.getRoleName()));

        // Normalise every entry in the JSON arrays
        entity.setCanAssignRoles(normalizeJsonRoleArray(entity.getCanAssignRoles()));
        entity.setCanSeeRoles(normalizeJsonRoleArray(entity.getCanSeeRoles()));

        // BUG FIX: set updated_at — required for both INSERT and UPDATE
        entity.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        // If entry exists, carry over createdAt (it's insertable=false, updatable=false)
        Optional<RoleHierarchyEntity> existing = hierarchyRepo.findByRoleName(entity.getRoleName());
        if (existing.isPresent()) {
            // UPDATE path: merge fields onto the managed entity
            RoleHierarchyEntity managed = existing.get();
            managed.setLevelOrder(entity.getLevelOrder());
            managed.setDescription(entity.getDescription());
            managed.setCanAssignRoles(entity.getCanAssignRoles());
            managed.setCanSeeRoles(entity.getCanSeeRoles());
            managed.setUpdatedAt(entity.getUpdatedAt());
            return hierarchyRepo.save(managed);
        }

        // INSERT path: new entry
        return hierarchyRepo.save(entity);
    }

    public RoleHierarchyEntity updateHierarchy(String roleName, RoleHierarchyEntity updated) throws CustomException {
        RoleHierarchyEntity existing = getByRoleName(roleName);
        existing.setLevelOrder(updated.getLevelOrder());
        existing.setDescription(updated.getDescription());

        // Normalise JSON arrays on every update
        existing.setCanAssignRoles(normalizeJsonRoleArray(updated.getCanAssignRoles()));
        existing.setCanSeeRoles(normalizeJsonRoleArray(updated.getCanSeeRoles()));

        // BUG FIX: always set updatedAt so the NOT NULL column is satisfied
        existing.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        return hierarchyRepo.save(existing);
    }

    public void deleteHierarchy(String roleName) throws CustomException {
        getByRoleName(roleName);
        hierarchyRepo.deleteById(roleName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses a JSON array string, uppercases every entry, and returns a new
     * normalised JSON array string.  "[]" and null both become "[]".
     */
    private String normalizeJsonRoleArray(String jsonArray) {
        List<String> raw = parseRoleList(jsonArray);
        if (raw.isEmpty()) return "[]";
        List<String> normalised = raw.stream()
                .map(RoleNormalizer::normalize)
                .filter(r -> r != null && !r.isBlank())
                .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(normalised);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Parses a JSON role array and returns values already UPPERCASED.
     * Any mixed-case values ("Telecaller", "Bd_Executive") are silently corrected.
     */
    public List<String> parseRoleList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank() || jsonArray.equals("[]"))
            return Collections.emptyList();
        try {
            List<String> raw = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {});
            return raw.stream()
                      .filter(r -> r != null && !r.isBlank())
                      .map(RoleNormalizer::normalize)
                      .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns the raw canAssignRoles list (normalised).
     * EMPTY list means "self-assign only".
     */
    public List<String> getCanAssignRoles(String roleName) {
        Optional<RoleHierarchyEntity> opt = hierarchyRepo.findByRoleName(
                RoleNormalizer.normalize(roleName));
        if (opt.isEmpty()) return Collections.emptyList();
        return parseRoleList(opt.get().getCanAssignRoles());
    }

    /** Legacy — returns [roleName] when canAssignRoles is empty. */
    public List<String> getAssignableRoles(String roleName) {
        List<String> roles = getCanAssignRoles(roleName);
        if (roles.isEmpty()) return List.of(RoleNormalizer.normalize(roleName));
        return roles;
    }

    /** Returns visible roles (normalised) for the Users page. */
    public List<String> getVisibleRoles(String roleName) {
        Optional<RoleHierarchyEntity> opt = hierarchyRepo.findByRoleName(
                RoleNormalizer.normalize(roleName));
        if (opt.isEmpty()) return Collections.emptyList();
        return parseRoleList(opt.get().getCanSeeRoles());
    }

    /** True when levelOrder <= 2 (SUPERADMIN or ADMIN). */
    public boolean isTopLevelRole(String roleName) {
        Optional<RoleHierarchyEntity> opt = hierarchyRepo.findByRoleName(
                RoleNormalizer.normalize(roleName));
        return opt.map(e -> e.getLevelOrder() <= 2).orElse(false);
    }
    public int getLevelOrder(String roleName) {
        return hierarchyRepo.findLevelOrderByRoleName(
                RoleNormalizer.normalize(roleName))
            .orElse(Integer.MAX_VALUE);
    }
}