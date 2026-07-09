package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.wrapperClasses.QuotationDTO;
import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.repo.QuotationRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.QuotationMapper;
import com.istlgroup.istl_group_crm_backend.service.QuotationService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.service.PurchaseOrderService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/quotations")
@RequiredArgsConstructor
@Slf4j
public class QuotationController {
    
    private final QuotationService quotationService;
    private final ProjectAccessService projectAccessService;
    private final QuotationRepository quotationRepository;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;
    private final PurchaseOrderService purchaseOrderService;

    /** Enrich vendor name for quotations that have a vendorId but no vendorName stored */
    private void enrichVendorName(QuotationDTO dto) {
        if (dto.getVendorId() != null &&
                (dto.getVendorName() == null || dto.getVendorName().isBlank())) {
            vendorRepository.findById(dto.getVendorId())
                    .ifPresent(v -> dto.setVendorName(v.getName()));
        }
    }

    /** Batch-enrich project names for a list of DTOs using a single DB query. */
    private void enrichProjectNames(List<QuotationDTO> dtos) {
        List<String> projectIds = dtos.stream()
            .map(QuotationDTO::getProjectId)
            .filter(pid -> pid != null && !pid.isBlank())
            .distinct()
            .collect(Collectors.toList());
        if (projectIds.isEmpty()) return;
        Map<String, String> nameMap = new HashMap<>();
        projectRepository.findByProjectUniqueIdIn(projectIds)
            .forEach(p -> nameMap.put(p.getProjectUniqueId(), p.getProjectName()));
        dtos.forEach(dto -> {
            if (dto.getProjectId() != null)
                dto.setProjectName(nameMap.getOrDefault(dto.getProjectId(), null));
        });
    }

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
            @RequestParam(required = false) String uploadedFrom,
            @RequestParam(required = false) String uploadedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "uploadedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);

