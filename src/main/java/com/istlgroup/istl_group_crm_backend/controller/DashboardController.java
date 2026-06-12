package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.DashboardService;
import com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DashboardDTO.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DashboardController
 *
 * Endpoints:
 *   GET /dashboard/admin         → L1/L2 (SUPERADMIN, ADMIN)
 *   GET /dashboard/sales-manager → L3 roles with manager flag
 *   GET /dashboard/bd            → L4 BD roles
 *   GET /dashboard/telecaller    → TELECALLER
 *   GET /dashboard/generic       → ANY role not covered above — never 403s
 *
 * All role guards now use RoleHierarchyService.getLevelOrder() so custom
 * roles like PROCUREMENT_MANAGER, SALES_EXEC, TESTING_ROLE, etc. are
 * automatically handled by level without hardcoding role names.
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService     dashboardService;
    private final RoleHierarchyService roleHierarchyService;

    // ─────────────────────────────────────────────────────────────────────────
    // SUPER ADMIN / ADMIN  (level <= 2)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/admin")
    public ResponseEntity<Map<String, Object>> getAdminDashboard(
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole) {

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level > 2) return forbidden("Admin or SuperAdmin role required");

        try {
            AdminDashboard data = dashboardService.buildAdminDashboard();
            return ok(data);
        } catch (Exception e) {
            log.error("Admin dashboard error userId={}: {}", userId, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MANAGER / L3 roles (level == 3)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/sales-manager")
    public ResponseEntity<Map<String, Object>> getSalesManagerDashboard(
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole) {

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level != 3) return forbidden("Manager-level role required");

        try {
            SalesManagerDashboard data = dashboardService.buildSalesManagerDashboard(userId);
            return ok(data);
        } catch (Exception e) {
            log.error("Manager dashboard error userId={}: {}", userId, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BD EXECUTIVE / L4 (level >= 4, not telecaller)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/bd")
    public ResponseEntity<Map<String, Object>> getBdDashboard(
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole) {

        int level = roleHierarchyService.getLevelOrder(userRole);
        if (level < 4) return forbidden("BD Executive level role required");

        try {
            BdDashboard data = dashboardService.buildBdDashboard(userId);
            return ok(data);
        } catch (Exception e) {
            log.error("BD dashboard error userId={}: {}", userId, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TELECALLER (explicit role name — special workflow)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/telecaller")
    public ResponseEntity<Map<String, Object>> getTelecallerDashboard(
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole) {

        if (!isTelecaller(userRole)) return forbidden("Telecaller role required");

        try {
            TelecallerDashboard data = dashboardService.buildTelecallerDashboard(userId);
            return ok(data);
        } catch (Exception e) {
            log.error("Telecaller dashboard error userId={}: {}", userId, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERIC — open to ANY authenticated role, never returns 403
    // Uses level_order to determine data scope automatically.
    // Ideal for PROCUREMENT_MANAGER, SALES_EXEC, TESTING_ROLE, and any
    // future custom role without needing code changes.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/generic")
    public ResponseEntity<Map<String, Object>> getGenericDashboard(
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole) {

        try {
            GenericDashboard data = dashboardService.buildGenericDashboard(userId, userRole);
            return ok(data);
        } catch (Exception e) {
            log.error("Generic dashboard error userId={} role={}: {}", userId, userRole, e.getMessage(), e);
            return serverError(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isTelecaller(String role) {
        return role != null && role.trim().equalsIgnoreCase("TELECALLER");
    }

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private ResponseEntity<Map<String, Object>> forbidden(String msg) {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", msg));
    }

    private ResponseEntity<Map<String, Object>> serverError(String msg) {
        return ResponseEntity.status(500)
            .body(Map.of("success", false, "message", msg != null ? msg : "Internal error"));
    }
}