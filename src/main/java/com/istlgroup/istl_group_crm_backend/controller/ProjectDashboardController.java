package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectDashboardDTO;
import com.istlgroup.istl_group_crm_backend.service.ProjectDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Project Dashboard
 * Endpoint: /api/projects/{projectId}/dashboard
 */
@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectDashboardController {
    
    private final ProjectDashboardService dashboardService;
    
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
}