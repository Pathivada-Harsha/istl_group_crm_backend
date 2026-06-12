package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.entity.ProjectEntity;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.service.VendorService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vendors")
@RequiredArgsConstructor
//@CrossOrigin(origins = "${cros.allowed-origins}")
@Slf4j
public class VendorController {
    
    private final VendorService vendorService;
    private final ProjectAccessService projectAccessService;
    private final ProjectRepository projectRepository;
    
    /**
     * GET /api/vendors
     * Get all vendors (only vendors with POs - totalOrders > 0)
     */
    @GetMapping
    public ResponseEntity<?> getVendors(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String vendorType,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String createdAtFrom,
            @RequestParam(required = false) String createdAtTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole,
            HttpServletRequest request
    ) {
        try {
            // Project access check — if a specific project is selected, verify user has access
            // Project access check — guard against null/zero userId from missing headers
            if (userId != null && userId > 0
                    && projectId != null && !projectId.isBlank()
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
                return ResponseEntity.status(403)
                    .body(Map.of("success", false, "message", "You do not have access to this project"));
            }

            Page<VendorEntity> vendors = vendorService.getVendors(
                    groupName, subGroupName, projectId, category, vendorType,
                    rating, status, searchTerm, createdAtFrom, createdAtTo,
                    userId, userRole, page, size, sortBy, sortDirection
            );
            
            Map<String, Object> response = new HashMap<>();

            // Batch-lookup project names so the table can show "Project Name (ID)"
            // instead of just the raw project ID.
            List<VendorEntity> vendorList = vendors.getContent();
            List<String> projectIds = vendorList.stream()
                .map(VendorEntity::getProjectId)
                .filter(pid -> pid != null && !pid.isBlank())
                .distinct()
                .collect(Collectors.toList());
            Map<String, String> projectNameMap = new HashMap<>();
            if (!projectIds.isEmpty()) {
                projectRepository.findByProjectUniqueIdIn(projectIds)
                    .forEach(p -> projectNameMap.put(p.getProjectUniqueId(), p.getProjectName()));
            }
            // Build enriched vendor list — each map is a copy of the entity fields
            // plus a projectName entry derived from the lookup above.
            List<Map<String, Object>> enrichedVendors = vendorList.stream().map(vendor -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id",                 vendor.getId());
                m.put("name",               vendor.getName());
                m.put("email",              vendor.getEmail());
                m.put("phone",              vendor.getPhone());
                m.put("contactPerson",      vendor.getContactPerson());
                m.put("vendorCode",         vendor.getVendorCode());
                m.put("category",           vendor.getCategory());
                m.put("vendorType",         vendor.getVendorType());
                m.put("status",             vendor.getStatus());
                m.put("rating",             vendor.getRating());
                m.put("totalOrders",        vendor.getTotalOrders());
                m.put("totalPurchaseValue", vendor.getTotalPurchaseValue());
                m.put("lastPurchaseDate",   vendor.getLastPurchaseDate());
                m.put("groupName",          vendor.getGroupName());
                m.put("subGroupName",       vendor.getSubGroupName());
                m.put("projectId",          vendor.getProjectId());
                m.put("projectName",        projectNameMap.getOrDefault(vendor.getProjectId(), null));
                m.put("city",               vendor.getCity());
                m.put("state",              vendor.getState());
                m.put("district",           vendor.getDistrict());
                m.put("gstNumber",          vendor.getGstNumber());
                m.put("notes",              vendor.getNotes());
                m.put("assignedTo",         vendor.getAssignedTo());
                m.put("createdBy",          vendor.getCreatedBy());
                m.put("createdAt",          vendor.getCreatedAt());
                m.put("updatedAt",          vendor.getUpdatedAt());
                // Additional fields needed by the vendor detail page
                m.put("address",            vendor.getAddress());
                m.put("pincode",            vendor.getPincode());
                m.put("website",            vendor.getWebsite());
                m.put("lastPurchaseAmount", vendor.getLastPurchaseAmount());
                m.put("kycVerified",        vendor.getKycVerified() != null && vendor.getKycVerified());
                return m;
            }).collect(Collectors.toList());

            response.put("vendors", enrichedVendors);
            response.put("currentPage", vendors.getNumber());
            response.put("totalPages", vendors.getTotalPages());
            response.put("totalElements", vendors.getTotalElements());
            response.put("pageSize", vendors.getSize());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching vendors", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
 // ADD TO VendorController.java
    /**
     * GET /api/vendors/for-bills
     * Get vendors for bills - includes vendors from vendors table + vendors from POs (new vendors)
     * Filtered by project and deduplicated
     */
    @GetMapping("/for-bills")
    public ResponseEntity<?> getVendorsForBills(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request
    ) {
        try {
            log.info("Fetching vendors for bills - group: {}, subGroup: {}, project: {}", 
                     groupName, subGroupName, projectId);
            
            List<Map<String, Object>> vendors = vendorService.getVendorsForBills(
                groupName, subGroupName, projectId
            );
            
            return ResponseEntity.ok(vendors);
            
        } catch (Exception e) {
            log.error("Error fetching vendors for bills", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
    /**
     * GET /api/vendors/by-group-subgroup?groupName=X&subGroupName=Y
     * Get vendors filtered by group and subgroup
     * Used by Quotations form to show only relevant vendors
     */
    @GetMapping("/by-group-subgroup")
    public ResponseEntity<Map<String, Object>> getVendorsByGroupAndSubGroup(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String userRole,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName) {
        try {
            log.info("Fetching vendors by group: {}, subGroup: {}", groupName, subGroupName);
            
            // Call service to get filtered vendors
            List<VendorEntity> vendors = vendorService.getVendorsByGroupAndSubGroup(groupName, subGroupName);
            
            // Convert to simple DTO for dropdown
            List<Map<String, Object>> vendorList = vendors.stream()
                    .map(v -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", v.getId());
                        map.put("name", v.getName());
                        map.put("contact", v.getPhone());
                        map.put("email", v.getEmail());
                        map.put("groupName", v.getGroupName());
                        map.put("subGroupName", v.getSubGroupName());
                        return map;
                    })
                    .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", vendorList);
            response.put("count", vendorList.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching vendors by group/subgroup", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    /**
     * GET /api/vendors/{id}
     * Get vendor by ID with purchase history
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getVendorById(@PathVariable Long id) {
        try {
            VendorEntity vendor = vendorService.getVendorById(id);
            return ResponseEntity.ok(vendor);
        } catch (Exception e) {
            log.error("Error fetching vendor: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * POST /api/vendors
     * Create vendor manually (not recommended - vendors auto-created from PO)
     */
    @PostMapping
    public ResponseEntity<?> createVendor(
            @RequestBody VendorEntity vendor,
            @RequestHeader(value = "X-User-Id",   required = false) Long   xUserId,
            @RequestHeader(value = "User-Id",      required = false) Long   userId,
            @RequestHeader(value = "X-User-Role",  required = false) String xUserRole,
            @RequestHeader(value = "User-Role",    required = false) String userRole
    ) {
        try {
            // Accept userId from either X-User-Id or User-Id header (frontend sends both)
            Long resolvedUserId = (xUserId != null && xUserId > 0) ? xUserId
                                : (userId  != null && userId  > 0) ? userId
                                : null;

            if (resolvedUserId == null) {
                log.warn("createVendor called with no valid userId in headers");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(createErrorResponse("User identity could not be determined. Please log in again."));
            }

            // Validate required fields
            if (vendor.getName() == null || vendor.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Vendor name is required"));
            }

            // projectId is optional — vendor may belong to multiple projects
            // or be assigned a project later after creation

            VendorEntity created = vendorService.createVendor(vendor, resolvedUserId);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Vendor created successfully", created));

        } catch (Exception e) {
            log.error("Error creating vendor", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * PUT /api/vendors/{id}
     * Update vendor information
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateVendor(
            @PathVariable Long id,
            @RequestBody VendorEntity vendor
    ) {
        try {
            VendorEntity updated = vendorService.updateVendor(id, vendor);
            
            return ResponseEntity.ok(createSuccessResponse("Vendor updated successfully", updated));
            
        } catch (Exception e) {
            log.error("Error updating vendor: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * DELETE /api/vendors/{id}
     * Soft delete vendor (mark as inactive)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVendor(@PathVariable Long id) {
        try {
            vendorService.deleteVendor(id);
            
            return ResponseEntity.ok(createSuccessResponse("Vendor deactivated successfully", null));
            
        } catch (Exception e) {
            log.error("Error deleting vendor: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/vendors/category/{category}
     * Get vendors by category
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<?> getVendorsByCategory(@PathVariable String category) {
        try {
            return ResponseEntity.ok(vendorService.getVendorsByCategory(category));
        } catch (Exception e) {
            log.error("Error fetching vendors by category: {}", category, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/vendors/type/{vendorType}
     * Get vendors by type
     */
    @GetMapping("/type/{vendorType}")
    public ResponseEntity<?> getVendorsByType(@PathVariable String vendorType) {
        try {
            return ResponseEntity.ok(vendorService.getVendorsByType(vendorType));
        } catch (Exception e) {
            log.error("Error fetching vendors by type: {}", vendorType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/vendors/stats
     * Get vendor statistics with project filtering
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStatistics(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String createdAtFrom,
            @RequestParam(required = false) String createdAtTo,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole
    ) {
        try {
            if (userId == null) userId = 1L;
            if (userRole == null) userRole = "USER";
            
            VendorService.VendorStats stats = vendorService.getStatistics(
                    groupName, subGroupName, projectId, status, category, searchTerm,
                    createdAtFrom, createdAtTo, userId, userRole
            );
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching vendor stats", e);
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
        
        // No userId in request — log warning and return null (caller must handle)
        log.warn("createVendor: no userId found in request attributes or X-User-Id header");
        return null;
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
        
        return "USER"; // default role when header missing
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
     * GET /api/vendors/dropdown
     * Fetch vendors for dropdown (id, name, and phone)
     * Filters by created_by or assigned_to based on current user
     */
    @GetMapping("/dropdown")
    public ResponseEntity<List<Map<String, Object>>> getVendorsForDropdown(
            @RequestHeader("X-User-Id") Long userId) {
        
        try {
            log.info("Fetching vendor dropdown for user: {}", userId);
            List<Map<String, Object>> vendors = vendorService.getVendorsForDropdown(userId);
            log.info("Found {} vendors for dropdown", vendors.size());
            return ResponseEntity.ok(vendors);
        } catch (Exception e) {
            log.error("Error fetching vendor dropdown for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
    
    /**
     * GET /api/vendors/dropdown/with-history
     * COMMENTED OUT FOR NOW - NOT BEING USED
     */
    /*
    @GetMapping("/dropdown/with-history")
    public ResponseEntity<List<Map<String, Object>>> getVendorsWithHistory(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) String projectId) {
        
        try {
            log.info("Fetching vendor dropdown with history for user: {}, project: {}", userId, projectId);
            List<Map<String, Object>> vendors = vendorService.getVendorsWithQuotationHistory(userId, projectId);
            log.info("Found {} vendors with history", vendors.size());
            return ResponseEntity.ok(vendors);
        } catch (Exception e) {
            log.error("Error fetching vendor dropdown with history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
    */
}