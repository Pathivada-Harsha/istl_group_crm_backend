package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.service.OrderBookItemCatalogueService;
import com.istlgroup.istl_group_crm_backend.service.PurchaseOrderService;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemRequestWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PurchaseOrderDropdownWrapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile; 

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;  

@RestController
@RequestMapping("/purchase-orders")
@RequiredArgsConstructor
//@CrossOrigin(origins = "${cros.allowed-origins}")
@Slf4j
public class PurchaseOrderController {
    
    private final PurchaseOrderService purchaseOrderService;
    private final com.istlgroup.istl_group_crm_backend.service.PurchaseOrderPdfService purchaseOrderPdfService;
    private final ProjectAccessService projectAccessService;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final OrderBookItemCatalogueService catalogueService;
    private final ProjectRepository projectRepository;
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
        @RequestParam(required = false) String paymentStatus,
        @RequestParam(required = false) String searchTerm,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "orderDate") String sortBy,
        @RequestParam(defaultValue = "DESC") String sortDirection,
        @RequestParam(required = false) String orderDateFrom,
        @RequestParam(required = false) String orderDateTo,
        @RequestHeader(value = "X-User-Id", required = false) Long userId,
        @RequestHeader(value = "X-User-Role", required = false) String userRole
) {
    try {
        if (projectId != null && !projectId.isBlank()
                && userId != null && userId > 0
                    && !projectAccessService.canAccessProject(projectId, userId, userRole)) {
            return ResponseEntity.status(403)
                .body(Map.of("success", false, "message", "You do not have access to this project"));
        }

        Page<PurchaseOrderEntity> pos = purchaseOrderService.getPurchaseOrders(
            groupName,
            subGroupName,
            projectId,
            status,
            paymentStatus,
            searchTerm,
            userId,
            userRole,
            page,
            size,
            sortBy,
            sortDirection,
            orderDateFrom,
            orderDateTo
        );

        // Enrich vendor names for POs that have vendorId but blank vendorName
        pos.getContent().forEach(purchaseOrderService::enrichVendorName);

        // Batch-lookup project names so the table can show name + ID
        List<String> projectIds = pos.getContent().stream()
            .map(PurchaseOrderEntity::getProjectId)
            .filter(pid -> pid != null && !pid.isBlank())
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        Map<String, String> projectNameMap = new java.util.HashMap<>();
        if (!projectIds.isEmpty()) {
            projectRepository.findByProjectUniqueIdIn(projectIds)
                .forEach(p -> projectNameMap.put(p.getProjectUniqueId(), p.getProjectName()));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("purchaseOrders", pos.getContent());
        response.put("projectNames", projectNameMap);
        response.put("currentPage", pos.getNumber());
        response.put("totalPages", pos.getTotalPages());
        response.put("totalElements", pos.getTotalElements());

        return ResponseEntity.ok(response);
    } catch (Exception e) {
        return ResponseEntity.badRequest().body(
            Map.of("error", e.getMessage())
        );
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
 * Create PO from quotation data with support for new vendors
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
        
        // Extract vendor info (support both existing and new vendors)
        Long vendorId = poRequest.get("vendorId") != null ? 
                Long.parseLong(poRequest.get("vendorId").toString()) : null;
        String vendorName = (String) poRequest.get("vendorName");
        String vendorContact = (String) poRequest.get("vendorContact");
        
        // Extract PO data
        String groupName = (String) poRequest.get("groupName");
        String subGroupName = (String) poRequest.get("subGroupName");
        String projectId = (String) poRequest.get("projectId");
        String rfqId = (String) poRequest.get("rfqId");
        String poRefId = (String) poRequest.get("poRefId");
        String orderDate = (String) poRequest.get("orderDate");
        String expectedDelivery = (String) poRequest.get("expectedDelivery");
        String paymentTerms = (String) poRequest.get("paymentTerms");
        String shippingAddress = (String) poRequest.get("shippingAddress");
        String notes = (String) poRequest.get("notes");
        String status = (String) poRequest.get("status");
        
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
            vendorId,
            vendorName,
            vendorContact,
            groupName,
            subGroupName,
            projectId,
            rfqId,
            poRefId,
            orderDate,
            expectedDelivery,
            paymentTerms != null ? paymentTerms : "",
            shippingAddress != null ? shippingAddress : "",
            notes != null ? notes : "",
            status,
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
        response.put("id", po.getId());
        
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
    /**
 * POST /api/purchase-orders
 * Create PO from order books or manually (supports new vendors)
 */
@PostMapping
public ResponseEntity<?> createPurchaseOrder(
        @RequestBody Map<String, Object> poRequest,
        HttpServletRequest request
) {
    try {
        Long userId = getUserIdFromRequest(request);
        
        // Extract vendor info
        Long vendorId = poRequest.get("vendorId") != null ? 
                Long.parseLong(poRequest.get("vendorId").toString()) : null;
        String vendorName = (String) poRequest.get("vendorName");
        String vendorContact = (String) poRequest.get("vendorContact");
        
        // Validate vendor
        if (vendorId == null && (vendorName == null || vendorName.trim().isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Either vendorId or vendorName must be provided"));
        }
        
        // Validate contact for new vendors
        if (vendorId == null && vendorContact != null && vendorContact.length() != 10) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Vendor contact must be exactly 10 digits"));
        }
        
        // Extract PO data
        String groupName = (String) poRequest.get("groupName");
        String subGroupName = (String) poRequest.get("subGroupName");
        String projectId = (String) poRequest.get("projectId");
        String orderDate = (String) poRequest.get("orderDate");
        String expectedDelivery = (String) poRequest.get("expectedDelivery");
        String paymentTerms = (String) poRequest.get("paymentTerms");
        String shippingAddress = (String) poRequest.get("shippingAddress");
        String notes = (String) poRequest.get("notes");
        String status = (String) poRequest.get("status");
        String poRefId = (String) poRequest.get("poRefId");

        // Validate
        if (groupName == null || groupName.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Group name is required"));
        }

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

        // Create PO
        PurchaseOrderEntity po = purchaseOrderService.createPOFromOrderBooks(
            userId,
            vendorId,
            vendorName,
            vendorContact,
            groupName,
            subGroupName,
            projectId,
            poRefId,
            orderDate,
            expectedDelivery,
            paymentTerms,
            shippingAddress,
            notes,
            status,
            itemsData
        );
        
        // Upsert manual items into catalogue for future autocomplete
        upsertItemsToCatalogue(itemsData);

        // Return success
        Map<String, Object> response = createSuccessResponse(
            "Purchase Order created successfully", 
            null
        );
        response.put("poNo", po.getPoNo());
        response.put("id", po.getId());
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
        
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
        @RequestBody Map<String, Object> poRequest,
        HttpServletRequest request
) {
    try {
        Long userId = getUserIdFromRequest(request);
        
        log.info("Received update request for PO {}", id);
        
        // Extract vendor info
        Long vendorId = poRequest.get("vendorId") != null ? 
                Long.parseLong(poRequest.get("vendorId").toString()) : null;
        String vendorName = (String) poRequest.get("vendorName");
        String vendorContact = (String) poRequest.get("vendorContact");
        
        // Validate vendor
        if (vendorId == null && (vendorName == null || vendorName.trim().isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Either vendorId or vendorName must be provided"));
        }
        
        // Extract PO data
        String groupName = (String) poRequest.get("groupName");
        String subGroupName = (String) poRequest.get("subGroupName");
        String projectId = (String) poRequest.get("projectId");
        String orderDate = (String) poRequest.get("orderDate");
        String expectedDelivery = (String) poRequest.get("expectedDelivery");
        String paymentTerms = (String) poRequest.get("paymentTerms");
        String shippingAddress = (String) poRequest.get("shippingAddress");
        String notes = (String) poRequest.get("notes");
        String status = (String) poRequest.get("status");
        String poRefId = (String) poRequest.get("poRefId");
        
        // Validate required fields
        if (groupName == null || groupName.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Group name is required"));
        }
        
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
        
        // Validate all items have required fields
        for (Map<String, Object> item : itemsData) {
            if (!item.containsKey("itemName") || !item.containsKey("quantity") || 
                !item.containsKey("unitPrice") || !item.containsKey("gst")) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("All items must have itemName, quantity, unitPrice, and gst"));
            }
        }
        
        // Update PO
        PurchaseOrderEntity po = purchaseOrderService.updatePurchaseOrderWithItems(
            id,
            userId,
            vendorId,
            vendorName,
            vendorContact,
            groupName,
            subGroupName,
            projectId,
            poRefId,
            orderDate,
            expectedDelivery,
            paymentTerms,
            shippingAddress,
            notes,
            status,
            itemsData
        );
        
        // Upsert manual items into catalogue for future autocomplete
        upsertItemsToCatalogue(itemsData);

        // Return success
        Map<String, Object> response = createSuccessResponse(
            "Purchase Order updated successfully", 
            null
        );
        response.put("poNo", po.getPoNo());
        response.put("id", po.getId());
        response.put("actualStatus", po.getStatus());

        // Warn if the requested status was ignored (e.g. tried to downgrade a
        // fully-delivered PO where all items are delivered)
        if (status != null && !status.isEmpty() && !status.equals(po.getStatus())) {
            response.put("statusWarning",
                "Status could not be changed to \"" + status + "\" because all items " +
                "in this PO are fully delivered. The status remains \"" + po.getStatus() + "\".");
        }
        
        return ResponseEntity.ok(response);
        
    } catch (RuntimeException e) {
        log.error("Business logic error updating purchase order: {}", id, e);
        return ResponseEntity.badRequest()
                .body(createErrorResponse(e.getMessage()));
    } catch (Exception e) {
        log.error("Error updating purchase order: {}", id, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Failed to update purchase order: " + e.getMessage()));
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
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid status update for PO {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
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
 // ============================================
 // ADD TO PurchaseOrderController.java
 // ============================================
    /**
     * GET /api/purchase-orders/{id}/items-for-bill
     * Get PO items with pending delivery quantities
     */
    @GetMapping("/{id}/items-for-bill")
    public ResponseEntity<?> getPurchaseOrderItemsForBill(@PathVariable Long id) {
        try {
            PurchaseOrderEntity po = purchaseOrderService.getPurchaseOrderById(id);

            // Always return ALL items — frontend needs them for both create and edit.
            // maxBillableQty = pendingQty, so fully-delivered items show 0 and are locked.
            List<PurchaseOrderItemEntity> items = purchaseOrderItemRepository
                    .findByPurchaseOrderId(id);

            // Convert to response format
            List<Map<String, Object>> itemsList = items.stream()
                .map(item -> {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("lineNo", item.getLineNo());
                    itemMap.put("itemSku", item.getItemSku());
                    itemMap.put("itemName", item.getItemName());
                    itemMap.put("description", item.getDescription());
                    itemMap.put("orderedQty", item.getQuantity());
                    itemMap.put("deliveredQty", item.getDeliveredQty());
                    itemMap.put("pendingQty", item.getPendingQty());
                    itemMap.put("maxBillableQty", item.getPendingQty()); // 0 for fully delivered
                    itemMap.put("unitPrice", item.getUnitPrice());
                    itemMap.put("taxPercent", item.getTaxPercent());
                    itemMap.put("deliveryStatus", getDeliveryStatus(item));
                    return itemMap;
                })
                .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("poNo", po.getPoNo());
            response.put("vendorName", po.getVendorDisplayName());
            response.put("items", itemsList);
            response.put("totalItems", itemsList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching PO items for bill: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "success", false));
        }
    }

    private String getDeliveryStatus(PurchaseOrderItemEntity item) {
        if (item.getDeliveredQty().compareTo(BigDecimal.ZERO) == 0) {
            return "Not Delivered";
        } else if (item.getPendingQty().compareTo(BigDecimal.ZERO) == 0) {
            return "Fully Delivered";
        } else {
            return "Partially Delivered";
        }
    }
 /**
  * GET /api/purchase-orders/by-vendor
  * Get POs by vendor (supports both vendorId and vendorName)
  * Filtered by project
  */
 @GetMapping("/by-vendor")
 public ResponseEntity<?> getPurchaseOrdersByVendor(
         @RequestParam(required = false) Long vendorId,
         @RequestParam(required = false) String vendorName,
         @RequestParam(required = false) String projectId,
         @RequestParam(required = false) String groupName,
         @RequestParam(required = false) String subGroupName,
         HttpServletRequest request
 ) {
     try {
         log.info("Fetching POs - vendorId: {}, vendorName: {}, projectId: {}", 
                  vendorId, vendorName, projectId);
         
         List<Map<String, Object>> pos = purchaseOrderService.getPurchaseOrdersByVendor1(
             vendorId, vendorName, groupName, subGroupName, projectId
         );
         
         return ResponseEntity.ok(pos);
         
     } catch (Exception e) {
         log.error("Error fetching POs by vendor", e);
         return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                 .body(Map.of("error", e.getMessage()));
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
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String orderDateFrom,
            @RequestParam(required = false) String orderDateTo,
            HttpServletRequest request
    ) {
        try {
            Long userId = getUserIdFromRequest(request);
            String userRole = getUserRoleFromRequest(request);
            
            PurchaseOrderService.POStats stats = purchaseOrderService.getStatistics(
                    groupName, subGroupName, projectId, status, paymentStatus, searchTerm,
                    orderDateFrom, orderDateTo, userId, userRole
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

    /**
     * Extracts manual items from a PO request body and upserts them into the
     * item catalogue so they appear in future autocomplete searches.
     * Manual items are those without an "orderBookItemId" (not linked to an order book).
     * Fire-and-forget — failures are logged but never block the PO response.
     */
    private void upsertItemsToCatalogue(List<Map<String, Object>> itemsData) {
        if (itemsData == null || itemsData.isEmpty()) return;
        try {
            List<OrderBookItemRequestWrapper> catalogueItems = itemsData.stream()
                .filter(i -> {
                    Object name = i.get("itemName");
                    // Only process items that have a name and are not linked to an order book
                    return name != null
                        && !name.toString().isBlank()
                        && i.get("orderBookItemId") == null;
                })
                .map(i -> {
                    OrderBookItemRequestWrapper w = new OrderBookItemRequestWrapper();
                    w.setItemName(i.get("itemName").toString().trim());
                    if (i.get("itemDescription") != null)
                        w.setDescription(i.get("itemDescription").toString());
                    if (i.get("unitPrice") != null)
                        w.setUnitPrice(new BigDecimal(i.get("unitPrice").toString()));
                    if (i.get("gst") != null)
                        w.setTaxPercent(new BigDecimal(i.get("gst").toString()));
                    if (i.get("discount") != null)
                        w.setDiscountPercent(new BigDecimal(i.get("discount").toString()));
                    return w;
                })
                .collect(Collectors.toList());

            if (!catalogueItems.isEmpty()) {
                catalogueService.upsertFromItems(catalogueItems);
                log.info("Upserted {} manual item(s) to catalogue", catalogueItems.size());
            }
        } catch (Exception ex) {
            log.warn("Non-fatal: failed to upsert items to catalogue: {}", ex.getMessage());
        }
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

    // =========================================================================
    // PO SOFT-COPY FILE UPLOAD / VIEW / DOWNLOAD  (max 10 MB)
    // =========================================================================

    /** List available stamp file names (default + uploaded) for the picker. */
    @GetMapping("/stamps")
    public ResponseEntity<?> listStamps() {
        try {
            return ResponseEntity.ok(Map.of("stamps", purchaseOrderPdfService.listStamps()));
        } catch (Exception e) {
            log.error("Error listing stamps", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Could not list stamps"));
        }
    }

    /** Upload a new stamp image with a chosen name; returns the stored file name. */
    @PostMapping("/stamps/upload")
    public ResponseEntity<?> uploadStamp(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name
    ) {
        try {
            if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            String ct = file.getContentType();
            if (ct == null || !ct.startsWith("image/")) return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
            if (file.getSize() > 3L * 1024 * 1024) return ResponseEntity.badRequest().body(Map.of("error", "Stamp must be under 3 MB"));
            String stored = purchaseOrderPdfService.saveStamp(name, file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("message", "Stamp uploaded", "stampFileName", stored));
        } catch (Exception e) {
            log.error("Error uploading stamp", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Stamp upload failed"));
        }
    }

    /** Generate the SESOLA PO PDF from supplied data and store it as the PO file. */
    @PostMapping("/{id}/generate-document")
    public ResponseEntity<?> generatePODocument(
            @PathVariable Long id,
            @RequestBody com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest req
    ) {
        try {
            String filePath = purchaseOrderService.generateAndStorePoDocument(id, req);
            return ResponseEntity.ok(Map.of(
                    "message", "PO document generated successfully",
                    "filePath", filePath));
        } catch (RuntimeException e) {
            log.error("Error generating PO document for {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error generating PO document for {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PO generation failed"));
        }
    }

    @PostMapping("/{id}/upload-file")
    public ResponseEntity<?> uploadPOFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            String filePath = purchaseOrderService.uploadPOFile(id, file);
            return ResponseEntity.ok(Map.of(
                    "message", "File uploaded successfully",
                    "filePath", filePath));
        } catch (RuntimeException e) {
            log.error("Error uploading file for PO {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error uploading file for PO {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File upload failed"));
        }
    }

    /** Opens the PO document inline in the browser (view) */
    @GetMapping("/{id}/view-file")
    public ResponseEntity<byte[]> viewPOFile(@PathVariable Long id) {
        try {
            PurchaseOrderEntity po = purchaseOrderService.getPurchaseOrderById(id);
            if (po.getPoFileName() == null && po.getPoFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = purchaseOrderService.getPOFileBytes(id);
            String contentType = purchaseOrderService.getPOFileContentType(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + po.getPoFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Error viewing file for PO {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Forces a browser download of the PO document */
    @GetMapping("/{id}/download-file")
    public ResponseEntity<byte[]> downloadPOFile(@PathVariable Long id) {
        try {
            PurchaseOrderEntity po = purchaseOrderService.getPurchaseOrderById(id);
            if (po.getPoFileName() == null && po.getPoFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = purchaseOrderService.getPOFileBytes(id);
            String contentType = purchaseOrderService.getPOFileContentType(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + po.getPoFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Error downloading file for PO {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /purchase-orders/project/{projectId}/all-items
     *
     * Returns all line items across every non-cancelled PO under this project,
     * enriched with poNo, vendorName, quantity, deliveredQty, pendingQty, unitPrice.
     * Used by the Inventory INWARD transaction modal flat item picker.
     */
    @GetMapping("/project/{projectId}/all-items")
    public ResponseEntity<?> getAllItemsByProject(@PathVariable String projectId) {
        try {
            List<PurchaseOrderItemEntity> items =
                purchaseOrderItemRepository.findAllItemsByProjectId(projectId);

            List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
            for (PurchaseOrderItemEntity item : items) {
                PurchaseOrderEntity po = item.getPurchaseOrder();

                java.math.BigDecimal ordered  = item.getQuantity()    != null ? item.getQuantity()    : java.math.BigDecimal.ZERO;
                java.math.BigDecimal received = item.getDeliveredQty() != null ? item.getDeliveredQty() : java.math.BigDecimal.ZERO;
                java.math.BigDecimal pending  = item.getPendingQty()   != null ? item.getPendingQty()   : ordered.subtract(received);
                if (pending.compareTo(java.math.BigDecimal.ZERO) < 0) pending = java.math.BigDecimal.ZERO;

                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id",          item.getId());
                row.put("poId",        po != null ? po.getId()         : null);
                row.put("poNo",        po != null ? po.getPoNo()       : null);
                row.put("vendorName",  po != null ? po.getVendorName() : null);
                row.put("lineNo",      item.getLineNo());
                row.put("itemCode",    item.getItemSku());
                row.put("itemName",    item.getItemName());
                row.put("description", item.getDescription());
                row.put("unit",        null);   // purchase_order_items has no unit column; leave null
                row.put("orderedQty",  ordered);
                row.put("receivedQty", received);
                row.put("pendingQty",  pending);
                row.put("unitPrice",   item.getUnitPrice()  != null ? item.getUnitPrice()  : java.math.BigDecimal.ZERO);
                row.put("taxPercent",  item.getTaxPercent() != null ? item.getTaxPercent() : java.math.BigDecimal.ZERO);
                result.add(row);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get PO items for project {}", projectId, e);
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("message", "Failed to load project PO items: " + e.getMessage()));
        }
    }
}