package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.istlgroup.istl_group_crm_backend.service.LeadAnalyticsService;

@RestController
@RequestMapping("/analytics")
public class LeadAnalyticsController {

    @Autowired private LeadAnalyticsService analyticsService;

    /**
     * Lead analytics for the dashboard.
     * range: this_month | last_month | last_3_months | this_year | last_year | last_12_months
     */
    @GetMapping("/leads")
    public ResponseEntity<Map<String, Object>> leadAnalytics(
            @RequestParam(value = "range", required = false, defaultValue = "last_12_months") String range,
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", analyticsService.buildLeadAnalytics(range));
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Failed to build lead analytics: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    @GetMapping("/team-performance")
    public ResponseEntity<Map<String, Object>> teamPerformance(
            @RequestHeader(value = "User-Id") Long userId,
            @RequestHeader(value = "User-Role") String userRole) {
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", analyticsService.buildTeamLeadPerformance(userId, userRole));
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Failed to build team performance: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }
}