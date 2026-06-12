package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.service.ProposalsService;
import com.istlgroup.istl_group_crm_backend.service.LeadHistoryService;
import com.istlgroup.istl_group_crm_backend.service.ProposalsPDFService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.ProposalRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.List; 
import java.util.UUID;


import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/proposals")
//@CrossOrigin(origins = "${cros.allowed-origins}")
public class ProposalsController {

    private static final Logger log = LoggerFactory.getLogger(ProposalsController.class);

    @Autowired
    private ProposalsService proposalsService;
    
    @Autowired
    private ProposalsPDFService proposalsPDFService;
    @Autowired
    private LeadHistoryService leadHistoryService;
    /**
     * Get all proposals with pagination
     */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllProposals(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        
        Map<String, Object> response = new HashMap<>();
        try {
        	System.err.println(subGroupName);
            Page<ProposalWrapper> proposalPage = proposalsService.getAllProposalsPaginated(
                userId, userRole, groupName,subGroupName, page, size
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
            return ResponseEntity.ok(response);
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
            return ResponseEntity.ok(response);
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
            return ResponseEntity.ok(response);
        }
    }
    
  

@PostMapping("/create")
public ResponseEntity<Map<String, Object>> createProposal(
        @RequestBody ProposalRequestWrapper requestWrapper,
        @RequestHeader("User-Id") Long userId,
        @RequestHeader("User-Role") String userRole) {
    
    Map<String, Object> response = new HashMap<>();
    try {
        ProposalWrapper proposal = proposalsService.createProposal(requestWrapper, userId);
        
        // Add to lead history if leadId is present
        if (requestWrapper.getLeadId() != null) {
            try {
                leadHistoryService.addHistory(
                    requestWrapper.getLeadId(),
                    "PROPOSAL_CREATED",
                    null,
                    null,
                    proposal.getProposalNo(),
                    "Proposal created: " + proposal.getProposalNo(),
                    userId
                );
            } catch (Exception historyEx) {
                // Log but don't fail the proposal creation
                System.err.println("Failed to add proposal history: " + historyEx.getMessage());
            }
        }
        
        response.put("success", true);
        response.put("data", proposal);
        response.put("message", "Proposal created successfully");
        return ResponseEntity.ok(response);
    } catch (CustomException e) {
        response.put("success", false);
        response.put("message", e.getMessage());
        return ResponseEntity.ok(response);
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
        }catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage()); // ✅ only business message
            return ResponseEntity.ok(response);     // ✅ still 200
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to load data");
            return ResponseEntity.ok(response);
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
            return ResponseEntity.ok(response);
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

    /**
     * Upload an offline proposal PDF (scanned/external proposal given by client).
     * POST /proposals/{id}/upload-offline
     * Stores file bytes directly in DB (MEDIUMBLOB). No disk write for new uploads.
     */
    @PostMapping("/{id}/upload-offline")
    public ResponseEntity<?> uploadOfflineProposal(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }
            if (file.getSize() > 10L * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 10 MB limit"));
            }

            String originalName = file.getOriginalFilename();
            byte[] bytes = file.getBytes();

            // Store blob + metadata; clear legacy path so view-offline knows to use BLOB
            ProposalWrapper updated = proposalsService.updateOfflinePdfBlob(
                    id, bytes, originalName, userId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Offline proposal uploaded successfully",
                "data", updated
            ));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "File upload failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Serve the offline proposal PDF file for viewing.
     * GET /proposals/{id}/view-offline
     * Priority: BLOB column -> disk fallback (legacy files).
     */
    @GetMapping("/{id}/view-offline")
    public ResponseEntity<byte[]> viewOfflineProposal(
            @PathVariable Long id,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        try {
            ProposalWrapper proposal = proposalsService.getProposalById(id, userId, userRole);

            byte[] pdfBytes = proposalsService.getOfflinePdfBytes(id);
            if (pdfBytes == null || pdfBytes.length == 0) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.add("Content-Disposition", "inline; filename=\"" + proposal.getOfflinePdfName() + "\"");
            headers.setContentLength(pdfBytes.length);
            return ResponseEntity.ok().headers(headers).body(pdfBytes);

        } catch (CustomException ce) {
            log.warn("Permission denied for view-offline proposal {}: {}", id, ce.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            log.error("Error serving offline proposal {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resolve stored path to an actual file path (kept for legacy disk fallback in FileMigrationRunner).
     */
    private Path resolveProposalFilePath(String storedPath) {
        Path direct = Paths.get(storedPath);
        if (Files.exists(direct)) return direct;

        String fileName = Paths.get(storedPath.replace('\\', '/')).getFileName().toString();
        String uploadDir = System.getProperty("user.dir") + "/uploads/proposals/offline/";
        Path resolved = Paths.get(uploadDir).resolve(fileName);
        log.debug("Resolving proposal path: stored='{}' -> resolved='{}'", storedPath, resolved);
        return resolved;
    }

// ADD THIS METHOD TO YOUR ProposalController.java

/**
 * NEW: Get proposals filtered by customer ID
 * Returns proposals for a specific customer
 * GET /proposals/by-customer/{customerId}
 */
@GetMapping("/by-customer/{customerId}")
public ResponseEntity<Map<String, Object>> getProposalsByCustomer(
        @PathVariable Long customerId,
        @RequestHeader("User-Id") Long userId,
        @RequestHeader("User-Role") String userRole) {
    
    Map<String, Object> response = new HashMap<>();
    try {
        // Call service method to get proposals for this customer
        List<Map<String, Object>> proposals = proposalsService.getProposalsByCustomerForDropdown(
            customerId, userId, userRole
        );
        
        response.put("success", true);
        response.put("data", proposals);
        response.put("message", "Proposals fetched successfully");
        
        return ResponseEntity.ok(response);
    } catch (Exception e) {
        response.put("success", false);
        response.put("message", "Failed to fetch proposals: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
}