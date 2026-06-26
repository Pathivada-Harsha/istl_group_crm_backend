package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.util.GroqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AiIntentService {

    private static final Logger log = LoggerFactory.getLogger(AiIntentService.class);

    @Autowired private GroqClient groqClient;

    private static final String INTENT_SYSTEM_PROMPT = """
            You are a classifier for a CRM assistant. Given a user question, output ONE line:
            MODULE|ACTION|FILTER|NAME_SEARCH

            MODULE options:
              LEADS, FOLLOWUPS, CUSTOMERS, PROPOSALS, INVOICES, VENDORS, TASKS, USERS,
              PROJECTS, EXPENSES, INVENTORY, BILLS, PURCHASE_ORDERS, ORDER_BOOK, QUOTATIONS,
              MENU_ITEMS, PERSONAL, HOW_TO, GENERAL

            ACTION options:
              COUNT   – user wants a number/total
              LIST    – user wants a list of records or names
              DETAIL  – user wants details about one specific named record
              SUMMARY – user wants an overview with amounts/breakdowns

            FILTER: status or type keyword if mentioned (Pending, Paid, Closed Won, High, etc.)
                    Leave empty if none.

            NAME_SEARCH: specific company/person/code the user named.
              - For codes (ORD-xxx, PROP-xxx, INV-xxx, LEAD-xxx): copy exactly.
              - For company names: 1-2 most distinctive words only, drop Pvt/Ltd/Energy/Solutions.
              - Leave empty if no specific entity named.

            KEY RULES:
            - Output ONLY the pipe-delimited line. Nothing else.
            - MODULE=HOW_TO only for "how to use the app" questions (create, navigate, find a page).
            - MODULE=PERSONAL only for questions about the user's own name/email/role/profile.
            - MODULE=GENERAL for greetings, math, jokes, anything not CRM-related.
            - Questions about CRM data (revenue, count, list, status, details) are NEVER HOW_TO.
            - "how did you calculate X" → the MODULE is whatever data X refers to (INVOICES, LEADS etc.)
            - "how many X" → COUNT
            - "show/give/list X" → LIST
            - "details/info/contact/status of named entity" → DETAIL
            - "total/revenue/summary/breakdown" → SUMMARY
            - GROUP/CATEGORY FILTER RULE: For PROJECTS, INVOICES, BILLS, PURCHASE_ORDERS,
              QUOTATIONS, and ORDER_BOOK, the FILTER field can be:
                (a) A group name: EPC, IoT
                (b) A category/sub-group name: Solar Wind, Solar Rooftop, CCMS, MCMS,
                    Solar_ground_mounted, Solar_carports
                (c) A real status value: Paid, Pending, Approved, IN_PROGRESS, etc.
              When the user says "under solar wind", "in epc group", "for solar wind category",
              "in solar rooftop", etc. — copy the group/category name as-is into FILTER.
              Leave NAME_SEARCH empty unless a specific project/order/invoice ID or company name is mentioned.

            EXAMPLES:
            "how many leads"                        → LEADS|COUNT||
            "give me all lead names"                → LEADS|LIST||
            "show pending followups"                → FOLLOWUPS|LIST|Pending|
            "details of Fourth Partner"             → LEADS|DETAIL||Fourth Partner
            "proposal details for T4"               → PROPOSALS|DETAIL||T4
            "total invoice amount"                  → INVOICES|SUMMARY||
            "how much revenue from Fourth Partner"  → INVOICES|SUMMARY||Fourth Partner
            "how did you calculate total revenue"   → INVOICES|SUMMARY||
            "paid invoices"                         → INVOICES|LIST|Paid|
            "contact number of ABC Solar"           → LEADS|DETAIL||ABC Solar
            "active vendors"                        → VENDORS|LIST|Active|
            "total orderbook value"                 → ORDER_BOOK|SUMMARY||
            "how many orderbooks"                   → ORDER_BOOK|COUNT||
            "total amount of ORD-2026-000022"       → ORDER_BOOK|DETAIL||ORD-2026-000022
            "status of PROP-2026-0008"              → PROPOSALS|DETAIL||PROP-2026-0008
            "pending bills"                         → BILLS|LIST|Pending|
            "purchase orders"                       → PURCHASE_ORDERS|LIST||
            "low stock items"                       → INVENTORY|LIST|low stock|
            "pending expenses"                      → EXPENSES|LIST|Pending|
            "total users"                           → USERS|COUNT||
            "pending tasks"                         → TASKS|LIST|Pending|
            "what is my name"                       → PERSONAL|DETAIL||
            "how to create a lead"                  → LEADS|HOW_TO||
            "where is the invoices page"            → INVOICES|HOW_TO||
            "total number of menu items"             → MENU_ITEMS|COUNT||
            "list all menu items"                        → MENU_ITEMS|LIST||
            "how many menus are there"                   → MENU_ITEMS|COUNT||
            "2+2"                                        → GENERAL|DETAIL||
            "hello"                                 → GENERAL|DETAIL||
            "total project value under solar wind"  → PROJECTS|SUMMARY|Solar Wind|
            "how many projects in epc group"        → PROJECTS|COUNT|EPC|
            "projects in solar rooftop category"    → PROJECTS|LIST|Solar Rooftop|
            "total project value under epc group"   → PROJECTS|SUMMARY|EPC|
            "show in progress projects"             → PROJECTS|LIST|IN_PROGRESS|
            "completed projects"                    → PROJECTS|LIST|COMPLETED|
            "total projects"                        → PROJECTS|COUNT||
            "list all projects"                     → PROJECTS|LIST||
            "project value"                         → PROJECTS|SUMMARY||
            "total invoices under solar wind"       → INVOICES|SUMMARY|Solar Wind|
            "invoices in epc group"                 → INVOICES|LIST|EPC|
            "total invoices raised under solar wind category" → INVOICES|SUMMARY|Solar Wind|
            "bills under solar wind"                → BILLS|SUMMARY|Solar Wind|
            "bills in epc group"                    → BILLS|LIST|EPC|
            "purchase orders in solar wind"         → PURCHASE_ORDERS|LIST|Solar Wind|
            "quotations in epc"                     → QUOTATIONS|LIST|EPC|
            "order book under solar wind"           → ORDER_BOOK|SUMMARY|Solar Wind|
            """;

    public AiIntent classify(String question) {
        if (question == null || question.isBlank()) return AiIntent.general(question);

        // Fast-path personal
        if (PERSONAL_PATTERN.matcher(question).find()) {
            return new AiIntent("PERSONAL", "DETAIL", null, null, question);
        }

        try {
            String response = groqClient.complete(INTENT_SYSTEM_PROMPT,
                "Question: " + question, 60, 0.0);
            return parseIntent(response.trim(), question);
        } catch (Exception e) {
            log.warn("AiIntentService: classification failed, using fallback: {}", e.getMessage());
            return fallbackClassify(question);
        }
    }

    private AiIntent parseIntent(String response, String original) {
        String[] parts = response.split("\\|", -1);
        if (parts.length < 2) return fallbackClassify(original);

        String module     = parts[0].trim().toUpperCase();
        String action     = parts[1].trim().toUpperCase();
        String filter     = parts.length > 2 ? parts[2].trim() : "";
        String nameSearch = parts.length > 3 ? parts[3].trim() : "";

        if (!VALID_MODULES.matcher(module).matches()) module = "GENERAL";
        if (!VALID_ACTIONS.matcher(action).matches()) action = "SUMMARY";

        return new AiIntent(
            module, action,
            filter.isEmpty()     ? null : filter,
            nameSearch.isEmpty() ? null : nameSearch,
            original
        );
    }

    private AiIntent fallbackClassify(String q) {
        String lower = q.toLowerCase();

        String module = "GENERAL";
        if (lower.contains("lead") || lower.contains("enquir"))                        module = "LEADS";
        else if (lower.contains("follow"))                                              module = "FOLLOWUPS";
        else if (lower.contains("customer") || lower.contains("client"))               module = "CUSTOMERS";
        else if (lower.contains("proposal"))                                            module = "PROPOSALS";
        else if (lower.contains("invoice") || lower.contains("revenue"))               module = "INVOICES";
        else if (lower.contains("vendor") || lower.contains("supplier"))               module = "VENDORS";
        else if (lower.contains("task"))                                                module = "TASKS";
        else if (lower.contains("user") || lower.contains("employee"))                 module = "USERS";
        else if (lower.contains("project"))                                             module = "PROJECTS";
        else if (lower.contains("expense"))                                             module = "EXPENSES";
        else if (lower.contains("inventory") || lower.contains("stock"))               module = "INVENTORY";
        else if (lower.contains("bill"))                                                module = "BILLS";
        else if (lower.contains("purchase order") || lower.matches(".*\\bpo\\b.*"))    module = "PURCHASE_ORDERS";
        else if (lower.contains("order book") || lower.contains("orderbook"))          module = "ORDER_BOOK";
        else if (lower.contains("quotation") || lower.contains("quote"))               module = "QUOTATIONS";
        else if (lower.contains("menu item") || lower.contains("menu items"))              module = "MENU_ITEMS";

        String action = "SUMMARY";
        if (lower.matches(".*(how many|count|total|number of).*"))                      action = "COUNT";
        else if (lower.matches(".*(detail|info|contact|phone|email|status of|about).*")) action = "DETAIL";
        else if (lower.matches(".*(show|list|give me|display|all|names).*"))            action = "LIST";
        else if (lower.matches(".*(how to use|how to create|where is the page|navigate).*")) action = "HOW_TO";

        // For PROJECT queries: try to extract a group/category filter from the question
        String filter = null;
        if ("PROJECTS".equals(module)) {
            // Check for known group names
            if (lower.contains("epc"))         filter = "EPC";
            else if (lower.contains("iot"))    filter = "IoT";
            // Check for known category/sub-group names (most specific first)
            if (lower.contains("solar wind"))           filter = "Solar Wind";
            else if (lower.contains("solar rooftop"))   filter = "Solar_Rooftop";
            else if (lower.contains("solar carport"))   filter = "Solar_carports";
            else if (lower.contains("ground mounted") || lower.contains("ground_mounted")) filter = "Solar_ground_mounted";
            else if (lower.contains("ccms"))            filter = "CCMS";
            else if (lower.contains("mcms"))            filter = "MCMS";
            // Check for status keywords
            else if (lower.contains("in progress") || lower.contains("in_progress"))  filter = "IN_PROGRESS";
            else if (lower.contains("completed"))                                      filter = "COMPLETED";
            else if (lower.contains("planning"))                                       filter = "PLANNING";
            else if (lower.contains("on hold") || lower.contains("on_hold"))          filter = "ON_HOLD";
            else if (lower.contains("cancelled") || lower.contains("canceled"))       filter = "CANCELLED";
            else if (lower.contains("not started") || lower.contains("not_started"))  filter = "NOT_STARTED";
            return new AiIntent(module, action, filter, null, q);
        }

        // For INVOICES, BILLS, PURCHASE_ORDERS, QUOTATIONS, ORDER_BOOK:
        // also extract group/category filter
        if ("INVOICES".equals(module) || "BILLS".equals(module) ||
            "PURCHASE_ORDERS".equals(module) || "QUOTATIONS".equals(module) ||
            "ORDER_BOOK".equals(module)) {

            if (lower.contains("solar wind"))           filter = "Solar Wind";
            else if (lower.contains("solar rooftop"))   filter = "Solar_Rooftop";
            else if (lower.contains("solar carport"))   filter = "Solar_carports";
            else if (lower.contains("ground mounted") || lower.contains("ground_mounted")) filter = "Solar_ground_mounted";
            else if (lower.contains("ccms"))            filter = "CCMS";
            else if (lower.contains("mcms"))            filter = "MCMS";
            else if (lower.contains(" epc ") || lower.endsWith(" epc") || lower.startsWith("epc ")) filter = "EPC";
            else if (lower.contains(" iot ") || lower.endsWith(" iot") || lower.startsWith("iot ")) filter = "IoT";
            else if (lower.contains("paid"))            filter = "Paid";
            else if (lower.contains("pending"))         filter = "Pending";
            else if (lower.contains("approved"))        filter = "Approved";
            else if (lower.contains("partially paid") || lower.contains("partial")) filter = "Partially Paid";

            return new AiIntent(module, action, filter, null, q);
        }

        return new AiIntent(module, action, null, null, q);
    }

    private static final Pattern PERSONAL_PATTERN = Pattern.compile(
        "\\b(my name|who am i|my email|my phone|my role|my designation|" +
        "my team|my profile|logged in as|change my|update my)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VALID_MODULES = Pattern.compile(
        "LEADS|FOLLOWUPS|CUSTOMERS|PROPOSALS|INVOICES|VENDORS|TASKS|USERS|" +
        "PROJECTS|EXPENSES|INVENTORY|BILLS|PURCHASE_ORDERS|ORDER_BOOK|QUOTATIONS|" +
        "MENU_ITEMS|PERSONAL|HOW_TO|GENERAL"
    );
    private static final Pattern VALID_ACTIONS = Pattern.compile(
        "COUNT|LIST|DETAIL|SUMMARY|HOW_TO"
    );

    public static class AiIntent {
        private final String module;
        private final String action;
        private final String filter;
        private final String nameSearch;
        private final String originalQuestion;

        public AiIntent(String module, String action, String filter,
                        String nameSearch, String originalQuestion) {
            this.module = module; this.action = action;
            this.filter = filter; this.nameSearch = nameSearch;
            this.originalQuestion = originalQuestion;
        }

        public static AiIntent general(String q) {
            return new AiIntent("GENERAL", "DETAIL", null, null, q);
        }

        public String getModule()           { return module; }
        public String getAction()           { return action; }
        public String getFilter()           { return filter; }
        public String getNameSearch()       { return nameSearch; }
        public String getOriginalQuestion() { return originalQuestion; }

        public boolean isPersonal() { return "PERSONAL".equals(module); }
        public boolean isHowTo()    { return "HOW_TO".equals(module) || "HOW_TO".equals(action); }
        public boolean isGeneral()  { return "GENERAL".equals(module); }
        // Keep isUnknown/isGuidance for backward compatibility
        public boolean isUnknown()  { return isGeneral(); }
        public boolean isGuidance() { return isHowTo(); }

        @Override
        public String toString() {
            return module + "|" + action +
                   (filter != null ? "|" + filter : "|") +
                   (nameSearch != null ? "|" + nameSearch : "|");
        }
    }
}