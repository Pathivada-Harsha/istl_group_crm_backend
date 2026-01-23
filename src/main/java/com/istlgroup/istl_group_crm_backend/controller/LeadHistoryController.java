package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.LeadHistoryService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadHistoryWrapper;

@RestController
@RequestMapping("/leads")
public class LeadHistoryController {
    
    @Autowired
    private LeadHistoryService historyService;
    
    /**
     * Get history/timeline for a lead
     */
    @GetMapping("/{leadId}/history")
    public ResponseEntity<Map<String, Object>> getLeadHistory(@PathVariable Long leadId) {
        try {
            List<LeadHistoryWrapper> history = historyService.getHistoryForLead(leadId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", history);
            response.put("count", history.size());
            
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch lead history");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}