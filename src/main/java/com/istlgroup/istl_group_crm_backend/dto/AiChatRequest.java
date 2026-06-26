package com.istlgroup.istl_group_crm_backend.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AiChatRequest (v5 — added visibleRoles for hierarchy-aware scoping)
 * ─────────────────────────────────────────────────────────────────────
 * NEW FIELD: visibleRoles
 *   The canSeeRoles list from role_hierarchy for the logged-in user's role.
 *   Sent from frontend AuthContext (populated at login from LoginResponseWrapper).
 *
 *   Examples:
 *     SALES_MANAGER  → ["SALES_EXEC","TELECALLER","MANAGER"]
 *     BD_MANAGER     → ["BD_EXECUTIVE","TELECALLER"]
 *     BD_EXECUTIVE   → []   (sees only own data)
 *     SUPERADMIN     → []   (isAdmin=true overrides, gets global data)
 *
 * ALL EXISTING FIELDS UNCHANGED.
 */
@Data
public class AiChatRequest {

    /** Conversation messages — only the latest user message is processed */
    private List<AiChatMessage> messages;

    /** Menu-level permissions — CHECK FIRST (page access gate) */
    private List<String> menuPermissions;

    /** Page-level permissions — CHECK SECOND (CRUD gate) */
    private Map<String, List<String>> pagePermissions;

    /**
     * Hierarchy-based team visibility.
     * From role_hierarchy.can_see_roles for the logged-in user's role.
     * Used to scope AI queries to the user's team, not just themselves.
     *
     * Empty list = user sees only their own data (scoped by userId).
     * Non-empty  = user sees data of users whose role is in this list.
     * Admin/Superadmin = isAdmin check bypasses this, uses global SQL.
     */
    private List<String> visibleRoles;

    /** Personal context from localStorage — for personal questions */
    private UserContext userContext;

    /** Optional image */
    private String imageBase64;
    private String imageMimeType;
    private String imageFileName;

    @Data
    public static class AiChatMessage {
        private String role;
        private String content;
    }

    @Data
    public static class UserContext {
        private String name;
        private String email;
        private String phone;
        private String role;
        private String userId;
        private String designation;
        private String team;
    }
}