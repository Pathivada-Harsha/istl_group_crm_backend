package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.FollowupsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.FollowupRequestWrapper;

@RestController
@RequestMapping("/followups")
public class FollowupsController {
    
    @Autowired
    private FollowupsService followupsService;
    
    
    /**
     * Get all follow-ups (Admin/SuperAdmin only).
     * Regular users should use /my-followups instead.
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllFollowups(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            if (!"SUPERADMIN".equalsIgnoreCase(userRole) && !"ADMIN".equalsIgnoreCase(userRole)) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Access denied");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
            }

            List<FollowupWrapper> followups = followupsService.getAllFollowups();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get follow-ups for the requesting user, with optional group/subGroup filters.
     *
     * Query params (all optional):
     *   groupName    — restrict to this group  (null = no restriction)
     *   subGroupName — restrict to this subGroup (null = no restriction)
     *
     * Role logic (handled server-side — frontend always calls this single endpoint):
     *   SUPERADMIN / ADMIN  → userId filter is removed; they see all matching records
     *   Everyone else       → only records where assigned_to = me OR created_by = me
     *
     * When groupName is null AND subGroupName is null, customer followups
     * (which have no groupName) are included naturally.
     * When groupName is set, only lead/project followups with that group are returned
     * (customer followups with null groupName are excluded — correct behaviour).
     */
    @GetMapping("/my-followups")
    public ResponseEntity<Map<String, Object>> getMyFollowups(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(value = "groupName",    required = false) String groupName,
            @RequestParam(value = "subGroupName", required = false) String subGroupName) {
        try {
            boolean isAdmin = "SUPERADMIN".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole);

            // Admins have no userId restriction (pass null so the JPQL skips that condition).
            // Regular users are restricted to rows they own (assigned or created).
            Long effectiveUserId = isAdmin ? null : userId;

            // Normalise empty strings from query params to null so JPQL treats them as "no filter"
            String grp    = (groupName    != null && !groupName.isBlank())    ? groupName    : null;
            String subGrp = (subGroupName != null && !subGroupName.isBlank()) ? subGroupName : null;

            List<FollowupWrapper> followups =
                    followupsService.getFollowupsByUserAndFilters(effectiveUserId, grp, subGrp);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Create a new follow-up
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createFollowup(
            @RequestHeader("User-Id") Long userId,
            @RequestBody FollowupRequestWrapper request) {
        try {
            FollowupWrapper created = followupsService.createFollowup(request, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Follow-up created successfully");
            response.put("data", created);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to create follow-up: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Update a follow-up
     */
    @PutMapping("/update/{followupId}")
    public ResponseEntity<Map<String, Object>> updateFollowup(
            @PathVariable Long followupId,
            @RequestHeader("User-Id") Long userId,
            @RequestBody FollowupRequestWrapper request) {
        try {
            FollowupWrapper updated = followupsService.updateFollowup(followupId, request, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Follow-up updated successfully");
            response.put("data", updated);
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update follow-up");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get follow-ups for a lead
     */
    @GetMapping("/lead/{leadId}")
    public ResponseEntity<Map<String, Object>> getFollowupsForLead(@PathVariable Long leadId) {
        try {
            List<FollowupWrapper> followups = followupsService.getFollowupsForLead(leadId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get follow-ups by entity type and ID.
     * Supports: Customer, Lead (extensible for future entity types).
     * Called by: GET /followups/entity/Customer/{id}
     *            GET /followups/entity/Lead/{id}
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<Map<String, Object>> getFollowupsForEntity(
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        try {
            List<FollowupWrapper> followups;

            if ("Customer".equalsIgnoreCase(entityType)) {
                followups = followupsService.getFollowupsForCustomer(entityId);
            } else if ("Lead".equalsIgnoreCase(entityType)) {
                followups = followupsService.getFollowupsForLead(entityId);
            } else {
                // Generic fallback using relatedType / relatedId columns
                followups = followupsService.getFollowupsForRelatedEntity(entityType, entityId);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get overdue follow-ups
     */
    @GetMapping("/overdue")
    public ResponseEntity<Map<String, Object>> getOverdueFollowups() {
        try {
            List<FollowupWrapper> followups = followupsService.getOverdueFollowups();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get today's follow-ups
     */
    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> getTodaysFollowups() {
        try {
            List<FollowupWrapper> followups = followupsService.getTodaysFollowups();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", followups);
            response.put("count", followups.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Delete a follow-up
     */
    @DeleteMapping("/delete/{followupId}")
    public ResponseEntity<Map<String, Object>> deleteFollowup(
            @PathVariable Long followupId,
            @RequestHeader("User-Id") Long userId) {
        try {
            followupsService.deleteFollowup(followupId, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Follow-up deleted successfully");
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to delete follow-up");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}