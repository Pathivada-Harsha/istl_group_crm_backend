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

import java.io.IOException;

@Component
public class SessionFilter implements Filter {

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

        // ✅ Session valid — continue
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}