package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.service.VendorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vendors")
@RequiredArgsConstructor
//@CrossOrigin(origins = "${cros.allowed-origins}")
@Slf4j
public class VendorController {
    
    private final VendorService vendorService;
    
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection,
            @RequestHeader("x-user-id") Long userId,
            @RequestHeader("x-user-role") String userRole,
            HttpServletRequest request
    ) {
        try {
//            Long userId = getUserIdFromRequest(request);
//            String userRole = getUserRoleFromRequest(request);
            
            Page<VendorEntity> vendors = vendorService.getVendors(
                    groupName, subGroupName, projectId, category, vendorType,
                    rating, status, searchTerm, userId, userRole,
                    page, size, sortBy, sortDirection
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("vendors", vendors.getContent());
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
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            // Validate required fields
            if (vendor.getName() == null || vendor.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Vendor name is required"));
            }
            
            if (vendor.getProjectId() == null || vendor.getProjectId().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Project ID is required for filtering"));
            }
            
            VendorEntity created = vendorService.createVendor(vendor, userId);
            
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
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            VendorService.VendorStats stats = vendorService.getStatistics(
                    groupName, subGroupName, projectId, userId, userRole
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
        
        // Fallback for testing - remove in production
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
        
        // Fallback for testing - remove in production
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