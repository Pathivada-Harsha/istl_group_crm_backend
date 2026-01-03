package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.FollowupsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/followups")
@CrossOrigin(origins = "*")
public class FollowupsController {
    
    @Autowired
    private FollowupsService followupsService;
    
    /**
     * Get followups for a specific entity (Lead/Customer)
     */
    @GetMapping("/entity/{relatedType}/{relatedId}")
    public ResponseEntity<Map<String, Object>> getFollowupsForEntity(
            @PathVariable String relatedType,
            @PathVariable Long relatedId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            List<FollowupWrapper> followups = followupsService.getFollowupsForEntity(relatedType, relatedId);
            response.put("success", true);
            response.put("data", followups);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Get all followups created by user
     */
    @GetMapping("/my-followups")
    public ResponseEntity<Map<String, Object>> getMyFollowups(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            List<FollowupWrapper> followups = followupsService.getFollowupsByCreator(userId);
            response.put("success", true);
            response.put("data", followups);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Get all followups assigned to user
     */
    @GetMapping("/assigned-to-me")
    public ResponseEntity<Map<String, Object>> getAssignedFollowups(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            List<FollowupWrapper> followups = followupsService.getFollowupsByAssignee(userId);
            response.put("success", true);
            response.put("data", followups);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Create a new followup
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createFollowup(
            @RequestBody FollowupRequestWrapper requestWrapper,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            FollowupWrapper followup = followupsService.createFollowup(requestWrapper, userId);
            response.put("success", true);
            response.put("data", followup);
            response.put("message", "Followup created successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Update a followup
     */
    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> updateFollowup(
            @PathVariable Long id,
            @RequestBody FollowupRequestWrapper requestWrapper,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            FollowupWrapper followup = followupsService.updateFollowup(id, requestWrapper);
            response.put("success", true);
            response.put("data", followup);
            response.put("message", "Followup updated successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Delete a followup
     */
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteFollowup(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            followupsService.deleteFollowup(id);
            response.put("success", true);
            response.put("message", "Followup deleted successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Check if lead has pending followups
     */
    @GetMapping("/has-pending/lead/{leadId}")
    public ResponseEntity<Map<String, Object>> checkLeadPendingFollowups(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            boolean hasPending = followupsService.hasLeadPendingFollowups(leadId);
            int count = followupsService.getPendingFollowupsCountForLead(leadId);
            response.put("success", true);
            response.put("hasPending", hasPending);
            response.put("count", count);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}