package com.istlgroup.istl_group_crm_backend.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.service.AuditLogService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * AuditHttpInterceptor — LOGIN ACTIVITY MODULE.
 *
 * Automatically records EVERY data-changing request (POST / PUT / PATCH /
 * DELETE) on EVERY endpoint into the activity timeline — no per-service code
 * required. This is what turns "Opened /follow-ups" into proper entries like
 * "Created Follow-up #41" and "Updated Lead #128 — failed (400)".
 *
 * Design decisions:
 *   • GET requests are NOT logged here — page views are already tracked by
 *     the frontend beacon, and logging every GET would flood the table.
 *   • Login/logout/OTP endpoints are excluded — LoginService records those
 *     with far richer detail (and OTP requests must never be logged).
 *   • Runs in afterCompletion, so it also captures FAILED operations with
 *     their HTTP status — validation errors and crashes become visible.
 *   • Writing goes through AuditLogService's async queue → adds ~0ms to the
 *     request.
 *   • The @Audited annotation still works on top of this for hand-written,
 *     richer descriptions; to avoid double rows the aspect marks the request
 *     and this interceptor then skips it.
 */
@Component
public class AuditHttpInterceptor implements HandlerInterceptor {

    /** Set by AuditedAspect when an @Audited method already logged this request. */
    public static final String ALREADY_AUDITED_ATTR = "LA_ALREADY_AUDITED";

    @Autowired
    private AuditLogService auditLogService;

    /** URI prefix → module shown in the monitor. Order matters (longest first). */
    private static final Map<String, String[]> MODULE_MAP = new LinkedHashMap<>();
    static {
        // prefix                          module          entity label
        MODULE_MAP.put("/followups",        new String[]{"LEADS",        "Follow-up"});
        MODULE_MAP.put("/leads",            new String[]{"LEADS",        "Lead"});
        MODULE_MAP.put("/telecaller",       new String[]{"LEADS",        "Telecaller Lead"});
        MODULE_MAP.put("/customers",        new String[]{"CUSTOMERS",    "Customer"});
        MODULE_MAP.put("/proposals",        new String[]{"PROPOSALS",    "Proposal"});
        MODULE_MAP.put("/quotations",       new String[]{"QUOTATIONS",   "Quotation"});
        MODULE_MAP.put("/invoices",         new String[]{"INVOICES",     "Invoice"});
        MODULE_MAP.put("/bills",            new String[]{"BILLS",        "Bill"});
        MODULE_MAP.put("/receipts",         new String[]{"INVOICES",     "Receipt"});
        MODULE_MAP.put("/order-book/item-catalogue", new String[]{"ORDER_BOOK", "Catalogue Item"});
        MODULE_MAP.put("/order-book/scope-activities", new String[]{"ORDER_BOOK", "Scope Activity"});
        MODULE_MAP.put("/order-book",       new String[]{"ORDER_BOOK",   "Order"});
        MODULE_MAP.put("/purchase-orders",  new String[]{"PROCUREMENT",  "Purchase Order"});
        MODULE_MAP.put("/vendors",          new String[]{"PROCUREMENT",  "Vendor"});
        MODULE_MAP.put("/vendor-advances",  new String[]{"PROCUREMENT",  "Vendor Advance"});
        MODULE_MAP.put("/inventory/items",  new String[]{"INVENTORY",    "Inventory Item"});
        MODULE_MAP.put("/inventory/purchase-orders", new String[]{"INVENTORY", "Purchase Order"});
        MODULE_MAP.put("/inventory/bills",  new String[]{"INVENTORY",    "Bill"});
        MODULE_MAP.put("/inventory/payments", new String[]{"INVENTORY",  "Payment"});
        MODULE_MAP.put("/inventory/transactions", new String[]{"INVENTORY", "Transaction"});
        MODULE_MAP.put("/warehouses",       new String[]{"INVENTORY",    "Warehouse"});
        MODULE_MAP.put("/projects",         new String[]{"PROJECTS",     "Project"});
        MODULE_MAP.put("/project-expenses", new String[]{"PROJECTS",     "Project Expense"});
        MODULE_MAP.put("/tasks",            new String[]{"TASKS",        "Task"});
        MODULE_MAP.put("/teams",            new String[]{"USERS",        "Team"});
        MODULE_MAP.put("/users",            new String[]{"USERS",        "User"});
        MODULE_MAP.put("/roles",            new String[]{"ROLES",        "Role"});
        MODULE_MAP.put("/role-hierarchy",   new String[]{"ROLES",        "Role Hierarchy"});
        MODULE_MAP.put("/role-permission",  new String[]{"ROLES",        "Role Permission"});
        MODULE_MAP.put("/permissions",      new String[]{"ROLES",        "Permission"});
        MODULE_MAP.put("/menu-permissions", new String[]{"ROLES",        "Menu Permission"});
        MODULE_MAP.put("/reports",          new String[]{"REPORTS",      "Report"});
        MODULE_MAP.put("/mail",             new String[]{"NOTIFICATIONS","Mail"});
        MODULE_MAP.put("/api/notifications",new String[]{"NOTIFICATIONS","Notification"});
        MODULE_MAP.put("/admin/dropdowns",  new String[]{"SETTINGS",     "Dropdown"});
        MODULE_MAP.put("/bom-items-master", new String[]{"SETTINGS",     "BOM Item"});
        MODULE_MAP.put("/ai-assistant",     new String[]{"AI_ASSISTANT", "AI Request"});
    }

