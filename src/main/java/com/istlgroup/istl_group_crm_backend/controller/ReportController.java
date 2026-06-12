package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.ReportService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProjectReportDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * GET /api/reports/project/{projectId}
     * Returns full report data for the given project.
     */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<?> getProjectReport(@PathVariable String projectId) {
        log.info("GET /api/reports/project/{}", projectId);
        try {
            ProjectReportDTO report = reportService.generateReport(projectId);
            return ResponseEntity.ok(report);
        } catch (RuntimeException e) {
            log.error("Error generating report for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error generating report for project {}", projectId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to generate report"));
        }
    }
}