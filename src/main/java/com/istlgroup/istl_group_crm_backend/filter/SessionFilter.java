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

        // ✅ Allow public APIs
        if (
            path.startsWith("/login/userLogin") ||
            path.startsWith("/login/logout") ||
            path.startsWith("/error")
        ) {
            chain.doFilter(request, response);
            return;
        }

        // ✅ Get session safely (JAVA WAY)
        HttpSession session = req.getSession(false);

        SecurityContext context = null;
        if (session != null) {
            context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
            );
        }

        Authentication authentication =
                (context != null) ? context.getAuthentication() : null;

        // ❌ Not logged in / session expired
        if (authentication == null || !authentication.isAuthenticated()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.getWriter().write("SESSION_EXPIRED");
            return;
        }

        // ✅ Session valid
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
