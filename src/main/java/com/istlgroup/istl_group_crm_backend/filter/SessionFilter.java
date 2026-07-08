package com.istlgroup.istl_group_crm_backend.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;
import com.istlgroup.istl_group_crm_backend.entity.UserSessionEntity;
import com.istlgroup.istl_group_crm_backend.service.SessionRegistryService;

import java.io.IOException;

@Component
public class SessionFilter implements Filter {

    // ── LOGIN ACTIVITY MODULE — session registry ─────────────────────────
    @Autowired
    private SessionRegistryService sessionRegistryService;
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getRequestURI();
        String method = req.getMethod();

        // ✅ Allow OPTIONS preflight always (required for CORS)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(request, response);
            return;
        }

        // ✅ Allow public endpoints without session check
        if (
        		path.contains("/login/userLogin") ||
        	    path.contains("/login/logout") ||
        	    path.contains("/login/forgot-password") ||
        	    path.contains("/error")
        ) {
            chain.doFilter(request, response);
            return;
        }

        // ✅ Check session
        HttpSession session = req.getSession(false);

        SecurityContext context = null;
        if (session != null) {
            context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
            );
        }

        Authentication authentication =
                (context != null) ? context.getAuthentication() : null;

        // ❌ Session missing or expired — return proper JSON
        if (authentication == null || !authentication.isAuthenticated()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");           // ✅ JSON content type
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write(
                "{\"error\":\"SESSION_EXPIRED\",\"message\":\"Your session has expired. Please log in again.\"}"
            );
            return;
        }

        // 🔐 LOGIN ACTIVITY: registry check — an EVICTED or ADMIN_TERMINATED
        // session is rejected here, which is what makes "logout oldest device"
        // and admin remote termination take effect immediately on that device.
        // A null status means the session predates this module — allowed.
        String registryStatus = sessionRegistryService.statusOf(session.getId());
        if (registryStatus != null && !UserSessionEntity.STATUS_ACTIVE.equals(registryStatus)) {
            session.invalidate();
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            if (UserSessionEntity.STATUS_EVICTED.equals(registryStatus)) {
                res.getWriter().write(
                    "{\"error\":\"SESSION_EVICTED\",\"message\":\"You were signed out because your account was used to sign in on another device.\"}"
                );
            } else if (UserSessionEntity.STATUS_ADMIN_TERMINATED.equals(registryStatus)) {
                res.getWriter().write(
                    "{\"error\":\"SESSION_EVICTED\",\"message\":\"Your session was ended by an administrator.\"}"
                );
            } else {
                res.getWriter().write(
                    "{\"error\":\"SESSION_EXPIRED\",\"message\":\"Your session has expired. Please log in again.\"}"
                );
            }
            return;
        }

        // 🔐 LOGIN ACTIVITY: refresh last_seen_at (throttled to 1/min inside)
        sessionRegistryService.touch(session.getId());

        // ✅ Session valid — continue
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}