package com.istlgroup.istl_group_crm_backend.service;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AiPermissionService (v6 — Two-layer permission check)
 * ═══════════════════════════════════════════════════════
 *
 * LAYER 1 — menuPermissions (page access gate):
 *   Exact uppercased DB menu_item.name values from findMenuNamesByUserId().
 *   Examples: SALES_LEADS, FOLLOW_UPS, PROCUREMENT_PURCHASE_ORDERS, OFFICE_USE
 *   → Does the user have access to this MODULE/PAGE at all?
 *
 * LAYER 2 — pagePermissions (CRUD operation gate):
 *   Map from buildPermissionsMapFromNames() at login.
 *   Examples: { "LEADS": ["VIEW","CREATE","EDIT"], "INVOICES": ["VIEW"] }
 *   → What can the user DO within the module they have access to?
 *
 * Both layers must pass before any data is returned.
 * SUPERADMIN always passes both layers.
 */
@Service
public class AiPermissionService {

    public enum Module {
        LEADS, CUSTOMERS, INVOICES, USERS, FOLLOWUPS, NOTIFICATIONS,
        PURCHASE_ORDERS, BILLS, PAYMENTS, VENDORS, QUOTATIONS,
        PROPOSALS, ORDER_BOOK, PROJECTS,
        INVENTORY, TASKS, EXPENSES, RECEIPTS
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LAYER 1: menuPermissions → page/module access
    // Maps real DB menu_item.name values (uppercased) to Modules
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the user has MENU-LEVEL access to the given module.
     * This is the FIRST check — before even looking at CRUD permissions.
     */
    public boolean hasMenuAccess(String userRole, List<String> menuPermissions, Module module) {
        if (isSuperAdmin(userRole)) return true;
        if (menuPermissions == null) return false;

        Set<String> menus = new HashSet<>(menuPermissions);

        return switch (module) {
            case LEADS          -> menus.contains("SALES_LEADS");
            case CUSTOMERS      -> menus.contains("SALES_CLIENTS");
            case INVOICES       -> menus.contains("INVOICES") || menus.contains("INVOICES_OUTSTANDING");
            case USERS          -> menus.contains("SETTINGS") || menus.contains("USERS");
            case FOLLOWUPS      -> menus.contains("FOLLOW_UPS");
            case NOTIFICATIONS  -> true; // always accessible
            case PURCHASE_ORDERS-> menus.contains("PROCUREMENT_PURCHASE_ORDERS");
            case BILLS          -> menus.contains("PROCUREMENT_BILLS_RECEIVED")
                                || menus.contains("BILLS_PAYMENTS")
                                || menus.contains("BILLS_OUTSTANDING");
            case PAYMENTS       -> menus.contains("BILLS_PAYMENTS");
            case VENDORS        -> menus.contains("PROCUREMENT_VENDERS");
            case QUOTATIONS     -> menus.contains("PROCUREMENT_QUOTATIONS_RECEIVED")
                                || menus.contains("SALES_ESTIMATION");
            case PROPOSALS      -> menus.contains("PROPOSAL");
            case ORDER_BOOK     -> menus.contains("SALES_ORDERBOOK");
            case PROJECTS       -> menus.contains("PROJECT_DASHBOARD")
                                || menus.contains("PROJECT_COST_EXPENSE");
            case INVENTORY      -> menus.contains("INVENTORY_MANAGEMENT");
            case TASKS          -> menus.contains("TASK_MANAGEMENT");
            case EXPENSES       -> menus.contains("PROJECT_COST_EXPENSE");
            case RECEIPTS       -> menus.contains("RECEIPTS") || menus.contains("INVOICES");
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LAYER 2: pagePermissions → CRUD operation access
    // Uses the map built by buildPermissionsMapFromNames() at login:
    //   { "LEADS": ["VIEW","CREATE","EDIT"], "INVOICES": ["VIEW"] }
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the user can VIEW data in the given module.
     * VIEW is the minimum required permission to read/query data.
     */
    public boolean canView(String userRole, Map<String, List<String>> pagePermissions, Module module) {
        if (isSuperAdmin(userRole)) return true;
        if (pagePermissions == null || pagePermissions.isEmpty()) return false;

        String key = pagePermKey(module);
        if (key == null) return false;

        List<String> actions = pagePermissions.get(key);
        if (actions == null) return false;

        return actions.stream().anyMatch(a -> a.equalsIgnoreCase("VIEW"));
    }

    /**
     * Returns true if the user can CREATE records in the given module.
     */
    public boolean canCreate(String userRole, Map<String, List<String>> pagePermissions, Module module) {
        if (isSuperAdmin(userRole)) return true;
        if (pagePermissions == null) return false;

        String key = pagePermKey(module);
        if (key == null) return false;

        List<String> actions = pagePermissions.get(key);
        if (actions == null) return false;

        return actions.stream().anyMatch(a -> a.equalsIgnoreCase("CREATE"));
    }

    /**
     * Full check: LAYER 1 (menu access) AND LAYER 2 (VIEW permission).
     * Use this before answering any data question.
     */
    public boolean isAllowed(
            String userRole,
            List<String> menuPermissions,
            Map<String, List<String>> pagePermissions,
            Module module) {

        if (isSuperAdmin(userRole)) return true;

        // Layer 1: must have menu access first
        if (!hasMenuAccess(userRole, menuPermissions, module)) return false;

        // Layer 2: must have VIEW permission in pagePermissions
        // If pagePermissions is null/empty but menu access exists,
        // allow (some roles may not have granular page perms configured)
        if (pagePermissions == null || pagePermissions.isEmpty()) return true;

        return canView(userRole, pagePermissions, module);
    }

    /**
     * Returns the set of modules the user is allowed to query.
     * Used for building user context and permission summary.
     */
    public Set<Module> getAllowedModules(
            String userRole,
            List<String> menuPermissions,
            Map<String, List<String>> pagePermissions) {

        Set<Module> allowed = new HashSet<>();
        if (isSuperAdmin(userRole)) {
            for (Module m : Module.values()) allowed.add(m);
            return allowed;
        }
        // Notifications always accessible
        allowed.add(Module.NOTIFICATIONS);
        // Check each module through both layers
        for (Module m : Module.values()) {
            if (isAllowed(userRole, menuPermissions, pagePermissions, m)) {
                allowed.add(m);
            }
        }
        return allowed;
    }

    /**
     * Maps a schema filename to its Module for permission gating.
     */
    public Module moduleForSchemaFile(String schemaFileName) {
        if (schemaFileName == null) return null;
        return switch (schemaFileName) {
            case "leads_schema.txt"           -> Module.LEADS;
            case "customers_schema.txt"       -> Module.CUSTOMERS;
            case "invoices_schema.txt"        -> Module.INVOICES;
            case "users_schema.txt"           -> Module.USERS;
            case "followups_schema.txt"       -> Module.FOLLOWUPS;
            case "notifications_schema.txt"   -> Module.NOTIFICATIONS;
            case "purchase_orders_schema.txt" -> Module.PURCHASE_ORDERS;
            case "bills_schema.txt"           -> Module.BILLS;
            case "payments_schema.txt"        -> Module.PAYMENTS;
            case "vendors_schema.txt"         -> Module.VENDORS;
            case "quotations_schema.txt"      -> Module.QUOTATIONS;
            case "proposals_schema.txt"       -> Module.PROPOSALS;
            case "order_book_schema.txt"      -> Module.ORDER_BOOK;
            case "projects_schema.txt"        -> Module.PROJECTS;
            case "tasks_schema.txt"            -> Module.TASKS;
            case "inventory_items_schema.txt"  -> Module.INVENTORY;
            case "inv_modules_schema.txt"      -> Module.INVENTORY;
            case "warehouses_schema.txt"       -> Module.INVENTORY;
            case "receipts_schema.txt"         -> Module.RECEIPTS;
            case "project_expenses_schema.txt" -> Module.EXPENSES;
            case "project_advances_schema.txt" -> Module.EXPENSES;
            case "vendor_advances_schema.txt"  -> Module.VENDORS;
            case "sales_orders_schema.txt"     -> Module.ORDER_BOOK;
            case "groups_schema.txt"           -> null; // master/lookup data, no permission gate
            case "project_dashboard_summary_schema.txt" -> Module.PROJECTS;
            default -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps Module → pagePermissions map key.
     * These keys match the MODULE_NAME_ALIASES in LoginService.buildPermissionsMapFromNames().
     */
    private String pagePermKey(Module module) {
        return switch (module) {
            case LEADS          -> "LEADS";
            case CUSTOMERS      -> "CUSTOMERS";
            case INVOICES       -> "INVOICES";
            case USERS          -> "USERS";
            case FOLLOWUPS      -> "FOLLOWUPS";
            case NOTIFICATIONS  -> null;
            case PURCHASE_ORDERS-> "PURCHASE_ORDERS";
            case BILLS          -> "BILLS";
            case PAYMENTS       -> "PAYMENTS";
            case VENDORS        -> "VENDORS";
            case QUOTATIONS     -> "SALES_QUOTATIONS";
            case PROPOSALS      -> "PROPOSALS";
            case ORDER_BOOK     -> "ORDER_BOOK";
            case PROJECTS       -> "PROJECTS";
            case INVENTORY      -> "INVENTORY";
            case TASKS          -> "TASKS";
            case EXPENSES       -> "EXPENSES";
            case RECEIPTS       -> "RECEIPTS";
        };
    }

    private boolean isSuperAdmin(String role) {
        return role != null && role.trim().equalsIgnoreCase("SUPERADMIN");
    }
}