            if (userId != null && userId > 0
                    && projectId != null && !projectId.isBlank()
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
                return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "You do not have access to this project"));
            }

            Page<QuotationEntity> quotations = quotationService.getQuotations(
                    groupName, subGroupName, projectId, status, searchTerm,
                    uploadedFrom, uploadedTo, userId, userRole, page, size, sortBy, sortDirection
            );
            
            List<QuotationDTO> quotationDTOs = quotations.getContent().stream()
                    .map(QuotationMapper::toDTO)
                    .collect(Collectors.toList());

            // Enrich vendor name for existing vendors (vendorId set, vendorName blank in DB)
            quotationDTOs.forEach(this::enrichVendorName);
            enrichProjectNames(quotationDTOs);

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
            QuotationDTO dto = QuotationMapper.toDTO(quotation);
            enrichVendorName(dto);
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
            
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            QuotationEntity quotation = mapper.readValue(quotationJson, QuotationEntity.class);
            
            if (file != null && !file.isEmpty()) {
                if (file.getSize() > 5 * 1024 * 1024) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("File size exceeds 5MB limit"));
                }
                
                String contentType = file.getContentType();
                if (contentType == null || 
                    (!contentType.equals("application/pdf") && !contentType.startsWith("image/"))) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("Only PDF and image files are allowed"));
                }
                
                quotation.setQuotationFile(file.getBytes());
                quotation.setFileName(file.getOriginalFilename());
                quotation.setFileType(contentType);
                quotation.setFileSize(file.getSize());
            }
            
            QuotationEntity created = quotationService.createQuotation(quotation, userId);
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
     * PUT /api/quotations/{id}
     * Update quotation WITH OPTIONAL FILE UPLOAD
     */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateQuotationWithFile(
            @PathVariable Long id,
            @RequestPart("quotation") String quotationJson,
            @RequestPart(value = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            log.info("📝 Updating quotation ID: {} by user: {}", id, userId);
            
            // Parse JSON quotation
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            QuotationEntity updatedQuotation = mapper.readValue(quotationJson, QuotationEntity.class);
            
            // Handle file upload if present
            if (file != null && !file.isEmpty()) {
                log.info("📄 New file uploaded: {}", file.getOriginalFilename());
                
                // Validate file size
                if (file.getSize() > 5 * 1024 * 1024) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("File size exceeds 5MB limit"));
                }
                
                // Validate file type
                String contentType = file.getContentType();
                if (contentType == null || 
                    (!contentType.equals("application/pdf") && !contentType.startsWith("image/"))) {
                    return ResponseEntity.badRequest()
                            .body(createErrorResponse("Only PDF and image files are allowed"));
                }
                
                // Attach new file to quotation
                updatedQuotation.setQuotationFile(file.getBytes());
                updatedQuotation.setFileName(file.getOriginalFilename());
                updatedQuotation.setFileType(contentType);
                updatedQuotation.setFileSize(file.getSize());
            }
            
            // Update quotation
            QuotationEntity updated = quotationService.updateQuotation(id, updatedQuotation);
            QuotationDTO dto = QuotationMapper.toDTO(updated);
            
            log.info("✅ Quotation {} updated successfully", updated.getQuoteNo());
            
            return ResponseEntity.ok(createSuccessResponse("Quotation updated successfully", dto));
            
        } catch (Exception e) {
            log.error("❌ Error updating quotation: {}", id, e);
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
            log.info("🗑️ Deleting quotation ID: {}", id);
            
            quotationService.deleteQuotation(id);
            
            log.info("✅ Quotation ID {} deleted successfully", id);
            
            return ResponseEntity.ok(createSuccessResponse("Quotation deleted successfully", null));
            
        } catch (Exception e) {
            log.error("❌ Error deleting quotation: {}", id, e);
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
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String uploadedFrom,
            @RequestParam(required = false) String uploadedTo,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            QuotationService.QuotationStats stats = quotationService.getStatistics(
                    groupName, subGroupName, projectId, status, searchTerm,
                    uploadedFrom, uploadedTo, userId, userRole
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
    
    /**
     * GET /api/quotations/approved
     * Get all procurement quotations available for PO creation
     * (status: New, Shortlisted, or Approved — excludes Rejected, Expired, PO Created)
     */
    @GetMapping("/approved")
    public ResponseEntity<?> getApprovedQuotations(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request) {
        try {
            log.info("Fetching available quotations for PO - group: {}, subGroup: {}, project: {}", 
                     groupName, subGroupName, projectId);
            
            List<QuotationEntity> quotations = quotationRepository.findAvailableForPO();
            
            if (groupName != null && !groupName.trim().isEmpty()) {
                quotations = quotations.stream()
                        .filter(q -> groupName.equals(q.getGroupName()))
                        .collect(Collectors.toList());
            }
            
            if (subGroupName != null && !subGroupName.trim().isEmpty()) {
                quotations = quotations.stream()
                        .filter(q -> subGroupName.equals(q.getSubGroupName()))
                        .collect(Collectors.toList());
            }
            
            if (projectId != null && !projectId.trim().isEmpty()) {
                quotations = quotations.stream()
                        .filter(q -> projectId.equals(q.getProjectId()))
                        .collect(Collectors.toList());
            }
            
            List<Map<String, Object>> quotationList = quotations.stream()
                    .map(q -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", q.getId());
                        map.put("quoteNo", q.getQuoteNo());
                        map.put("vendorId", q.getVendorId());
                        map.put("vendorName", q.getVendorName());
                        map.put("vendorContact", q.getVendorContact());
                        map.put("rfqId", q.getRfqId());
                        map.put("category", q.getCategory());
                        map.put("totalValue", q.getTotalValue());
                        map.put("validTill", q.getValidTill());
                        map.put("status", q.getStatus());
                        map.put("groupName", q.getGroupName());
                        map.put("subGroupName", q.getSubGroupName());
                        map.put("projectId", q.getProjectId());
                        map.put("paymentTerms", q.getPaymentTerms());
                        map.put("notes", q.getNotes());
                        map.put("uploadedAt", q.getUploadedAt());
                        return map;
                    })
                    .collect(Collectors.toList());
            
            log.info("Returning {} available quotations for PO creation", quotationList.size());
            return ResponseEntity.ok(quotationList);
            
        } catch (Exception e) {
            log.error("Error fetching available quotations", e);
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
            quotationService.updateQuotation(id, quotation);

            // Derive the ordering status from the actual PO quantities instead of blindly
            // stamping "PO Created" — a partial order must land on "Partially Ordered".
            purchaseOrderService.syncQuotationOrderingStatus(id);

            QuotationDTO dto = QuotationMapper.toDTO(quotationService.getQuotationById(id));

            return ResponseEntity.ok(createSuccessResponse("PO linked successfully", dto));
            
        } catch (Exception e) {
            log.error("Error linking PO to quotation: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/quotations/orderbook-items/{projectId}
     * Get order book items for a project
     */
    @GetMapping("/orderbook-items/{projectId}")
    public ResponseEntity<Map<String, Object>> getOrderBookItemsByProject(
            @PathVariable String projectId,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {
        try {
            log.info("Fetching order book items for project: {}", projectId);
            
            List<Map<String, Object>> items = quotationService.getOrderBookItemsByProject(projectId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching order book items for project: {}", projectId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // Helper methods
    
    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr != null) {
            if (userIdAttr instanceof Long)    return (Long) userIdAttr;
            if (userIdAttr instanceof Integer) return ((Integer) userIdAttr).longValue();
            if (userIdAttr instanceof String)  return Long.parseLong((String) userIdAttr);
        }

        // Check both X-User-Id and User-Id header variants
        for (String headerName : new String[]{"X-User-Id", "User-Id", "x-user-id", "user-id"}) {
            String val = request.getHeader(headerName);
            if (val != null && !val.isBlank()) {
                try { return Long.parseLong(val.trim()); }
                catch (NumberFormatException ignored) {}
            }
        }

        return null; // Return null instead of 1L so callers can detect missing header
    }

    private String getUserRoleFromRequest(HttpServletRequest request) {
        Object userRoleAttr = request.getAttribute("userRole");
        if (userRoleAttr != null) return userRoleAttr.toString();

        // Check both X-User-Role and User-Role header variants
        for (String headerName : new String[]{"X-User-Role", "User-Role", "x-user-role", "user-role"}) {
            String val = request.getHeader(headerName);
            if (val != null && !val.isBlank()) return val;
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
}