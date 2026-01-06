package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.ProposalsService;
import com.istlgroup.istl_group_crm_backend.service.ProposalsPDFService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalRequestWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/proposals")
@CrossOrigin(origins = "*")
public class ProposalsController {
    
    @Autowired
    private ProposalsService proposalsService;
    
    @Autowired
    private ProposalsPDFService proposalsPDFService;
    
    /**
     * Get all proposals with pagination
     */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllProposals(
            @RequestParam(required = false) String groupName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            Page<ProposalWrapper> proposalPage = proposalsService.getAllProposalsPaginated(
                userId, userRole, groupName, page, size
            );
            
            Map<String, Object> pageData = new HashMap<>();
            pageData.put("content", proposalPage.getContent());
            pageData.put("currentPage", proposalPage.getNumber());
            pageData.put("totalElements", proposalPage.getTotalElements());
            pageData.put("totalPages", proposalPage.getTotalPages());
            
            response.put("success", true);
            response.put("data", pageData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Filter proposals with pagination
     */
    @PostMapping("/filter")
    public ResponseEntity<Map<String, Object>> filterProposals(
            @RequestBody ProposalRequestWrapper filterRequest,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            int page = filterRequest.getPage() != null ? filterRequest.getPage() : 0;
            int size = filterRequest.getSize() != null ? filterRequest.getSize() : 10;
            
            Page<ProposalWrapper> proposalPage = proposalsService.getFilteredProposalsPaginated(
                userId, userRole, filterRequest, page, size
            );
            
            Map<String, Object> pageData = new HashMap<>();
            pageData.put("content", proposalPage.getContent());
            pageData.put("currentPage", proposalPage.getNumber());
            pageData.put("totalElements", proposalPage.getTotalElements());
            pageData.put("totalPages", proposalPage.getTotalPages());
            
            response.put("success", true);
            response.put("data", pageData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Get proposal by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getProposalById(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            ProposalWrapper proposal = proposalsService.getProposalById(id, userId, userRole);
            response.put("success", true);
            response.put("data", proposal);
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Create new proposal
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createProposal(
            @RequestBody ProposalRequestWrapper requestWrapper,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            ProposalWrapper proposal = proposalsService.createProposal(requestWrapper, userId);
            response.put("success", true);
            response.put("data", proposal);
            response.put("message", "Proposal created successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Update proposal
     */
    @PutMapping("/update/{id}")
    public ResponseEntity<Map<String, Object>> updateProposal(
            @PathVariable Long id,
            @RequestBody ProposalRequestWrapper requestWrapper,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            ProposalWrapper proposal = proposalsService.updateProposal(id, requestWrapper, userId, userRole);
            response.put("success", true);
            response.put("data", proposal);
            response.put("message", "Proposal updated successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Delete proposal
     */
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Map<String, Object>> deleteProposal(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
            proposalsService.deleteProposal(id, userId, userRole);
            response.put("success", true);
            response.put("message", "Proposal deleted successfully");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * Download proposal as PDF
     */
    @GetMapping("/download-pdf/{id}")
    public ResponseEntity<byte[]> downloadProposalPDF(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        try {
            byte[] pdfBytes = proposalsPDFService.generateProposalPDF(id, userId, userRole);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "proposal-" + id + ".pdf");
            headers.setContentLength(pdfBytes.length);
            
            return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}