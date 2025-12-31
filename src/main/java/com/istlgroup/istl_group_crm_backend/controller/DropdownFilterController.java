package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownProjectWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.DropdownSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsSubGroupWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LeadsUserWrapper;
import com.istlgroup.istl_group_crm_backend.service.DropdownFilterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/filters")
@RequiredArgsConstructor
@CrossOrigin(origins = "${cros.allowed-origins}")
public class DropdownFilterController {
    
    private final DropdownFilterService filterService;
    
    @GetMapping("/groups")
    public ResponseEntity<List<DropdownGroupWrapper>> getAllGroups() {
        return ResponseEntity.ok(filterService.getAllGroups());
    }
    
    @GetMapping("/subgroups")
    public ResponseEntity<List<DropdownSubGroupWrapper>> getSubGroups(
            @RequestParam String groupName) {
        return ResponseEntity.ok(filterService.getSubGroupsByGroup(groupName));
    }
    
    @GetMapping("/projects")
    public ResponseEntity<List<DropdownProjectWrapper>> getProjects(
            @RequestParam String groupName,
            @RequestParam String subGroupName) {
        return ResponseEntity.ok(
            filterService.getProjectsByGroupAndSubGroup(groupName, subGroupName)
        );
    }
    
    // ============ LEADS-SPECIFIC ENDPOINTS (Unique Names) ============
    
    /**
     * Get groups for Leads module with unique wrapper
     * Endpoint: GET /api/filters/leads-groups
     */
    @GetMapping("/leads-groups")
    public ResponseEntity<List<LeadsGroupWrapper>> getLeadsGroups() {
        List<DropdownGroupWrapper> groups = filterService.getAllGroups();
        
        // Convert to LeadsGroupWrapper to avoid naming conflicts
        List<LeadsGroupWrapper> leadsGroups = groups.stream()
            .map(g -> new LeadsGroupWrapper(g.getValue(), g.getLabel()))
            .collect(Collectors.toList());
            
        return ResponseEntity.ok(leadsGroups);
    }
    
    /**
     * Get subgroups for Leads module with unique wrapper
     * Endpoint: GET /api/filters/leads-subgroups?groupName=Technology
     */
    @GetMapping("/leads-subgroups")
    public ResponseEntity<List<LeadsSubGroupWrapper>> getLeadsSubGroups(
            @RequestParam String groupName) {
        List<DropdownSubGroupWrapper> subGroups = filterService.getSubGroupsByGroup(groupName);
        
        // Convert to LeadsSubGroupWrapper to avoid naming conflicts
        List<LeadsSubGroupWrapper> leadsSubGroups = subGroups.stream()
            .map(sg -> new LeadsSubGroupWrapper(sg.getValue(), sg.getLabel()))
            .collect(Collectors.toList());
            
        return ResponseEntity.ok(leadsSubGroups);
    }
    /**
     * Get users for Leads assignment dropdown with unique wrapper
     * Endpoint: GET /api/filters/leads-users
     */
    @GetMapping("/leads-users")
    public ResponseEntity<List<LeadsUserWrapper>> getLeadsUsers() {
        return ResponseEntity.ok(filterService.getLeadsUsers()); 
    }
}