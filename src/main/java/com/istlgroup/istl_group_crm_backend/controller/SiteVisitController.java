package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.SiteVisitService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SiteVisitRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SiteVisitWrapper;

@RestController
@RequestMapping("/site-visits")
public class SiteVisitController {

    @Autowired
    private SiteVisitService siteVisitService;

    /** All site visit reports for a lead. */
    @GetMapping("/lead/{leadId}")
    public ResponseEntity<Map<String, Object>> getByLead(@PathVariable Long leadId) {
        try {
            List<SiteVisitWrapper> visits = siteVisitService.getByLead(leadId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", visits);
            response.put("count", visits.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Distinct list of users who visited this lead's site(s). */
    @GetMapping("/lead/{leadId}/visited-users")
    public ResponseEntity<Map<String, Object>> getVisitedUsers(@PathVariable Long leadId) {
        try {
            List<Map<String, Object>> users = siteVisitService.getVisitedUsers(leadId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", users);
            response.put("count", users.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Create a site visit report (lead-access holders, admins, or the assigned visitor). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody SiteVisitRequestWrapper request) {
        try {
            SiteVisitWrapper created = siteVisitService.create(request, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Site visit report saved");
            response.put("data", created);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save site visit: " + e.getMessage());
        }
    }

    /** Update an existing site visit report. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole,
            @RequestBody SiteVisitRequestWrapper request) {
        try {
            SiteVisitWrapper updated = siteVisitService.update(id, request, userId, userRole);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Site visit report updated");
            response.put("data", updated);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update site visit: " + e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", message);
        return ResponseEntity.status(status).body(errorResponse);
    }
}
