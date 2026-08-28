package com.istlgroup.istl_group_crm_backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.istlgroup.istl_group_crm_backend.audit.AuditHttpInterceptor;
import com.istlgroup.istl_group_crm_backend.security.ActingUserArgumentResolver;

/**
 * WebConfig — LOGIN ACTIVITY MODULE.
 * Registers the global audit interceptor so every data-changing request
 * (POST/PUT/PATCH/DELETE) on every controller is recorded in the activity
 * timeline automatically. Read-only GETs and the login endpoints are
 * excluded inside the interceptor itself.
 *
 * <p>Also registers the resolver that binds {@code @ActingUserId} /
 * {@code @ActingUserRole} / {@code @ActingUserName} from the authenticated session.
 * Controllers used to read those off {@code User-Id} / {@code User-Role} /
 * {@code X-User-*} request headers, which the browser sets and an attacker therefore
 * controls; identity is now established server-side and those headers are ignored.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AuditHttpInterceptor auditHttpInterceptor;

    @Autowired
    private ActingUserArgumentResolver actingUserArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditHttpInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actingUserArgumentResolver);
    }
}