    /** Endpoints excluded from auto-audit. */
    private static final String[] EXCLUDED_PREFIXES = {
            "/login",                          // logged with richer detail by LoginService
            "/office-use/login-activity/track",// page-view beacon (already an audit write)
            "/office-use/login-activity/geo",  // background location fill — not a user action
            "/error",
    };

    /** Trailing numeric path segment = entity id (e.g. /followups/41). */
    private static final Pattern TRAILING_ID = Pattern.compile("/(\\d+)(?:/[a-zA-Z\\-]*)?/?$");

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String method = request.getMethod();
            if (!("POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method) || "DELETE".equals(method))) {
                return; // GETs are covered by page-view tracking
            }

            String uri = request.getRequestURI();
            String ctx = request.getContextPath();
            if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
                uri = uri.substring(ctx.length());
            }
            for (String skip : EXCLUDED_PREFIXES) {
                if (uri.startsWith(skip)) return;
            }

            // Only audit actions of a signed-in user
            var session = request.getSession(false);
            if (session == null || session.getAttribute("USER_ID") == null) return;

            // An @Audited method already wrote a richer entry for this request
            if (Boolean.TRUE.equals(request.getAttribute(ALREADY_AUDITED_ATTR))) return;

            // ── Resolve module + entity from the URI ─────────────────────
            String module = "GENERAL";
            String entityLabel = "Record";
            for (Map.Entry<String, String[]> e : MODULE_MAP.entrySet()) {
                if (uri.startsWith(e.getKey())) {
                    module = e.getValue()[0];
                    entityLabel = e.getValue()[1];
                    break;
                }
            }

            String operation = switch (method) {
                case "POST"   -> "CREATE";
                case "DELETE" -> "DELETE";
                default        -> "UPDATE"; // PUT / PATCH
            };
            // Sub-path verbs refine the operation (e.g. /leads/12/assign)
            String lower = uri.toLowerCase();
            if (lower.contains("/assign"))                       operation = "ASSIGN";
            else if (lower.contains("/approve"))                 operation = "APPROVE";
            else if (lower.contains("/reject"))                  operation = "REJECT";
            else if (lower.contains("/status"))                  operation = "STATUS_CHANGE";
            else if (lower.contains("/import"))                  operation = "IMPORT";
            else if (lower.contains("/export") || lower.contains("/download")) operation = "EXPORT";
            else if (lower.contains("/upload"))                  operation = "UPLOAD";
            else if (lower.contains("password"))                 operation = "PASSWORD_CHANGE";
            else if (lower.contains("permission"))               operation = "PERMISSION_CHANGE";

            Long entityId = null;
            Matcher m = TRAILING_ID.matcher(uri);
            if (m.find()) {
                try { entityId = Long.valueOf(m.group(1)); } catch (NumberFormatException ignored) { }
            }

            int status = response.getStatus();
            boolean success = status >= 200 && status < 400;

            String verb = switch (operation) {
                case "CREATE" -> "Created"; case "UPDATE" -> "Updated";
                case "DELETE" -> "Deleted"; case "ASSIGN" -> "Assigned";
                case "APPROVE" -> "Approved"; case "REJECT" -> "Rejected";
                case "STATUS_CHANGE" -> "Changed status of";
                case "IMPORT" -> "Imported"; case "EXPORT" -> "Exported";
                case "UPLOAD" -> "Uploaded file for";
                case "PASSWORD_CHANGE" -> "Changed password of";
                case "PERMISSION_CHANGE" -> "Changed permissions of";
                default -> "Modified";
            };
            String description = verb + " " + entityLabel
                    + (entityId != null ? " #" + entityId : "")
                    + (success ? "" : " — failed (" + status + ")");

            AuditLogEntity log = auditLogService.base(module, operation, uri, description);
            log.setEntityType(entityLabel);
            log.setEntityId(entityId);
            if (!success) {
                log.setStatus("FAILURE");
                log.setFailureReason("HTTP " + status);
            }
            auditLogService.enqueue(log);
        } catch (Exception ignored) {
            // Auditing must never break a business request
        }
    }
}
