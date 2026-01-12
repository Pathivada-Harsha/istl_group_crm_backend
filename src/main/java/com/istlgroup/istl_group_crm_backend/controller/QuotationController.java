package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.QuotationDTO;
import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.QuotationMapper;
import com.istlgroup.istl_group_crm_backend.service.QuotationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
@CrossOrigin(origins = "${cros.allowed-origins}")
@Slf4j
public class QuotationController {
    
    private final QuotationService quotationService;
    
    /**
     * GET /api/quotations/procurement
     * Get all procurement quotations with filters
     */
    @GetMapping("/procurement")
    public ResponseEntity<?> getProcurementQuotations(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "uploadedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            Page<QuotationEntity> quotations = quotationService.getQuotations(
                    groupName, subGroupName, projectId, status, searchTerm,
                    userId, userRole, page, size, sortBy, sortDirection
            );
            
            // Convert to DTOs to avoid circular reference
            List<QuotationDTO> quotationDTOs = quotations.getContent().stream()
                    .map(QuotationMapper::toDTO)
                    .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("quotations", quotationDTOs);
            response.put("currentPage", quotations.getNumber());
            response.put("totalPages", quotations.getTotalPages());
            response.put("totalElements", quotations.getTotalElements());
            response.put("pageSize", quotations.getSize());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching quotations", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/quotations/{id}
     * Get quotation by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getQuotationById(@PathVariable Long id) {
        try {
            QuotationEntity quotation = quotationService.getQuotationById(id);
            // Convert to DTO to avoid circular reference
            QuotationDTO dto = QuotationMapper.toDTO(quotation);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("Error fetching quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * POST /api/quotations/procurement
     * Create new procurement quotation WITH FILE UPLOAD
     */
    @PostMapping(value = "/procurement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createQuotationWithFile(
            @RequestPart("quotation") String quotationJson,
            @RequestPart(value = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            // Parse JSON quotation
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            QuotationEntity quotation = mapper.readValue(quotationJson, QuotationEntity.class);
            
            // Validate and attach file if present
            if (file != null && !file.isEmpty()) {
                // Check file size (max 5MB)
                if (file.getSize() > 5 * 1024 * 1024) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("File size exceeds 5MB limit"));
                }
                
                // Check file type
                String contentType = file.getContentType();
                if (contentType == null || 
                    (!contentType.equals("application/pdf") && !contentType.startsWith("image/"))) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("Only PDF and image files are allowed"));
                }
                
                // Attach file to quotation
                quotation.setQuotationFile(file.getBytes());
                quotation.setFileName(file.getOriginalFilename());
                quotation.setFileType(contentType);
                quotation.setFileSize(file.getSize());
            }
            
            // Create quotation
            QuotationEntity created = quotationService.createQuotation(quotation, userId);
            
            // Convert to DTO to avoid circular reference
            QuotationDTO dto = QuotationMapper.toDTO(created);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Quotation created successfully", dto));
            
        } catch (Exception e) {
            log.error("Error creating quotation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/quotations/{id}/file
     * Download quotation file
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<?> downloadFile(@PathVariable Long id) {
        try {
            QuotationEntity quotation = quotationService.getQuotationById(id);
            
            if (quotation.getQuotationFile() == null || quotation.getQuotationFile().length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(createErrorResponse("No file attached to this quotation"));
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(quotation.getFileType()));
            headers.setContentDispositionFormData("attachment", quotation.getFileName());
            headers.setContentLength(quotation.getFileSize());
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(quotation.getQuotationFile());
                    
        } catch (Exception e) {
            log.error("Error downloading file for quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse("File not found"));
        }
    }
    
    /**
     * PUT /api/quotations/{id}
     * Update quotation
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateQuotation(
            @PathVariable Long id,
            @RequestBody QuotationEntity quotation
    ) {
        try {
            QuotationEntity updated = quotationService.updateQuotation(id, quotation);
            QuotationDTO dto = QuotationMapper.toDTO(updated);
            
            return ResponseEntity.ok(createSuccessResponse("Quotation updated successfully", dto));
            
        } catch (Exception e) {
            log.error("Error updating quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * PUT /api/quotations/{id}/status
     * Update quotation status
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateQuotationStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload
    ) {
        try {
            String newStatus = payload.get("status");
            if (newStatus == null || newStatus.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Status is required"));
            }
            
            QuotationEntity updated = quotationService.updateStatus(id, newStatus);
            QuotationDTO dto = QuotationMapper.toDTO(updated);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Quotation status updated to: " + newStatus, 
                    dto
            ));
            
        } catch (Exception e) {
            log.error("Error updating quotation status: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * POST /api/quotations/{id}/create-po
     * Create Purchase Order from Quotation
     */
    @PostMapping("/{id}/create-po")
    public ResponseEntity<?> createPOFromQuotation(
            @PathVariable Long id,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            Map<String, Object> result = quotationService.createPOFromQuotation(id, userId);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Purchase Order created successfully", 
                    result
            ));
            
        } catch (Exception e) {
            log.error("Error creating PO from quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * DELETE /api/quotations/{id}
     * Soft delete quotation
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteQuotation(@PathVariable Long id) {
        try {
            quotationService.deleteQuotation(id);
            
            return ResponseEntity.ok(createSuccessResponse("Quotation deleted successfully", null));
            
        } catch (Exception e) {
            log.error("Error deleting quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/quotations/vendor/{vendorId}
     * Get all quotations for a vendor
     */
    @GetMapping("/vendor/{vendorId}")
    public ResponseEntity<?> getQuotationsByVendor(@PathVariable Long vendorId) {
        try {
            List<QuotationEntity> quotations = quotationService.getQuotationsByVendor(vendorId);
            List<QuotationDTO> dtos = quotations.stream()
                    .map(QuotationMapper::toDTO)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Error fetching quotations for vendor: {}", vendorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/quotations/stats
     * Get quotation statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            QuotationService.QuotationStats stats = quotationService.getStatistics(
                    groupName, subGroupName, projectId, userId, userRole
            );
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching quotation stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * POST /api/quotations/expire-old
     * Mark expired quotations
     */
    @PostMapping("/expire-old")
    public ResponseEntity<?> markExpiredQuotations() {
        try {
            quotationService.markExpiredQuotations();
            return ResponseEntity.ok(createSuccessResponse("Expired quotations marked", null));
        } catch (Exception e) {
            log.error("Error marking expired quotations", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    // Helper methods
    
    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr != null) {
            if (userIdAttr instanceof Long) return (Long) userIdAttr;
            if (userIdAttr instanceof Integer) return ((Integer) userIdAttr).longValue();
            if (userIdAttr instanceof String) return Long.parseLong((String) userIdAttr);
        }
        
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null) {
            try {
                return Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid userId in header: {}", userIdHeader);
            }
        }
        
        return 1L;
    }
    
    private String getUserRoleFromRequest(HttpServletRequest request) {
        Object userRoleAttr = request.getAttribute("userRole");
        if (userRoleAttr != null) {
            return userRoleAttr.toString();
        }
        
        String userRoleHeader = request.getHeader("X-User-Role");
        if (userRoleHeader != null) {
            return userRoleHeader;
        }
        
        return "USER";
    }
    
    private Map<String, Object> createSuccessResponse(String message, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        if (data != null) {
            response.put("data", data);
        }
        return response;
    }
    
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
    /**
     * GET /api/quotations/approved
     * Get all approved quotations without PO
     */
    @GetMapping("/approved")
    public ResponseEntity<?> getApprovedQuotations(HttpServletRequest request) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            List<QuotationEntity> quotations = quotationService.getApprovedQuotations();
            
            // Convert to DTOs to avoid circular reference
            List<QuotationDTO> quotationDTOs = quotations.stream()
                    .map(QuotationMapper::toDTO)
                    .collect(Collectors.toList());
            
            log.info("Fetched {} approved quotations for user: {}", quotationDTOs.size(), userId);
            
            return ResponseEntity.ok(quotationDTOs);
            
        } catch (Exception e) {
            log.error("Error fetching approved quotations", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * POST /api/quotations/{id}/link-po
     * Link a PO to a quotation
     */
    @PostMapping("/{id}/link-po")
    public ResponseEntity<?> linkPOToQuotation(
            @PathVariable Long id,
            @RequestBody Map<String, Long> payload
    ) {
        try {
            Long poId = payload.get("poId");
            if (poId == null) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("PO ID is required"));
            }
            
            QuotationEntity quotation = quotationService.getQuotationById(id);
            quotation.setPoId(poId);
            quotation.setStatus("PO Created");
            
            QuotationEntity updated = quotationService.updateQuotation(id, quotation);
            QuotationDTO dto = QuotationMapper.toDTO(updated);
            
            return ResponseEntity.ok(createSuccessResponse("PO linked successfully", dto));
            
        } catch (Exception e) {
            log.error("Error linking PO to quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
}