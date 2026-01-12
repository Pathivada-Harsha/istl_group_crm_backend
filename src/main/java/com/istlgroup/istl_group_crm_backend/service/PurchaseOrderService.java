package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderService {
    
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final VendorRepository vendorRepository;
    
    /**
     * Get purchase orders with role-based and project-based filtering
     */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderEntity> getPurchaseOrders(
            String groupName,
            String subGroupName,
            String projectId,
            String status,
            String searchTerm,
            Long userId,
            String userRole,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        Pageable pageable = PageRequest.of(
                page, 
                size, 
                Sort.by(Sort.Direction.fromString(sortDirection), sortBy)
        );
        
        boolean isAdmin = isAdmin(userRole);
        
        // Search takes priority
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            return purchaseOrderRepository.search(searchTerm, pageable);
        }
        
        // Project-based filtering
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                return purchaseOrderRepository.findByProjectId(projectId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupAndSubGroup(groupName, subGroupName, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupName(groupName, pageable);
            }
            return purchaseOrderRepository.findAllActive(pageable);
        } else {
            if (projectId != null && !projectId.isEmpty()) {
                return purchaseOrderRepository.findByProjectIdAndUserAccess(projectId, userId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupSubGroupAndUserAccess(groupName, subGroupName, userId, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupNameAndUserAccess(groupName, userId, pageable);
            }
            return purchaseOrderRepository.findByUserAccess(userId, pageable);
        }
    }
    
    /**
     * Get PO by ID
     */
    @Transactional(readOnly = true)
    public PurchaseOrderEntity getPurchaseOrderById(Long id) {
        return purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Purchase Order not found with id: " + id));
    }
    
    /**
     * Create PO from quotation with custom data from frontend
     * KEY FIX: Auto-creates vendor if vendor_id is missing
     */
    @Transactional
    public PurchaseOrderEntity createPOFromQuotationWithCustomData(
            Long quotationId,
            Long userId,
            String orderDateStr,
            String expectedDeliveryStr,
            String paymentTerms,
            String shippingAddress,
            String notes,
            List<Map<String, Object>> itemsData
    ) {
        try {
            log.info("Creating PO from quotation {} with custom data", quotationId);
            
            // Get quotation
            QuotationEntity quotation = quotationRepository.findById(quotationId)
                    .orElseThrow(() -> new RuntimeException("Quotation not found"));
            
            if (!"Approved".equals(quotation.getStatus())) {
                throw new RuntimeException("Only approved quotations can be converted to PO");
            }
            
            // ===== KEY FIX: Ensure vendor exists =====
            Long vendorId = quotation.getVendorId();
            if (vendorId == null) {
                // Auto-create vendor with quotation data
                log.info("No vendor_id in quotation, creating new vendor");
                vendorId = createVendorFromQuotation(quotation, userId);
                
                // Update quotation with new vendor_id
                quotation.setVendorId(vendorId);
                quotationRepository.save(quotation);
            } else {
                // Ensure vendor exists in database
                ensureVendorExistsById(vendorId, quotation, userId);
            }
            
            // Parse dates
            LocalDate orderDate = LocalDate.parse(orderDateStr);
            LocalDate expectedDelivery = LocalDate.parse(expectedDeliveryStr);
            
            // Create PO first WITHOUT items
            PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                    .poNo(generatePONumber())
                    .vendorId(vendorId)  // Now guaranteed to be non-null
                    .quotationId(quotationId)
                    .rfqId(quotation.getRfqId())
                    .orderDate(orderDate.atStartOfDay())
                    .expectedDelivery(expectedDelivery.atStartOfDay())
                    .status("Draft")
                    .paymentStatus("Pending")
                    .groupName(quotation.getGroupName())
                    .subGroupName(quotation.getSubGroupName())
                    .projectId(quotation.getProjectId())
                    .deliveryAddress(shippingAddress)
                    .paymentTerms(paymentTerms)
                    .notes(notes)
                    .category(quotation.getCategory())
                    .createdBy(userId)
                    .totalItemsOrdered(0)
                    .totalItemsDelivered(0)
                    .build();
            
            // Save PO FIRST to get ID
            PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
            log.info("Saved PO with ID: {} and vendor_id: {}", savedPO.getId(), savedPO.getVendorId());
            
            // Create PO items from custom data
            BigDecimal totalValue = BigDecimal.ZERO;
            int totalItemsOrdered = 0;
            List<PurchaseOrderItemEntity> poItems = new ArrayList<>();
            
            for (int i = 0; i < itemsData.size(); i++) {
                Map<String, Object> itemData = itemsData.get(i);
                
                BigDecimal quantity = new BigDecimal(itemData.get("quantity").toString());
                BigDecimal unitPrice = new BigDecimal(itemData.get("unitPrice").toString());
                BigDecimal gst = new BigDecimal(itemData.get("gst").toString());
                BigDecimal discount = itemData.containsKey("discount") 
                    ? new BigDecimal(itemData.get("discount").toString()) 
                    : BigDecimal.ZERO;
                
                // Calculate line total with GST
                BigDecimal lineSubtotal = quantity.multiply(unitPrice);
                BigDecimal discountAmount = lineSubtotal.multiply(discount)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal taxableAmount = lineSubtotal.subtract(discountAmount);
                BigDecimal gstAmount = taxableAmount.multiply(gst)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = taxableAmount.add(gstAmount);
                
                PurchaseOrderItemEntity poItem = PurchaseOrderItemEntity.builder()
                        .purchaseOrder(savedPO)
                        .lineNo(i + 1)
                        .itemName(itemData.get("itemName").toString())
                        .description(itemData.containsKey("itemDescription") 
                            ? itemData.get("itemDescription").toString() 
                            : "")
                        .quantity(quantity)
                        .deliveredQty(BigDecimal.ZERO)
                        .unitPrice(unitPrice)
                        .taxPercent(gst)
                        .build();
                
                poItems.add(poItem);
                
                totalValue = totalValue.add(lineTotal);
                totalItemsOrdered += quantity.intValue();
            }
            
            // Save all items
            List<PurchaseOrderItemEntity> savedItems = purchaseOrderItemRepository.saveAll(poItems);
            log.info("Saved {} PO items", savedItems.size());
            
            // Update PO totals
            savedPO.setTotalValue(totalValue.setScale(2, RoundingMode.HALF_UP));
            savedPO.setTotalItemsOrdered(totalItemsOrdered);
            savedPO.setTotalItemsDelivered(0);
            savedPO.setItems(savedItems);
            purchaseOrderRepository.save(savedPO);
            
            log.info("Created PO {} from quotation {} with {} items, total: {}", 
                savedPO.getPoNo(), quotation.getQuoteNo(), itemsData.size(), totalValue);
            
            return savedPO;
            
        } catch (Exception e) {
            log.error("Error creating PO from quotation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create PO: " + e.getMessage());
        }
    }
    
    /**
     * Create vendor from quotation data
     * Uses database auto-generated ID to avoid JPA entity state issues
     */
    private Long createVendorFromQuotation(QuotationEntity quotation, Long userId) {
        try {
            String vendorName = quotation.getVendorContact() != null 
                ? "Vendor-" + quotation.getVendorContact().substring(0, Math.min(10, quotation.getVendorContact().length()))
                : "Auto-Vendor-" + System.currentTimeMillis();
            
            VendorEntity vendor = VendorEntity.builder()
                    // Don't set ID - let database auto-generate it
                    .name(vendorName)
                    .email("vendor_" + System.currentTimeMillis() + "@temp.com")
                    .phone(quotation.getVendorContact())
                    .rating(quotation.getVendorRating() != null ? quotation.getVendorRating().intValue() : 0)
                    .status("Active")
                    .groupName(quotation.getGroupName())
                    .subGroupName(quotation.getSubGroupName())
                    .projectId(quotation.getProjectId())
                    .category(quotation.getCategory())
                    .totalOrders(0)
                    .totalPurchaseValue(BigDecimal.ZERO)
                    .createdBy(userId)
                    .build();
            
            VendorEntity savedVendor = vendorRepository.save(vendor);
            log.info("Created new vendor {} with auto-generated ID: {}", vendorName, savedVendor.getId());
            
            return savedVendor.getId();
            
        } catch (Exception e) {
            log.error("Error creating vendor: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create vendor: " + e.getMessage());
        }
    }
    
    /**
     * Ensure vendor exists by ID, create if not
     * Fixed to use auto-generated ID instead of manual assignment
     */
    private void ensureVendorExistsById(Long vendorId, QuotationEntity quotation, Long userId) {
        if (!vendorRepository.existsById(vendorId)) {
            log.info("Vendor {} does not exist, creating new vendor with auto-generated ID...", vendorId);
            
            // Don't use the provided vendorId - let database generate it
            String vendorName = quotation.getVendorContact() != null 
                ? "Vendor-" + quotation.getVendorContact()
                : "Auto-Vendor-" + System.currentTimeMillis();
            
            VendorEntity vendor = VendorEntity.builder()
                    // Don't set ID - let database auto-generate
                    .name(vendorName)
                    .email("vendor_" + System.currentTimeMillis() + "@temp.com")
                    .phone(quotation.getVendorContact())
                    .rating(quotation.getVendorRating() != null ? quotation.getVendorRating().intValue() : 0)
                    .status("Active")
                    .groupName(quotation.getGroupName())
                    .subGroupName(quotation.getSubGroupName())
                    .projectId(quotation.getProjectId())
                    .category(quotation.getCategory())
                    .totalOrders(0)
                    .totalPurchaseValue(BigDecimal.ZERO)
                    .createdBy(userId)
                    .build();
            
            VendorEntity savedVendor = vendorRepository.save(vendor);
            log.info("Created vendor with auto-generated ID: {} (original requested: {})", savedVendor.getId(), vendorId);
            
            // Update quotation with the actual vendor ID
            quotation.setVendorId(savedVendor.getId());
            quotationRepository.save(quotation);
        }
    }
    
    /**
     * Update PO status
     */
    @Transactional
    public PurchaseOrderEntity updateStatus(Long id, String newStatus, Long userId) {
        PurchaseOrderEntity po = getPurchaseOrderById(id);
        
        String oldStatus = po.getStatus();
        po.setStatus(newStatus);
        
        if ("Approved".equals(newStatus)) {
            po.setApprovedBy(userId);
        }
        
        if ("Delivered".equals(newStatus) && !"Delivered".equals(oldStatus)) {
            updateVendorAfterDelivery(po);
        }
        
        log.info("Updated PO {} status from {} to {}", po.getPoNo(), oldStatus, newStatus);
        return purchaseOrderRepository.save(po);
    }
    
    /**
     * Soft delete PO
     */
    @Transactional
    public void deletePurchaseOrder(Long id) {
        PurchaseOrderEntity po = getPurchaseOrderById(id);
        po.setDeletedAt(LocalDateTime.now());
        po.setStatus("Cancelled");
        purchaseOrderRepository.save(po);
        
        log.info("Soft deleted PO: {}", po.getPoNo());
    }
    
    /**
     * Get POs by vendor
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderEntity> getPurchaseOrdersByVendor(Long vendorId) {
        return purchaseOrderRepository.findByVendorIdOrderByOrderDateDesc(vendorId);
    }
    
    /**
     * Mark item as delivered
     */
    @Transactional
    public PurchaseOrderEntity markItemDelivered(Long poId, Long itemId, BigDecimal deliveredQty) {
        PurchaseOrderItemEntity item = purchaseOrderItemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("PO Item not found"));
        
        if (!item.getPurchaseOrder().getId().equals(poId)) {
            throw new RuntimeException("Item does not belong to this PO");
        }
        
        // Update delivered quantity
        BigDecimal newDelivered = item.getDeliveredQty().add(deliveredQty);
        if (newDelivered.compareTo(item.getQuantity()) > 0) {
            throw new RuntimeException("Delivered quantity exceeds ordered quantity");
        }
        
        item.setDeliveredQty(newDelivered);
        purchaseOrderItemRepository.save(item);
        
        // Recalculate PO totals
        PurchaseOrderEntity po = getPurchaseOrderById(poId);
        recalculateTotals(po);
        
        // If all items delivered, change status
        if (po.getTotalItemsPending() != null && po.getTotalItemsPending() == 0) {
            po.setStatus("Delivered");
            updateVendorAfterDelivery(po);
        }
        
        return purchaseOrderRepository.save(po);
    }
    
    /**
     * Update PO
     */
    @Transactional
    public PurchaseOrderEntity updatePurchaseOrder(Long id, PurchaseOrderEntity updatedPO) {
        PurchaseOrderEntity existing = getPurchaseOrderById(id);
        
        // Update fields
        existing.setExpectedDelivery(updatedPO.getExpectedDelivery());
        existing.setDeliveryAddress(updatedPO.getDeliveryAddress());
        existing.setDeliveryTerms(updatedPO.getDeliveryTerms());
        existing.setPaymentTerms(updatedPO.getPaymentTerms());
        existing.setTrackingNumber(updatedPO.getTrackingNumber());
        existing.setNotes(updatedPO.getNotes());
        
        return purchaseOrderRepository.save(existing);
    }
    
    /**
     * Create PO manually (without quotation)
     */
    @Transactional
    public PurchaseOrderEntity createPurchaseOrder(PurchaseOrderEntity po, Long userId) {
        po.setPoNo(generatePONumber());
        po.setCreatedBy(userId);
        po.setOrderDate(LocalDateTime.now());
        po.setStatus("Draft");
        po.setPaymentStatus("Pending");
        
        // Calculate totals from items
        if (po.getItems() != null && !po.getItems().isEmpty()) {
            int totalOrdered = po.getItems().stream()
                    .mapToInt(item -> item.getQuantity().intValue())
                    .sum();
            po.setTotalItemsOrdered(totalOrdered);
            po.setTotalItemsDelivered(0);
            
            BigDecimal totalValue = po.getItems().stream()
                    .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            po.setTotalValue(totalValue);
        }
        
        PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
        
        // Save items
        if (po.getItems() != null) {
            for (int i = 0; i < po.getItems().size(); i++) {
                PurchaseOrderItemEntity item = po.getItems().get(i);
                item.setPurchaseOrder(savedPO);
                item.setLineNo(i + 1);
            }
            purchaseOrderItemRepository.saveAll(po.getItems());
        }
        
        log.info("Created manual PO: {}", savedPO.getPoNo());
        return savedPO;
    }
    
    /**
     * Create PO from approved quotation
     */
    @Transactional
    public PurchaseOrderEntity createFromQuotation(Long quotationId, Long userId) {
        QuotationEntity quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new RuntimeException("Quotation not found"));
        
        if (!"Approved".equals(quotation.getStatus())) {
            throw new RuntimeException("Only approved quotations can be converted to PO");
        }
        
        List<QuotationItemEntity> quotationItems = quotationItemRepository.findByQuotationId(quotationId);
        
        // Ensure vendor exists
        Long vendorId = quotation.getVendorId();
        if (vendorId == null) {
            vendorId = createVendorFromQuotation(quotation, userId);
            quotation.setVendorId(vendorId);
            quotationRepository.save(quotation);
        } else {
            ensureVendorExistsById(vendorId, quotation, userId);
        }
        
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .poNo(generatePONumber())
                .vendorId(vendorId)
                .quotationId(quotationId)
                .rfqId(quotation.getRfqId())
                .orderDate(LocalDateTime.now())
                .expectedDelivery(quotation.getValidTill().atStartOfDay())
                .status("Draft")
                .paymentStatus("Pending")
                .totalValue(quotation.getTotalValue())
                .totalItemsOrdered(quotationItems.stream()
                        .mapToInt(item -> item.getQuantity().intValue())
                        .sum())
                .totalItemsDelivered(0)
                .groupName(quotation.getGroupName())
                .subGroupName(quotation.getSubGroupName())
                .projectId(quotation.getProjectId())
                .deliveryTerms(quotation.getDeliveryTime())
                .paymentTerms(quotation.getPaymentTerms())
                .notes(quotation.getNotes())
                .category(quotation.getCategory())
                .createdBy(userId)
                .build();
        
        PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
        
        for (int i = 0; i < quotationItems.size(); i++) {
            QuotationItemEntity qItem = quotationItems.get(i);
            
            PurchaseOrderItemEntity poItem = PurchaseOrderItemEntity.builder()
                    .purchaseOrder(savedPO)
                    .lineNo(i + 1)
                    .itemName(qItem.getItemName())
                    .description(qItem.getDescription())
                    .quantity(qItem.getQuantity())
                    .deliveredQty(BigDecimal.ZERO)
                    .unitPrice(qItem.getUnitPrice())
                    .taxPercent(qItem.getTaxPercent())
                    .deliverySchedule(qItem.getDeliveryLeadTime())
                    .build();
            
            purchaseOrderItemRepository.save(poItem);
        }
        
        log.info("Created PO {} from quotation {}", savedPO.getPoNo(), quotation.getQuoteNo());
        return savedPO;
    }
    
    
    /**
     * Recalculate PO totals from items
     */
    private void recalculateTotals(PurchaseOrderEntity po) {
        Integer totalOrdered = purchaseOrderItemRepository.getTotalOrderedItems(po.getId());
        Integer totalDelivered = purchaseOrderItemRepository.getTotalDeliveredItems(po.getId());
        
        po.setTotalItemsOrdered(totalOrdered != null ? totalOrdered : 0);
        po.setTotalItemsDelivered(totalDelivered != null ? totalDelivered : 0);
    }
    
    /**
     * Get PO statistics
     */
    @Transactional(readOnly = true)
    public POStats getStatistics(String groupName, String subGroupName, String projectId, Long userId, String userRole) {
        boolean isAdmin = isAdmin(userRole);
        
        Page<PurchaseOrderEntity> allPOs = getPurchaseOrders(
                groupName, subGroupName, projectId, null, null,
                userId, userRole, 0, Integer.MAX_VALUE, "orderDate", "DESC"
        );
        
        List<PurchaseOrderEntity> pos = allPOs.getContent();
        
        long total = pos.size();
        long draft = pos.stream().filter(p -> "Draft".equals(p.getStatus())).count();
        long approved = pos.stream().filter(p -> "Approved".equals(p.getStatus())).count();
        long ordered = pos.stream().filter(p -> "Ordered".equals(p.getStatus())).count();
        long inTransit = pos.stream().filter(p -> "In-Transit".equals(p.getStatus())).count();
        long delivered = pos.stream().filter(p -> "Delivered".equals(p.getStatus())).count();
        long cancelled = pos.stream().filter(p -> "Cancelled".equals(p.getStatus())).count();
        
        Double totalValue = pos.stream()
                .map(PurchaseOrderEntity::getTotalValue)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        
        return POStats.builder()
                .totalPOs(total)
                .draft(draft)
                .approved(approved)
                .ordered(ordered)
                .inTransit(inTransit)
                .delivered(delivered)
                .cancelled(cancelled)
                .totalValue(totalValue)
                .build();
    }
    
    // Helper methods
    
    private boolean isAdmin(String userRole) {
        return "ADMIN".equalsIgnoreCase(userRole) || "SUPERADMIN".equalsIgnoreCase(userRole);
    }
    
    private String generatePONumber() {
        long count = purchaseOrderRepository.countActivePOs();
        return String.format("PO-2024-%03d", count + 1);
    }
    
    private void updateVendorAfterDelivery(PurchaseOrderEntity po) {
        vendorRepository.findById(po.getVendorId()).ifPresent(vendor -> {
            vendor.setLastPurchaseAmount(po.getTotalValue());
            vendor.setLastPurchaseDate(LocalDateTime.now());
            
            BigDecimal currentTotal = vendor.getTotalPurchaseValue() != null 
                    ? vendor.getTotalPurchaseValue() 
                    : BigDecimal.ZERO;
            vendor.setTotalPurchaseValue(currentTotal.add(po.getTotalValue()));
            
            Integer currentOrders = vendor.getTotalOrders() != null ? vendor.getTotalOrders() : 0;
            vendor.setTotalOrders(currentOrders + 1);
            
            vendorRepository.save(vendor);
            log.info("Updated vendor {} stats after delivery", vendor.getName());
        });
    }
    
    @lombok.Data
    @lombok.Builder
    public static class POStats {
        private long totalPOs;
        private long draft;
        private long approved;
        private long ordered;
        private long inTransit;
        private long delivered;
        private long cancelled;
        private Double totalValue;
    }
}