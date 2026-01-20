package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.service.PurchaseOrderService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PurchaseOrderDropdownWrapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;  

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
//@CrossOrigin(origins = "${cros.allowed-origins}")
@Slf4j
public class PurchaseOrderController {
    
    private final PurchaseOrderService purchaseOrderService;
    
    /**
     * GET /api/purchase-orders
     * Get all purchase orders with filters
     */
    @GetMapping
    public ResponseEntity<?> getPurchaseOrders(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "orderDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            Page<PurchaseOrderEntity> purchaseOrders = purchaseOrderService.getPurchaseOrders(
                    groupName, subGroupName, projectId, status, searchTerm,
                    userId, userRole, page, size, sortBy, sortDirection
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("purchaseOrders", purchaseOrders.getContent());
            response.put("currentPage", purchaseOrders.getNumber());
            response.put("totalPages", purchaseOrders.getTotalPages());
            response.put("totalElements", purchaseOrders.getTotalElements());
            response.put("pageSize", purchaseOrders.getSize());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching purchase orders", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/purchase-orders/{id}
     * Get purchase order by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPurchaseOrderById(@PathVariable Long id) {
        try {
            PurchaseOrderEntity po = purchaseOrderService.getPurchaseOrderById(id);
            return ResponseEntity.ok(po);
        } catch (Exception e) {
            log.error("Error fetching purchase order: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
 // ADD THIS METHOD TO YOUR EXISTING PurchaseOrderController.java

    /**
     * POST /api/purchase-orders/from-quotation
     * Create PO from quotation data sent from frontend
     */
    @PostMapping("/from-quotation")
    public ResponseEntity<?> createPOFromQuotation(
            @RequestBody Map<String, Object> poRequest,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            // Extract and validate quotationId
            Object quotationIdObj = poRequest.get("quotationId");
            if (quotationIdObj == null) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Quotation ID is required"));
            }
            Long quotationId = Long.parseLong(quotationIdObj.toString());
            
            // Extract PO data
            String orderDate = (String) poRequest.get("orderDate");
            String expectedDelivery = (String) poRequest.get("expectedDelivery");
            String paymentTerms = (String) poRequest.get("paymentTerms");
            String shippingAddress = (String) poRequest.get("shippingAddress");
            String notes = (String) poRequest.get("notes");
            
            // Validate required fields
            if (orderDate == null || orderDate.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Order date is required"));
            }
            
            if (expectedDelivery == null || expectedDelivery.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Expected delivery date is required"));
            }
            
            // Extract items
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemsData = (List<Map<String, Object>>) poRequest.get("items");
            
            if (itemsData == null || itemsData.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("At least one item is required"));
            }
            
            // Create PO with custom data
            PurchaseOrderEntity po = purchaseOrderService.createPOFromQuotationWithCustomData(
                quotationId, 
                userId,
                orderDate,
                expectedDelivery,
                paymentTerms != null ? paymentTerms : "",
                shippingAddress != null ? shippingAddress : "",
                notes != null ? notes : "",
                itemsData
            );
            
            // Link PO back to quotation
            try {
                linkPOToQuotation(quotationId, po.getId());
            } catch (Exception e) {
                log.warn("Failed to link PO to quotation: {}", e.getMessage());
                // Don't fail the whole operation for this
            }
            
            // Return success with PO data
            Map<String, Object> response = createSuccessResponse(
                "Purchase Order created successfully", 
                null
            );
            response.put("poNo", po.getPoNo());
            response.put("poId", po.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (Exception e) {
            log.error("Error creating PO from quotation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Link PO back to quotation
     */
    private void linkPOToQuotation(Long quotationId, Long poId) {
        try {
            // Update quotation via HTTP call
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // Get auth token from current context
            String authToken = "Bearer " + java.util.Optional.ofNullable(
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            ).map(attrs -> attrs.getAttribute("authToken", 0)).orElse("");
            
            headers.set("Authorization", authToken);
            
            Map<String, Long> body = new HashMap<>();
            body.put("poId", poId);
            
            HttpEntity<Map<String, Long>> entity = new HttpEntity<>(body, headers);
            
            String url = "http://localhost:8080/api/quotations/" + quotationId + "/link-po";
            restTemplate.postForEntity(url, entity, String.class);
            
            log.info("Successfully linked PO {} to quotation {}", poId, quotationId);
        } catch (Exception e) {
            log.warn("Failed to link PO to quotation: {}", e.getMessage());
            throw e;
        }
    }
    /**
     * POST /api/purchase-orders/from-quotation/{quotationId}
     * Create PO from approved quotation (auto-creates vendor)
     */
    @PostMapping("/from-quotation/{quotationId}")
    public ResponseEntity<?> createFromQuotation(
            @PathVariable Long quotationId,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            PurchaseOrderEntity po = purchaseOrderService.createFromQuotation(quotationId, userId);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse(
                            "Purchase Order created from quotation successfully. Vendor auto-created/updated.", 
                            po
                    ));
            
        } catch (Exception e) {
            log.error("Error creating PO from quotation: {}", quotationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * POST /api/purchase-orders
     * Create PO manually (without quotation)
     */
    @PostMapping
    public ResponseEntity<?> createPurchaseOrder(
            @RequestBody PurchaseOrderEntity po,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            
            PurchaseOrderEntity created = purchaseOrderService.createPurchaseOrder(po, userId);
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createSuccessResponse("Purchase Order created successfully", created));
            
        } catch (Exception e) {
            log.error("Error creating purchase order", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * PUT /api/purchase-orders/{id}
     * Update purchase order
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updatePurchaseOrder(
            @PathVariable Long id,
            @RequestBody PurchaseOrderEntity po
    ) {
        try {
            PurchaseOrderEntity updated = purchaseOrderService.updatePurchaseOrder(id, po);
            
            return ResponseEntity.ok(createSuccessResponse("Purchase Order updated successfully", updated));
            
        } catch (Exception e) {
            log.error("Error updating purchase order: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * PUT /api/purchase-orders/{id}/status
     * Update PO status (Draft → Approved → Ordered → In-Transit → Delivered)
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updatePOStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request
    ) {
        try {
            String newStatus = payload.get("status");
            if (newStatus == null || newStatus.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Status is required"));
            }
            
            Long userId = getUserIdFromRequest(request);
            
            PurchaseOrderEntity updated = purchaseOrderService.updateStatus(id, newStatus, userId);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Purchase Order status updated to: " + newStatus, 
                    updated
            ));
            
        } catch (Exception e) {
            log.error("Error updating PO status: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * PUT /api/purchase-orders/{id}/items/{itemId}/deliver
     * Mark item as delivered (partial or full)
     */
    @PutMapping("/{id}/items/{itemId}/deliver")
    public ResponseEntity<?> markItemDelivered(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> payload
    ) {
        try {
            Object deliveredQtyObj = payload.get("deliveredQty");
            if (deliveredQtyObj == null) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("deliveredQty is required"));
            }
            
            BigDecimal deliveredQty = new BigDecimal(deliveredQtyObj.toString());
            
            PurchaseOrderEntity updated = purchaseOrderService.markItemDelivered(id, itemId, deliveredQty);
            
            return ResponseEntity.ok(createSuccessResponse(
                    "Item delivery recorded successfully", 
                    updated
            ));
            
        } catch (Exception e) {
            log.error("Error marking item delivered - PO: {}, Item: {}", id, itemId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * DELETE /api/purchase-orders/{id}
     * Cancel/soft delete purchase order
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePurchaseOrder(@PathVariable Long id) {
        try {
            purchaseOrderService.deletePurchaseOrder(id);
            
            return ResponseEntity.ok(createSuccessResponse("Purchase Order cancelled successfully", null));
            
        } catch (Exception e) {
            log.error("Error deleting purchase order: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/purchase-orders/vendor/{vendorId}
     * Get all POs for a vendor
     */
    @GetMapping("/vendor/{vendorId}")
    public ResponseEntity<?> getPurchaseOrdersByVendor(@PathVariable Long vendorId) {
        try {
            List<PurchaseOrderEntity> pos = purchaseOrderService.getPurchaseOrdersByVendor(vendorId);
            return ResponseEntity.ok(pos);
        } catch (Exception e) {
            log.error("Error fetching POs for vendor: {}", vendorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse(e.getMessage()));
        }
    }
    
    /**
     * GET /api/purchase-orders/stats
     * Get purchase order statistics with project filtering
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
            
            PurchaseOrderService.POStats stats = purchaseOrderService.getStatistics(
                    groupName, subGroupName, projectId, userId, userRole
            );
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching PO stats", e);
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
    
    
    
 // Add this endpoint to your PurchaseOrderController.java

    /**
     * GET /api/purchase-orders/dropdown - Get POs for dropdown
     */
    @GetMapping("/dropdown")
    public ResponseEntity<List<PurchaseOrderDropdownWrapper>> getPurchaseOrdersForDropdown(
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String subGroupName,
            @RequestParam(required = false) String projectId
    ) {
        try {
            List<PurchaseOrderDropdownWrapper> pos = purchaseOrderService.getPurchaseOrdersForDropdown(
                groupName, subGroupName, projectId
            );
            return ResponseEntity.ok(pos);
        } catch (Exception e) {
            log.error("Error fetching POs for dropdown", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}