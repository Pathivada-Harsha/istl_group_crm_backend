package com.istlgroup.istl_group_crm_backend.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Audited — place on any service method to record it in the audit trail.
 *
 * Example:
 *   @Audited(module = "LEADS", operation = "CREATE", description = "Lead created")
 *   public LeadsEntity createLead(...) { ... }
 *
 * Success/failure, the exception message, user, session, IP, browser and
 * device are captured automatically by AuditedAspect. For old/new value
 * diffs call auditLogService.logChange(...) explicitly inside the method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Module name, e.g. LEADS, CUSTOMERS, USERS, ROLES, REPORTS. */
    String module();

    /** Operation, e.g. CREATE, UPDATE, DELETE, APPROVE, EXPORT, STATUS_CHANGE. */
    String operation();

    /** Human-readable description shown in the activity timeline. */
    String description() default "";
}
