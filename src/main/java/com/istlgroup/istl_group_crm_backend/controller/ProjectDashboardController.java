package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO;
import com.istlgroup.istl_group_crm_backend.service.ProjectDashboardService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.util.*;
import java.util.Collections;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * REST Controller for Project Dashboard
 * Endpoint: /api/projects/{projectId}/dashboard
 */
@Slf4j
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectDashboardController {
    
    private final ProjectDashboardService dashboardService;
    private final OrderBookItemRepo orderBookItemRepo;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    
    /**
     * Get complete dashboard data for a project
     * 
     * @param projectId The project unique ID
     * @return Complete dashboard DTO with financial, procurement, and timeline data
     */
    @GetMapping("/{projectId}/dashboard")
    public ResponseEntity<ProjectDashboardDTO> getProjectDashboard(
            @PathVariable String projectId) {
        
        log.info("GET /api/projects/{}/dashboard - Fetching project dashboard", projectId);
        
        try {
            ProjectDashboardDTO dashboard = dashboardService.getDashboardData(projectId);
            log.info("Successfully retrieved dashboard for project: {}", projectId);
            return ResponseEntity.ok(dashboard);
            
        } catch (RuntimeException e) {
            log.error("Error fetching dashboard for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Unexpected error fetching dashboard for project {}", projectId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get only financial data for a project
     * 
     * @param projectId The project unique ID
     * @return Financial data DTO
     */
    @GetMapping("/{projectId}/dashboard/financial")
    public ResponseEntity<ProjectDashboardDTO.FinancialData> getProjectFinancialData(
            @PathVariable String projectId) {
        
        log.info("GET /api/projects/{}/dashboard/financial", projectId);
        
        try {
            ProjectDashboardDTO dashboard = dashboardService.getDashboardData(projectId);
            return ResponseEntity.ok(dashboard.getFinancialData());
        } catch (RuntimeException e) {
            log.error("Error fetching financial data for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Get aggregated dashboard for all projects, a group, or a group+subGroup.
     *
     * Query params (all optional):
     *   groupName    – filter by group  (e.g. "EPC")
     *   subGroupName – filter by sub-group (e.g. "Solar_Rooftop")
     *
     * Behaviour:
     *   No params          → aggregate ALL active projects
     *   groupName only     → aggregate all projects in that group
     *   groupName+subGroup → aggregate all projects in that group+subGroup
     */
    @GetMapping("/dashboard/aggregate")
    public ResponseEntity<?> getAggregatedDashboard(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestHeader(value = "X-User-Id",   required = false) Long   userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        log.info("GET /api/projects/dashboard/aggregate groupName={} subGroupName={} userId={} userRole={}",
                 groupName, subGroupName, userId, userRole);

        try {
            Object result = dashboardService.getAggregatedDashboard(groupName, subGroupName, userId, userRole);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error fetching aggregated dashboard", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get only procurement data for a project
     * 
     * @param projectId The project unique ID
     * @return Procurement data DTO
     */
    @GetMapping("/{projectId}/dashboard/procurement")
    public ResponseEntity<ProjectDashboardDTO.ProcurementData> getProjectProcurementData(
            @PathVariable String projectId) {
        
        log.info("GET /api/projects/{}/dashboard/procurement", projectId);
        
        try {
            ProjectDashboardDTO dashboard = dashboardService.getDashboardData(projectId);
            return ResponseEntity.ok(dashboard.getProcurementData());
        } catch (RuntimeException e) {
            log.error("Error fetching procurement data for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * GET /api/projects/dashboard/capacity
     * Fast: reads pre-computed capacity_value/capacity_unit from the projects table.
     * Respects user project-access grants — same restriction as the aggregate endpoint.
     */
    @GetMapping("/dashboard/capacity")
    public ResponseEntity<?> getCapacitySummary(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestHeader(value = "X-User-Id",   required = false) Long   userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        log.info("GET /api/projects/dashboard/capacity groupName={} subGroupName={} userId={} userRole={}",
                 groupName, subGroupName, userId, userRole);

        try {
            // ── Resolve access: admins see all; others only see their accessible projects ──
            boolean isAdmin = projectAccessService.isBypassRole(userRole);
            List<String> accessibleIds = isAdmin ? null
                    : projectAccessService.getAccessibleProjectIds(userId, userRole);

            // If non-admin has no accessible projects at all, return empty
            if (!isAdmin && (accessibleIds == null || accessibleIds.isEmpty())) {
                return ResponseEntity.ok(Map.of("subGroups", Collections.emptyList()));
            }

            List<Map<String, Object>> subGroups = new ArrayList<>();

            // Helper: build unitBreakdown for a project (access already enforced at project level)
            Function<String, List<Map<String, Object>>> getBreakdown = (pid) -> {
                if (pid == null || pid.isBlank()) return Collections.emptyList();
                // Skip if non-admin and project not in accessible list
                if (!isAdmin && accessibleIds != null && !accessibleIds.contains(pid))
                    return Collections.emptyList();
                List<Map<String, Object>> bd = new ArrayList<>();
                for (Object[] br : orderBookItemRepo.findUnitBreakdownByProject(pid)) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("unit",     br[0] != null ? br[0].toString() : "Nos");
                    b.put("quantity", br[1] != null ? ((Number) br[1]).doubleValue() : 0);
                    bd.add(b);
                }
                return bd;
            };

            // Helper: build allUnitTotals for a subgroup, filtered to accessible projects
            BiFunction<String, String, List<Map<String, Object>>> getSubGroupUnits = (gn, sg) -> {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object[] r : orderBookItemRepo.findUnitTotalsBySubGroup(gn, sg)) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("unit",     r[0] != null ? r[0].toString() : "Nos");
                    u.put("quantity", r[1] != null ? ((Number) r[1]).doubleValue() : 0);
                    result.add(u);
                }
                return result;
            };

            if (subGroupName != null && !subGroupName.isBlank()) {
                // Use access-filtered project query for totals
                List<Object[]> projRows = isAdmin
                    ? projectRepository.findCapacityByProject(groupName, subGroupName)
                    : projectRepository.findCapacityByProjectFiltered(groupName, subGroupName, accessibleIds);

                double total = projRows.stream()
                    .mapToDouble(r -> r[2] != null ? ((Number) r[2]).doubleValue() : 0).sum();
                String unit = projRows.isEmpty() ? "Units"
                    : (projRows.get(0)[3] != null ? projRows.get(0)[3].toString() : "Units");

                // Modal rows — per order book level, filtered to accessible projects
                List<Object[]> obRows = orderBookItemRepo.findOrderBookCapacityBySubGroup(groupName, subGroupName);
                if (!isAdmin && accessibleIds != null) {
                    final List<String> ids = accessibleIds;
                    obRows = obRows.stream()
                        .filter(r -> r[0] != null && ids.contains(r[0].toString()))
                        .collect(java.util.stream.Collectors.toList());
                }
                List<Map<String, Object>> projects = buildOrderBookEntries(obRows, getBreakdown, unit);

                long distinctProjects = projects.stream()
                    .map(p -> (String) p.get("projectId"))
                    .filter(pid -> pid != null && !pid.isBlank())
                    .distinct().count();

                if (!projects.isEmpty()) {
                    Map<String, Object> sg = new LinkedHashMap<>();
                    sg.put("subGroupName",  subGroupName);
                    sg.put("totalQuantity", total);
                    sg.put("unit",          unit);
                    sg.put("projectCount",  distinctProjects > 0 ? distinctProjects : (long) projRows.size());
                    sg.put("projects",      projects);
                    sg.put("allUnitTotals", getSubGroupUnits.apply(groupName, subGroupName));
                    subGroups.add(sg);
                }
            } else {
                // ALL / GROUP scope — use access-filtered subgroup query
                List<Object[]> rows = isAdmin
                    ? projectRepository.findCapacityBySubGroup(groupName)
                    : projectRepository.findCapacityBySubGroupFiltered(groupName, accessibleIds);

                Map<String, Map<String, Object>> map = new LinkedHashMap<>();
                for (Object[] r : rows) {
                    String sg   = r[0] != null ? r[0].toString() : "Unknown";
                    double qty  = r[1] != null ? ((Number) r[1]).doubleValue() : 0;
                    String unit = r[2] != null ? r[2].toString() : "Units";
                    long   cnt  = r[3] != null ? ((Number) r[3]).longValue() : 0;

                    map.computeIfAbsent(sg, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("subGroupName",  sg);
                        m.put("totalQuantity", 0.0);
                        m.put("unit",          unit);
                        m.put("projectCount",  0L);
                        m.put("projects",      new ArrayList<>());
                        return m;
                    });
                    Map<String, Object> entry = map.get(sg);
                    entry.put("totalQuantity", ((Number) entry.get("totalQuantity")).doubleValue() + qty);
                    entry.put("projectCount",  ((Number) entry.get("projectCount")).longValue() + cnt);
                    entry.put("allUnitTotals", getSubGroupUnits.apply(groupName, sg));

                    // Modal rows — filter to accessible projects
                    List<Object[]> obRows = orderBookItemRepo.findOrderBookCapacityBySubGroup(groupName, sg);
                    if (!isAdmin && accessibleIds != null) {
                        final List<String> ids = accessibleIds;
                        obRows = obRows.stream()
                            .filter(r2 -> r2[0] != null && ids.contains(r2[0].toString()))
                            .collect(java.util.stream.Collectors.toList());
                    }
                    List<Map<String, Object>> obEntries = buildOrderBookEntries(obRows, getBreakdown, unit);
                    long distinctProjs = obEntries.stream()
                        .map(p -> (String) p.get("projectId"))
                        .filter(pid -> pid != null && !pid.isBlank())
                        .distinct().count();
                    entry.put("projects", obEntries);
                    if (distinctProjs > 0) entry.put("projectCount", distinctProjs);
                }
                subGroups.addAll(map.values());
            }

            return ResponseEntity.ok(Map.of("subGroups", subGroups));

        } catch (Exception e) {
            log.error("Error fetching capacity summary", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String normaliseUnit(String subGroup, String unit) {
        if (subGroup == null) return unit != null ? unit : "Nos";
        String sg = subGroup.toLowerCase();
        if (sg.contains("ccms") || sg.contains("mcms") || sg.contains("itms")) return "Units";
        if (unit != null) {
            String u = unit.toLowerCase();
            if (u.contains("kw") || u.contains("mw")) return unit;
        }
        return unit != null ? unit : "Nos";
    }

    /**
     * Builds per-order-book modal entries from the raw query rows.
     * Rows: [projectId, orderBookNo, orderTitle, unit, SUM(quantity), customerName]
     * Groups multi-unit rows for the same order book into one entry with unitBreakdown.
     */
    private List<Map<String, Object>> buildOrderBookEntries(
            List<Object[]> rows,
            java.util.function.Function<String, List<Map<String, Object>>> getBreakdown,
            String defaultUnit) {

        // key = orderBookNo → entry
        Map<String, Map<String, Object>> obMap = new LinkedHashMap<>();

        for (Object[] r : rows) {
            String pid          = r[0] != null ? r[0].toString() : "";
            String obNo         = r[1] != null ? r[1].toString() : "";
            String title        = r[2] != null ? r[2].toString() : "";
            String unit         = r[3] != null ? r[3].toString() : defaultUnit;
            double qty          = r[4] != null ? ((Number) r[4]).doubleValue() : 0;
            String customerName = r.length > 5 && r[5] != null ? r[5].toString() : "";

            String key = pid + "||" + obNo;
            obMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projectId",     pid);
                m.put("orderBookNo",   obNo);
                m.put("orderTitle",    title);
                m.put("customerName",  customerName);
                m.put("quantity",      0.0);
                m.put("unit",          unit);
                m.put("unitBreakdown", new ArrayList<Map<String, Object>>());
                return m;
            });

            Map<String, Object> entry = obMap.get(key);
            // For headline quantity use the first/dominant unit row
            double current = ((Number) entry.get("quantity")).doubleValue();
            if (current == 0) entry.put("unit", unit);
            entry.put("quantity", current + qty);

            // Add to unitBreakdown chips
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bd = (List<Map<String, Object>>) entry.get("unitBreakdown");
            Map<String, Object> chip = new LinkedHashMap<>();
            chip.put("unit", unit);
            chip.put("quantity", qty);
            bd.add(chip);
        }

        return new ArrayList<>(obMap.values());
    }
}