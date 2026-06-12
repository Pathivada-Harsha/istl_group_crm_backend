package com.istlgroup.istl_group_crm_backend.constants;

/**
 * Central catalogue of every notification module and type the CRM raises.
 * Kept as String constants (not enums) so they map 1:1 with the
 * {@code module} / {@code notification_type} VARCHAR columns and remain
 * forward-compatible if new types are added without a DB migration.
 *
 * IMPORTANT: only the events explicitly approved for the Notification System
 * are listed here. Excluded events (status changes, comments, priority flags,
 * proposals, quotations, projects, purchase orders, customer events, etc.)
 * are intentionally NOT defined so they can never be raised by mistake.
 */
public final class NotificationConstants {

    private NotificationConstants() { }

    /** Module buckets — also used by the frontend to build the deep-link URL. */
    public static final class Module {
        public static final String LEAD     = "LEAD";
        public static final String TASK     = "TASK";
        public static final String FOLLOWUP = "FOLLOWUP";
        public static final String INVOICE  = "INVOICE";
        private Module() { }
    }

    /** Approved notification types. */
    public static final class Type {

        // ── Follow-up ────────────────────────────────────────────────
        public static final String FOLLOWUP_ASSIGNED    = "FOLLOWUP_ASSIGNED";
        public static final String FOLLOWUP_REASSIGNED  = "FOLLOWUP_REASSIGNED";
        public static final String FOLLOWUP_DUE_TODAY   = "FOLLOWUP_DUE_TODAY";
        public static final String FOLLOWUP_OVERDUE     = "FOLLOWUP_OVERDUE";
        public static final String FOLLOWUP_REMINDER    = "FOLLOWUP_REMINDER";

        // ── Task ─────────────────────────────────────────────────────
        public static final String TASK_ASSIGNED        = "TASK_ASSIGNED";
        public static final String TASK_REASSIGNED      = "TASK_REASSIGNED";
        public static final String TASK_DUE_TODAY       = "TASK_DUE_TODAY";
        public static final String TASK_OVERDUE         = "TASK_OVERDUE";
        public static final String TASK_COMPLETED       = "TASK_COMPLETED";

        // ── Lead ─────────────────────────────────────────────────────
        public static final String LEAD_ASSIGNED            = "LEAD_ASSIGNED";
        public static final String LEAD_REASSIGNED          = "LEAD_REASSIGNED";
        public static final String LEAD_CONVERTED           = "LEAD_CONVERTED_TO_CUSTOMER";
        public static final String LEAD_ASSIGNED_SALES_EXEC = "LEAD_ASSIGNED_TO_SALES_EXECUTIVE";

        // ── Invoice ──────────────────────────────────────────────────
        public static final String INVOICE_CREATED      = "INVOICE_CREATED";
        public static final String INVOICE_PENDING_APPROVAL = "INVOICE_PENDING_APPROVAL";
        public static final String INVOICE_APPROVED     = "INVOICE_APPROVED";
        public static final String INVOICE_REJECTED     = "INVOICE_REJECTED";
        public static final String INVOICE_PAYMENT      = "INVOICE_PAYMENT_RECEIVED";

        private Type() { }
    }
}