package com.istlgroup.istl_group_crm_backend.util;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * RoleNormalizer — single source of truth for role name format.
 *
 * Every role string stored in ANY table (users.role, role_hierarchy.role_name,
 * roles.name, and every value inside the canAssignRoles / canSeeRoles JSON
 * arrays) must go through this class before being persisted.
 *
 * Rule: UPPER_SNAKE_CASE
 *   "BD Executive"  → "BD_EXECUTIVE"
 *   "Sales Manager" → "SALES_MANAGER"
 *   "Admin"         → "ADMIN"
 *   "Telecaller"    → "TELECALLER"
 *   "SuperAdmin"    → "SUPERADMIN"
 *   "SUPERADMIN"    → "SUPERADMIN"   (no-op — already correct)
 * ─────────────────────────────────────────────────────────────────────────────
 */
public final class RoleNormalizer {

    private RoleNormalizer() {}

    /**
     * Normalises any role string to UPPER_SNAKE_CASE.
     * Returns null if the input is null or blank.
     */
    public static String normalize(String role) {
        if (role == null || role.isBlank()) return null;
        return role.trim()
                   .replaceAll("[\\s\\-]+", "_")   // spaces / hyphens → underscore
                   .toUpperCase();
    }

    /**
     * Same as normalize() but throws IllegalArgumentException on null/blank.
     * Use this on required fields.
     */
    public static String normalizeRequired(String role) {
        String r = normalize(role);
        if (r == null) throw new IllegalArgumentException("Role name must not be blank");
        return r;
    }
}