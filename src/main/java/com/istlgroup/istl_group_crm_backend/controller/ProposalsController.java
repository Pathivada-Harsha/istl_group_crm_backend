package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
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
    @Autowired
    private com.istlgroup.istl_group_crm_backend.service.SolarProposalDocService solarProposalDocService;
    /**
     * Get all proposals with pagination
     */
    @GetMapping("/getAll")
    public ResponseEntity<Map<String, Object>> getAllProposals(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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
        @ActingUserId Long userId,
        @ActingUserRole String userRole) {
    
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        
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

    // =========================================================================
    // SOLAR PROPOSAL DOCUMENT — generated from the lead's own tabs
    //
    // Scoped to the Solar group. Other groups (CCMS / EPC / IoT / Hybrid /
    // Others) keep the generic proposal path above, untouched.
    // =========================================================================

    /**
     * Review-step defaults for a lead: everything variable, pulled from the
     * Technical Scope / BOM / Budget / Site Visit tabs, plus the last generated
     * payload when this is a re-generation.
     * GET /proposals/solar/prefill?leadId=…&proposalId=…
     */
    @GetMapping("/solar/prefill")
    public ResponseEntity<Map<String, Object>> solarPrefill(
            @RequestParam Long leadId,
            @RequestParam(required = false) Long proposalId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {

        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", solarProposalDocService.prefill(leadId, proposalId));
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Solar proposal prefill failed for lead {}", leadId, e);
            response.put("success", false);
            response.put("message", "Failed to prepare the proposal: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Fill the skeleton with the confirmed values and store the result as the
     * next version on the proposal record (creating the record on first run).
     * POST /proposals/solar/generate
     */
    @PostMapping("/solar/generate")
    public ResponseEntity<Map<String, Object>> generateSolarProposal(
            @RequestBody com.istlgroup.istl_group_crm_backend.wrapperClasses.SolarProposalDocRequest request,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {

        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> data = solarProposalDocService.generate(request, userId);
            response.put("success", true);
            response.put("data", data);
            response.put("message", "Proposal document generated (v" + data.get("version") + ")");

            if (request.getLeadId() != null) {
                try {
                    leadHistoryService.addHistory(
                        request.getLeadId(), "PROPOSAL_CREATED", null, null,
                        String.valueOf(data.get("proposalNo")),
                        "Proposal document generated: " + data.get("proposalNo") + " v" + data.get("version"),
                        userId);
                } catch (Exception historyEx) {
                    log.warn("Failed to add proposal history: {}", historyEx.getMessage());
                }
            }
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Solar proposal generation failed for lead {}", request.getLeadId(), e);
            response.put("success", false);
            response.put("message", "Failed to generate the proposal document: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /** Every stored version of a proposal's generated document (no file bytes). */
    @GetMapping("/{id}/documents")
    public ResponseEntity<Map<String, Object>> listProposalDocuments(
            @PathVariable Long id,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {

        Map<String, Object> response = new HashMap<>();
        try {
            proposalsService.getProposalById(id, userId, userRole); // permission check
            response.put("success", true);
            response.put("data", solarProposalDocService.versions(id));
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /** @param version omit for the latest. */
    @GetMapping("/{id}/documents/download")
    public ResponseEntity<byte[]> downloadProposalDocument(
            @PathVariable Long id,
            @RequestParam(required = false) Integer version,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        return serveProposalDocument(id, version, userId, userRole, false);
    }

    @GetMapping("/{id}/documents/view")
    public ResponseEntity<byte[]> viewProposalDocument(
            @PathVariable Long id,
            @RequestParam(required = false) Integer version,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        return serveProposalDocument(id, version, userId, userRole, true);
    }

    /**
     * PDF rendition of a generated Solar proposal, for in-browser preview.
     * A .docx cannot be previewed — its cover is a DrawingML grouped shape that no
     * client-side renderer draws — so the PDF is rendered alongside it at generate
     * time, and re-rendered on demand for versions that predate the feature.
     *
     * <p>{@code /download} and {@code /view} are untouched and still serve the .docx,
     * which remains the deliverable.
     *
     * <p>200 → the PDF. 404 → no such version, or no permission (deliberately not
     * disambiguated, matching {@link #serveProposalDocument}). 409 → the version
     * exists but can never be given a PDF (no stored payload), which is the
     * frontend's cue to offer the Word file instead.
     *
     * @param version omit for the latest.
     */
    @GetMapping("/{id}/documents/pdf")
    public ResponseEntity<byte[]> proposalDocumentPdf(
            @PathVariable Long id,
            @RequestParam(required = false) Integer version,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            proposalsService.getProposalById(id, userId, userRole); // permission check
            var pdf = solarProposalDocService.pdf(id, version);
            if (pdf == null || pdf.data() == null || pdf.data().length == 0) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(pdf.fileName(), StandardCharsets.UTF_8).build());
            headers.setContentLength(pdf.data().length);
            return ResponseEntity.ok().headers(headers).body(pdf.data());
        } catch (CustomException e) {
            log.warn("Proposal PDF {} v{} unavailable: {}", id, version, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Error serving proposal PDF {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Remove one generated version.
     * DELETE /proposals/{id}/documents/{version}
     *
     * <p>Deleting the current version rolls the proposal record back to the one
     * below it, so the card falls back to showing that version instead of a file
     * that is gone. The last remaining version is refused — that is what deleting
     * the proposal itself is for.
     */
    @DeleteMapping("/{id}/documents/{version}")
    public ResponseEntity<Map<String, Object>> deleteProposalDocumentVersion(
            @PathVariable Long id,
            @PathVariable Integer version,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {

        Map<String, Object> response = new HashMap<>();
        try {
            proposalsService.requireDeletable(id, userId, userRole);
            Map<String, Object> data = solarProposalDocService.deleteVersion(id, version);
            response.put("success", true);
            response.put("data", data);
            response.put("message", "v" + version + " deleted — v" + data.get("currentVersion") + " is now the latest");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to delete proposal {} document v{}", id, version, e);
            response.put("success", false);
            response.put("message", "Failed to delete this version");
            return ResponseEntity.ok(response);
        }
    }

    private ResponseEntity<byte[]> serveProposalDocument(Long id, Integer version, Long userId,
                                                         String userRole, boolean inline) {
        try {
            proposalsService.getProposalById(id, userId, userRole); // permission check
            var doc = solarProposalDocService.document(id, version);
            byte[] bytes = doc.data();
            if (bytes == null || bytes.length == 0) return ResponseEntity.notFound().build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    doc.contentType() != null ? doc.contentType() : "application/octet-stream"));
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    (inline ? "inline" : "attachment") + "; filename=\"" + doc.fileName() + "\"");
            headers.setContentLength(bytes.length);
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (CustomException e) {
            log.warn("Proposal document {} v{} unavailable: {}", id, version, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Error serving proposal document {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Every proposal on one lead the caller may see.
     * GET /proposals/by-lead/{leadId}
     */
    @GetMapping("/by-lead/{leadId}")
    public ResponseEntity<?> getProposalsByLead(
            @PathVariable Long leadId,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "data", proposalsService.getProposalsByLead(leadId, userId, userRole)
            ));
        } catch (CustomException ce) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "message", ce.getMessage()));
        } catch (Exception e) {
            log.error("Error listing proposals for lead {}", leadId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", e.getMessage()));
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        try {
            // Ownership: this write path used to accept any proposal id from any
            // authenticated caller. It answers to the same rule as the other
            // proposal write paths now.
            proposalsService.requireEditable(id, userId, userRole);

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }
            if (file.getSize() > 10L * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 10 MB limit"));
            }

            String originalName = file.getOriginalFilename();
            // The bytes are served back as application/pdf, so only accept a PDF.
            boolean pdfName = originalName != null && originalName.toLowerCase().endsWith(".pdf");
            boolean pdfType = "application/pdf".equalsIgnoreCase(file.getContentType());
            if (!pdfName && !pdfType) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only PDF files are allowed"));
            }

            byte[] bytes = file.getBytes();

            // Store blob + metadata; clear legacy path so view-offline knows to use BLOB
            ProposalWrapper updated = proposalsService.updateOfflinePdfBlob(
                    id, bytes, originalName, userId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Offline proposal uploaded successfully",
                "data", updated
            ));
        } catch (CustomException ce) {
            log.warn("Permission denied for upload-offline proposal {}: {}", id, ce.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ce.getMessage()));
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
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
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
 * Proposals a customer's order book may cite — approved AND system-generated only.
 * Backs the Proposal dropdown in the Create/Edit Order Book modals.
 * GET /proposals/by-customer/{customerId}[?includeId=]
 *
 * includeId keeps an already-linked proposal in the list even if it no longer
 * qualifies, so editing an order book cannot silently drop its proposal.
 */
@GetMapping("/by-customer/{customerId}")
public ResponseEntity<Map<String, Object>> getProposalsByCustomer(
        @PathVariable Long customerId,
        @ActingUserId Long userId,
        @ActingUserRole String userRole,
        @RequestParam(value = "includeId", required = false) Long includeId) {

    Map<String, Object> response = new HashMap<>();
    try {
        // Call service method to get proposals for this customer
        List<Map<String, Object>> proposals = proposalsService.getProposalsByCustomerForDropdown(
            customerId, userId, userRole, includeId
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