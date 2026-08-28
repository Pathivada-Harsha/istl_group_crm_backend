package com.istlgroup.istl_group_crm_backend.security;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.UserMenuPermissionRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserPagePermissionRepo;
import com.istlgroup.istl_group_crm_backend.service.LoginService;
import com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService;

import lombok.RequiredArgsConstructor;

/**
 * The caller's permission set, rebuilt from the database for the session's user id.
 *
 * <p>Exists because {@code POST /ai-assistant/chat} used to take {@code menuPermissions},
 * {@code pagePermissions}, {@code visibleRoles} and a {@code userContext} <b>out of the
 * request body</b> and hand them to {@code AiPermissionService}. A caller could therefore
 * post themselves an arbitrary permission set — {@code "role": "SUPERADMIN"} and every
 * module listed — and have the assistant answer questions over data they cannot otherwise
 * reach. Client-supplied authorization in a body field is the same bug as in a header.
 *
 * <p>Every value here comes from exactly the same source login uses, so what the assistant
 * believes about a caller now matches what the rest of the app believes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActingUserPermissionsService {

    private final UserMenuPermissionRepo userMenuRepo;
    private final UserPagePermissionRepo userPagePermissionRepo;
    private final RoleHierarchyService roleHierarchyService;
    private final LoginService loginService;

    /** Menu names granted to this user, exactly as login computes them. */
    public List<String> menuPermissions(Long userId) {
        List<String> names = userMenuRepo.findMenuNamesByUserId(userId);
        return names == null ? List.of() : names;
    }

    /** Page permissions as MODULE → [ACTION, …], exactly as login computes them. */
    public Map<String, List<String>> pagePermissions(Long userId) {
        return loginService.buildPermissionsMapFromNames(
                userPagePermissionRepo.findPermissionNamesByUserId(userId));
    }

    /** Roles this user's own role is allowed to see (role_hierarchy.can_see_roles). */
    public List<String> visibleRoles(String role) {
        List<String> roles = roleHierarchyService.getVisibleRoles(role);
        return roles == null ? List.of() : roles;
    }

    /** The identity block the assistant describes the caller with. */
    public com.istlgroup.istl_group_crm_backend.dto.AiChatRequest.UserContext userContext(UsersEntity user) {
        var ctx = new com.istlgroup.istl_group_crm_backend.dto.AiChatRequest.UserContext();
        ctx.setName(user.getName());
        ctx.setEmail(user.getEmail());
        ctx.setPhone(user.getPhone());
        ctx.setRole(user.getRole());
        ctx.setUserId(user.getUser_id());
        ctx.setDesignation(user.getDesignation());
        ctx.setTeam(user.getTeam());
        return ctx;
    }
}
