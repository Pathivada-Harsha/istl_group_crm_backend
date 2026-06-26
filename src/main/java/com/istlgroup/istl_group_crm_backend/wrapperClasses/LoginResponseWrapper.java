package com.istlgroup.istl_group_crm_backend.wrapperClasses;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * LoginResponseWrapper (v2 — added visibleRoles)
 * ────────────────────────────────────────────────
 * Added: visibleRoles — the canSeeRoles list from role_hierarchy for this
 * user's role. Frontend stores this in AuthContext and sends it with every
 * AI chat request so the bot knows which roles' data to include.
 *
 * EXISTING FIELDS UNCHANGED — no other code needs to change.
 */
@Data
public class LoginResponseWrapper {

    private LoginCredentialsWrapper user;

    private List<String> menuPermissions;
    private Map<String, List<String>> pagePermissions;

    private Long sessionTimeout;
    private Long warningTime;

    /**
     * Roles whose data this user can see, from role_hierarchy.can_see_roles.
     * Examples:
     *   SALES_MANAGER  → ["SALES_EXEC","TELECALLER","MANAGER"]
     *   BD_EXECUTIVE   → []   (sees only own data)
     *   SUPERADMIN     → []   (sees ALL — empty means global, handled by isAdmin check)
     *
     * Frontend stores this in AuthContext as visibleRoles and sends it
     * in every AI chat request body.
     */
    private List<String> visibleRoles;
}