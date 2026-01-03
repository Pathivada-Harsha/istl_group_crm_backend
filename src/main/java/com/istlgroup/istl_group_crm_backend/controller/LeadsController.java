package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadFilterRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadRequestWrapper;
import com.istlgroup.istl_group_crm_backend.service.LeadsService;

@RestController
@RequestMapping("/leads")
@CrossOrigin(origins = "${cros.allowed-origins}")
public class LeadsController {

    @Autowired
    private LeadsService leadsService;

    /**
     * Get all leads based on user role
     * @param userId - ID of the requesting user (from JWT token or session)
     * @param userRole - Role of the requesting user (SUPERADMIN, ADMIN, USER, etc.)
     */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllLeads(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        try {
        	
            List<LeadWrapper> leads = leadsService.getAllLeads(userId, userRole, groupName, subGroupName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get filtered leads with search and filters
     */
    @PostMapping("/filter")
    public ResponseEntity<Map<String, Object>> getFilteredLeads(
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody LeadFilterRequestWrapper filterRequest) {
        try {
            List<LeadWrapper> leads = leadsService.getFilteredLeads(userId, userRole, filterRequest);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get lead by ID
     */
    @GetMapping("/{leadId}")
    public ResponseEntity<Map<String, Object>> getLeadById(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            LeadWrapper lead = leadsService.getLeadById(leadId, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", lead);
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Internal server error");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Create a new lead
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createLead(
            @RequestHeader("User-Id") Long userId,
            @RequestBody LeadRequestWrapper requestDTO) {
        try {
            LeadWrapper createdLead = leadsService.createLead(requestDTO, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead created successfully");
            response.put("data", createdLead);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to create lead: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Update an existing lead
     */
    @PutMapping("/update/{leadId}")
    public ResponseEntity<Map<String, Object>> updateLead(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole,
            @RequestBody LeadRequestWrapper requestDTO) {
        try {
            LeadWrapper updatedLead = leadsService.updateLead(leadId, requestDTO, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead updated successfully");
            response.put("data", updatedLead);
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update lead");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Delete a lead (soft delete)
     */
    @DeleteMapping("/delete/{leadId}")
    public ResponseEntity<Map<String, Object>> deleteLead(
            @PathVariable Long leadId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            leadsService.deleteLead(leadId, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Lead deleted successfully");
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to delete lead");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get leads by group name
     */
    @GetMapping("/group/{groupName}")
    public ResponseEntity<Map<String, Object>> getLeadsByGroup(
            @PathVariable String groupName,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsByGroup(groupName, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get leads by status
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<Map<String, Object>> getLeadsByStatus(
            @PathVariable String status,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsByStatus(status, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get leads assigned to a specific user
     */
    @GetMapping("/assigned/{assignedUserId}")
    public ResponseEntity<Map<String, Object>> getLeadsAssignedTo(
            @PathVariable Long assignedUserId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsAssignedTo(assignedUserId, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get leads created by a specific user
     */
    @GetMapping("/createdBy/{createdByUserId}")
    public ResponseEntity<Map<String, Object>> getLeadsCreatedBy(
            @PathVariable Long createdByUserId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            List<LeadWrapper> leads = leadsService.getLeadsCreatedBy(createdByUserId, userId, userRole);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", leads);
            response.put("count", leads.size());
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}