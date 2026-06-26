package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.dto.AiChatRequest;
import com.istlgroup.istl_group_crm_backend.dto.AiChatResponse;
import com.istlgroup.istl_group_crm_backend.util.GroqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    @Autowired private AiIntentService     aiIntentService;
    @Autowired private AiDataService       aiDataService;
    @Autowired private AiPermissionService aiPermissionService;
    @Autowired private GroqClient          groqClient;

    // ─────────────────────────────────────────────────────────────────────────
    // SYSTEM PROMPT — used for all CRM data responses
    // Rules are strict: answer the question directly, never suggest pages
    // ─────────────────────────────────────────────────────────────────────────
    private static final String DATA_RESPONSE_PROMPT = """
            You are a CRM assistant. Answer the user's question using ONLY the data provided.

            STRICT RULES:
            1. NEVER suggest pages or navigation. Just answer with the data.
            2. Money amounts are already formatted as ₹X,XX,XXX.00 — use them exactly as provided, do NOT reformat them.
            3. Dates: "15 Jan 2025" format.
            4. Never show raw database IDs.
            5. For counts: state the number clearly.
            6. If the user asks multiple specific questions (e.g. "what are A, B, C, D?"),
               answer EACH one on a separate labeled line like:
                 • Total invoices raised: 30
                 • From clients (collected): ₹55,86,21,153.84
                 • Yet to collect: ₹26,27,15,777.64
                 • Collection progress: 67.9%
            7. For a single value: one sentence is enough.
            8. For financial summaries: show total, collected/paid, outstanding clearly.
            9. If asked how something was calculated, show the formula briefly.
            10. When data contains group_filter or category_filter, the results are already
                scoped to that group/category. Answer directly: "In the Solar Wind category,
                total invoices raised: ₹X.XX Cr".
            11. For invoice queries: total_invoiced = sum of all invoices, total_collected = paid
                amount, outstanding = yet to collect.
            12. For project queries: total_project_value = sum of project budgets.
                Also show total_billed, total_received, total_procurement if available.
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────
    public AiChatResponse handleQuestion(
            Long userId,
            String userRole,
            String question,
            List<String> menuPermissions,
            Map<String, List<String>> pagePermissions,
            List<String> visibleRoles,
            AiChatRequest.UserContext userContext,
            String imageBase64,
            String imageMimeType) {

        String q = (question != null && !question.isBlank()) ? question.trim() : "";
        log.info("AI: user={} role={} q='{}'", userId, userRole, q);
        String userIdentity = buildUserIdentity(userContext, userRole, userId);

        // ── Image ─────────────────────────────────────────────────────────────
        if (imageBase64 != null && !imageBase64.isBlank()) {
            return handleImage(q, imageBase64, imageMimeType);
        }

        // ── Classify ──────────────────────────────────────────────────────────
        AiIntentService.AiIntent intent = aiIntentService.classify(q);
        log.info("AI: intent={}", intent);

        // ── Personal ─────────────────────────────────────────────────────────
        if (intent.isPersonal()) {
            return answerPersonal(q, userContext, userRole);
        }

        // ── General / Math / Greeting ────────────────────────────────────────
        if (intent.isGeneral()) {
            return answerGeneral(q);
        }

        // ── How-To (app navigation) ──────────────────────────────────────────
        if (intent.isHowTo()) {
            return answerHowTo(q, userRole, menuPermissions);
        }

        // ── Permission check ─────────────────────────────────────────────────
        AiPermissionService.Module module = moduleFromIntent(intent);
        if (module != null) {
            boolean isAdmin = userRole != null &&
                (userRole.equalsIgnoreCase("SUPERADMIN") || userRole.equalsIgnoreCase("ADMIN"));
            if (!isAdmin) {
                if (!aiPermissionService.hasMenuAccess(userRole, menuPermissions, module)) {
                    return AiChatResponse.of(
                        "You don't have access to that information.", "denied");
                }
                if (!aiPermissionService.canView(userRole, pagePermissions, module)) {
                    return AiChatResponse.of(
                        "You don't have permission to view that. Contact your administrator.", "denied");
                }
            }
        }

        // ── Fetch data from real services ────────────────────────────────────
        AiDataService.AiDataResult data = fetchData(intent, userId, userRole);

        // ── Format and return ────────────────────────────────────────────────
        return formatResponse(q, intent, data, userIdentity);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DATA FETCH — routes to real service methods
    // ─────────────────────────────────────────────────────────────────────────
    private AiDataService.AiDataResult fetchData(
            AiIntentService.AiIntent intent, Long userId, String userRole) {
        String mod    = intent.getModule();
        String filter = intent.getFilter();
        String name   = intent.getNameSearch();
        String action = intent.getAction();
        try {
            return switch (mod) {
                case "LEADS"           -> aiDataService.getLeads(userId, userRole, filter, name, action);
                case "FOLLOWUPS"       -> aiDataService.getFollowups(userId, userRole, filter);
                case "CUSTOMERS"       -> aiDataService.getCustomers(userId, userRole, name);
                case "PROPOSALS"       -> aiDataService.getProposals(userId, userRole, name, filter);
                case "INVOICES"        -> aiDataService.getInvoices(userId, userRole, filter, name);
                case "VENDORS"         -> aiDataService.getVendors(userId, userRole, name);
                case "TASKS"           -> aiDataService.getTasks(userId, userRole, filter);
                case "USERS"           -> aiDataService.getUsers(userId, userRole);
                case "BILLS"           -> aiDataService.getBills(userId, userRole, filter, name);
                case "PURCHASE_ORDERS" -> aiDataService.getPurchaseOrders(userId, userRole, filter, name);
                case "ORDER_BOOK"      -> aiDataService.getOrderBook(userId, userRole, filter, name, intent.getOriginalQuestion());
                case "QUOTATIONS"      -> aiDataService.getQuotations(userId, userRole, filter, name);
                case "INVENTORY"       -> aiDataService.getInventory(userId, userRole, name);
                case "EXPENSES"        -> aiDataService.getExpenses(userId, userRole, filter);
                case "PROJECTS"        -> aiDataService.getProjects(userId, userRole, filter, name);
                case "MENU_ITEMS"      -> aiDataService.getMenuItems();
                default                -> AiDataService.AiDataResult.error(mod, "Not supported");
            };
        } catch (Exception e) {
            log.error("AI fetch error module={}: {}", mod, e.getMessage());
            return AiDataService.AiDataResult.error(mod, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FORMAT RESPONSE
    // ─────────────────────────────────────────────────────────────────────────
    private AiChatResponse formatResponse(
            String question,
            AiIntentService.AiIntent intent,
            AiDataService.AiDataResult data,
            String userIdentity) {

        if (!data.isSuccess()) {
            return AiChatResponse.of("I had trouble fetching that data. Please try again.", "error");
        }

        if (data.isEmpty()) {
            java.util.Set<String> GROUP_AWARE_MODULES = java.util.Set.of(
                "PROJECTS", "INVOICES", "BILLS", "PURCHASE_ORDERS", "QUOTATIONS", "ORDER_BOOK");
            String qualifier = intent.getNameSearch() != null
                ? " for \"" + intent.getNameSearch() + "\""
                : (intent.getFilter() != null
                    ? (" in the \"" + intent.getFilter() + "\" " +
                       (GROUP_AWARE_MODULES.contains(intent.getModule()) ? "group/category" : "category"))
                    : "");
            return AiChatResponse.of(
                "No " + data.getModule().toLowerCase().replace("_", " ") + " found" + qualifier + ".",
                "empty");
        }

        String dataContext = buildDataContext(data);
        String userPrompt  = userIdentity + "\n\nQuestion: " + question +
                             "\n\nData:\n" + dataContext +
                             "\n\nAnswer directly using the data above.";

        try {
            String reply = groqClient.complete(DATA_RESPONSE_PROMPT, userPrompt, 300, 0.2);
            return AiChatResponse.of(reply, "data");
        } catch (Exception e) {
            log.error("AI format error: {}", e.getMessage());
            return AiChatResponse.of(buildFallback(data), "fallback");
        }
    }

    private String buildDataContext(AiDataService.AiDataResult data) {
        StringBuilder sb = new StringBuilder();
        if (!data.getSummary().isEmpty()) {
            data.getSummary().forEach((k, v) -> {
                if (v instanceof List<?> list && !list.isEmpty()) {
                    sb.append(k).append(" (").append(list.size()).append(" items):\n");
                    list.stream().limit(50).forEach(item -> sb.append("  - ").append(item).append("\n"));
                } else if (v instanceof Map<?,?> map && !map.isEmpty()) {
                    sb.append(k).append(": ").append(v).append("\n");
                } else if (v != null) {
                    sb.append(k).append(": ").append(formatValue(k, v)).append("\n");
                }
            });
        }
        if (!data.getRecords().isEmpty()) {
            sb.append("\nRecords (").append(data.getRecords().size()).append("):\n");
            data.getRecords().forEach(r -> {
                sb.append("  - ");
                r.forEach((k, v) -> { if (v != null && !v.toString().isBlank())
                    sb.append(k).append(": ").append(formatValue(k, v)).append("  "); });
                sb.append("\n");
            });
        }
        return sb.toString().trim();
    }

    /**
     * Formats a number in Indian rupee format: ₹X,XX,XXX.00
     * Examples: 5500000 → ₹55,00,000.00 | 82133731.48 → ₹8,21,33,731.48
     */
    private String formatInr(Object value) {
        if (value == null) return "₹0.00";
        try {
            java.math.BigDecimal amount = new java.math.BigDecimal(value.toString());
            long paise = amount.multiply(java.math.BigDecimal.valueOf(100)).longValue();
            long rupees = paise / 100;
            long decimal = Math.abs(paise % 100);

            String whole = String.valueOf(Math.abs(rupees));
            StringBuilder formatted = new StringBuilder();

            if (whole.length() <= 3) {
                formatted.append(whole);
            } else {
                // Last 3 digits
                formatted.append(whole.substring(whole.length() - 3));
                String remaining = whole.substring(0, whole.length() - 3);
                // Then groups of 2 from right
                int i = remaining.length();
                while (i > 0) {
                    int start = Math.max(0, i - 2);
                    formatted.insert(0, ",").insert(0, remaining, start, i);
                    i = start;
                }
            }

            String sign = rupees < 0 ? "-" : "";
            return sign + "₹" + formatted + "." + String.format("%02d", decimal);
        } catch (Exception e) {
            return "₹" + value;
        }
    }

    /** Formats a value as INR if it looks like a financial amount, otherwise returns as-is */
    private String formatValue(String key, Object value) {
        if (value == null) return "";
        String k = key.toLowerCase();
        // Only format as currency for genuine money fields — NOT counts or percentages
        // "total_count", "filtered_count", "low_stock_count" must NOT be formatted as money
        if (k.contains("count") || k.contains("progress") || k.contains("percentage") ||
            k.contains("rate") || k.contains("margin") || k.contains("qty") ||
            k.contains("quantity") || k.contains("number")) {
            return value.toString();
        }
        // Format as currency if key suggests a financial/money field
        if (k.matches(".*(amount|value|paid|balance|outstanding|collected|revenue|profit|" +
                       "invoiced|deficit|advance|budget|spent|received|price|cost|subtotal|" +
                       "total_amount|total_value|total_invoiced|total_collected).*")) {
            return formatInr(value);
        }
        return value.toString();
    }

    private String buildFallback(AiDataService.AiDataResult data) {
        Map<String, Object> s = data.getSummary();
        StringBuilder sb = new StringBuilder();
        if (s.containsKey("total_count"))    sb.append("Total: ").append(s.get("total_count")).append("\n");
        if (s.containsKey("total_value"))    sb.append("Total value: ").append(formatInr(s.get("total_value"))).append("\n");
        if (s.containsKey("total_invoiced")) sb.append("Invoiced: ").append(formatInr(s.get("total_invoiced")))
                                               .append(", Collected: ").append(formatInr(s.get("total_collected")))
                                               .append(", Outstanding: ").append(formatInr(s.get("outstanding"))).append("\n");
        if (!data.getRecords().isEmpty()) {
            data.getRecords().stream().limit(10).forEach(r -> {
                Object name = r.get("name"); Object code = r.get("lead_code");
                Object status = r.get("status");
                sb.append("• ").append(code != null ? code + " " : "")
                  .append(name != null ? name : "")
                  .append(status != null ? " (" + status + ")" : "").append("\n");
            });
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERSONAL
    // ─────────────────────────────────────────────────────────────────────────
    private AiChatResponse answerPersonal(String question, AiChatRequest.UserContext ctx, String userRole) {
        String name  = ctx != null && ctx.getName()  != null ? ctx.getName()  : "—";
        String email = ctx != null && ctx.getEmail() != null ? ctx.getEmail() : "—";
        String phone = ctx != null && ctx.getPhone() != null ? ctx.getPhone() : "—";
        String role  = ctx != null && ctx.getRole()  != null ? ctx.getRole()  : userRole;
        String desig = ctx != null && ctx.getDesignation() != null ? ctx.getDesignation() : "—";

        String prompt = "Answer in 1-2 sentences. To change profile, user clicks their avatar top-right.";
        String data   = "Name=" + name + " Email=" + email + " Phone=" + phone +
                        " Role=" + role + " Designation=" + desig;
        try {
            return AiChatResponse.of(
                groqClient.complete(prompt, data + "\nQuestion: " + question, 100, 0.2), "personal");
        } catch (Exception e) {
            return AiChatResponse.of("Your name is " + name + ", role is " + role + ".", "personal");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERAL (math, greetings, etc.)
    // ─────────────────────────────────────────────────────────────────────────
    private AiChatResponse answerGeneral(String question) {
        try {
            String reply = groqClient.complete(
                "Answer briefly in 1 sentence.", question, 80, 0.2);
            return AiChatResponse.of(reply, "general");
        } catch (Exception e) {
            return AiChatResponse.of("I'm your CRM assistant. Ask me about leads, invoices, or any CRM data.", "general");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOW-TO (app navigation only)
    // ─────────────────────────────────────────────────────────────────────────
    private AiChatResponse answerHowTo(String question, String userRole, List<String> menuPermissions) {
        String prompt = """
                You are a CRM assistant. Answer this how-to question completely with:
                1. WHERE: which page/section in the sidebar to go to.
                2. HOW: the exact steps to complete the action on that page.

                Be concise but complete — do not skip the steps.
                Use exact sidebar page names from the structure below.

                CRM SIDEBAR:
                Main: Dashboard, Follow-Ups, Reports, Task Management, Inventory Management
                Sales: Leads/Enquiries, Clients Data, OrderBook, Invoices
                Procurement: Vendor Data, Quotations Received, Purchase Orders, Bills Received
                Office Use (admin only): Add New Group/Project, Add New Roles/Permissions, Project Access Manager

                HOW-TO KNOWLEDGE:
                Create new role:      Office Use → Add New Roles/Permissions → fill role name, select permissions → Save.
                Create new lead:      Sales → Leads/Enquiries → click "+ Add Lead" → fill form → Save.
                Create new customer:  Sales → Clients Data → click "+ Add Client" → fill form → Save.
                Create new vendor:    Procurement → Vendor Data → click "+ Add Vendor" → fill form → Save.
                Create purchase order:Procurement → Purchase Orders → click "+ Create PO" → select vendor, add items → Save.
                Create invoice:       Sales → Invoices → click "+ Create Invoice" → select customer, add items → Save.
                Create follow-up:     Follow-Ups → click "+ Add Follow-Up" → select lead/customer, set date → Save.
                Create task:          Task Management → click "+ Add Task" → fill details → Save.
                Create new group:     Office Use → Add New Group/Project → fill group details → Save.
                """;
        try {
            return AiChatResponse.of(
                groqClient.complete(prompt, "Question: " + question, 250, 0.2), "howto");
        } catch (Exception e) {
            return AiChatResponse.of(
                "Please refer to the sidebar to navigate the CRM.", "howto");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMAGE
    // ─────────────────────────────────────────────────────────────────────────
    private AiChatResponse handleImage(String question, String imageBase64, String mimeType) {
        try {
            String reply = groqClient.completeWithVision(
                "Read and describe this image. If it has text, read it. Answer any question in it. Format money as ₹.",
                question, imageBase64, mimeType, 300, 0.2);
            return AiChatResponse.of(reply, "image");
        } catch (Exception e) {
            return AiChatResponse.of("Unable to analyse the image. Please try again.", "image_error");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private AiPermissionService.Module moduleFromIntent(AiIntentService.AiIntent intent) {
        return switch (intent.getModule()) {
            case "LEADS"           -> AiPermissionService.Module.LEADS;
            case "FOLLOWUPS"       -> AiPermissionService.Module.FOLLOWUPS;
            case "CUSTOMERS"       -> AiPermissionService.Module.CUSTOMERS;
            case "PROPOSALS"       -> AiPermissionService.Module.PROPOSALS;
            case "INVOICES"        -> AiPermissionService.Module.INVOICES;
            case "VENDORS"         -> AiPermissionService.Module.VENDORS;
            case "TASKS"           -> AiPermissionService.Module.TASKS;
            case "USERS"           -> AiPermissionService.Module.USERS;
            case "PROJECTS"        -> AiPermissionService.Module.PROJECTS;
            case "EXPENSES"        -> AiPermissionService.Module.EXPENSES;
            case "INVENTORY"       -> AiPermissionService.Module.INVENTORY;
            default                -> null;
        };
    }

    private String buildUserIdentity(AiChatRequest.UserContext ctx, String userRole, Long userId) {
        if (ctx == null) return "User: Role=" + userRole + ", ID=" + userId;
        return "User: Name=" + (ctx.getName() != null ? ctx.getName() : "—") +
               " | Role=" + (ctx.getRole() != null ? ctx.getRole() : userRole) +
               " | Email=" + (ctx.getEmail() != null ? ctx.getEmail() : "—");
    }
}