package com.istlgroup.istl_group_crm_backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.istlgroup.istl_group_crm_backend.audit.AuditHttpInterceptor;

/**
 * WebConfig — LOGIN ACTIVITY MODULE.
 * Registers the global audit interceptor so every data-changing request
 * (POST/PUT/PATCH/DELETE) on every controller is recorded in the activity
 * timeline automatically. Read-only GETs and the login endpoints are
 * excluded inside the interceptor itself.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AuditHttpInterceptor auditHttpInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditHttpInterceptor).addPathPatterns("/**");
    }
}
