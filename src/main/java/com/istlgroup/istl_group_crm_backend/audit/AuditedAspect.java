package com.istlgroup.istl_group_crm_backend.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.istlgroup.istl_group_crm_backend.entity.AuditLogEntity;
import com.istlgroup.istl_group_crm_backend.service.AuditLogService;

import lombok.extern.slf4j.Slf4j;

/**
 * AuditedAspect — intercepts every @Audited method, records SUCCESS on normal
 * return and FAILURE (with the exception message) on throw, then rethrows.
 * Requires spring-boot-starter-aop (added to pom.xml).
 */
@Aspect
@Component
@Slf4j
public class AuditedAspect {

    @Autowired
    private AuditLogService auditLogService;

    /** Tells AuditHttpInterceptor this request already has a richer entry. */
    private static void markRequestAudited() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                attrs.getRequest().setAttribute(AuditHttpInterceptor.ALREADY_AUDITED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) { }
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        markRequestAudited();
        String description = audited.description().isBlank()
                ? audited.module() + " " + audited.operation() + " — "
                  + joinPoint.getSignature().getName()
                : audited.description();
        try {
            Object result = joinPoint.proceed();
            AuditLogEntity e = auditLogService.base(
                    audited.module(), audited.operation(), null, description);
            e.setEntityId(extractId(result));
            e.setEntityType(result != null ? result.getClass().getSimpleName() : null);
            auditLogService.enqueue(e);
            return result;
        } catch (Throwable t) {
            AuditLogEntity e = auditLogService.base(
                    audited.module(), audited.operation(), null, description);
            e.setStatus("FAILURE");
            e.setFailureReason(t.getMessage() != null && t.getMessage().length() > 500
                    ? t.getMessage().substring(0, 500) : t.getMessage());
            auditLogService.enqueue(e);
            throw t;
        }
    }

    /** Best-effort getId() extraction from the returned entity. */
    private Long extractId(Object result) {
        if (result == null) return null;
        try {
            var m = result.getClass().getMethod("getId");
            Object id = m.invoke(result);
            return id instanceof Long l ? l : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
