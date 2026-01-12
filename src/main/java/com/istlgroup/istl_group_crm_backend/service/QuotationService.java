package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.entity.QuotationItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.QuotationRepository;
import com.istlgroup.istl_group_crm_backend.repo.QuotationItemRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuotationService {
    
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    
    /**
     * Get quotations with role-based and project-based filtering
     */
    @Transactional(readOnly = true)
    public Page<QuotationEntity> getQuotations(
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
            return quotationRepository.searchProcurement(searchTerm, pageable);
        }
        
        // Project-based filtering
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                return quotationRepository.findByProjectId(projectId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return quotationRepository.findByGroupAndSubGroup(groupName, subGroupName, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return quotationRepository.findByGroupName(groupName, pageable);
            }
            return quotationRepository.findAllProcurement(pageable);
        } else {
            if (projectId != null && !projectId.isEmpty()) {
                return quotationRepository.findByProjectIdAndUserAccess(projectId, userId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return quotationRepository.findByGroupSubGroupAndUserAccess(groupName, subGroupName, userId, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return quotationRepository.findByGroupNameAndUserAccess(groupName, userId, pageable);
            }
            return quotationRepository.findByUserAccess(userId, pageable);
        }
    }
    
    /**
     * Get quotation by ID
     */
    @Transactional(readOnly = true)
    public QuotationEntity getQuotationById(Long id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Quotation not found with id: " + id));
    }
    
    /**
     * Create new procurement quotation
     * FIXED: Proper handling of bidirectional relationship and cascade
     */
    @Transactional
    public QuotationEntity createQuotation(QuotationEntity quotation, Long userId) {
        try {
            log.info("Creating quotation for user: {}", userId);
            
            // Set metadata
            quotation.setPreparedBy(userId);
            quotation.setUploadedAt(LocalDateTime.now());
            
            // Generate quotation number if not provided
            if (quotation.getQuoteNo() == null || quotation.getQuoteNo().isEmpty()) {
                quotation.setQuoteNo(generateQuoteNumber());
            }
            
            // Set default status if not provided
            if (quotation.getStatus() == null || quotation.getStatus().isEmpty()) {
                quotation.setStatus("New");
            }
            
            // Set default type
            if (quotation.getType() == null || quotation.getType().isEmpty()) {
                quotation.setType("Procurement");
            }
            
            // Extract items BEFORE saving quotation (to avoid cascade issues)
            List<QuotationItemEntity> itemsToSave = new ArrayList<>();
            if (quotation.getItems() != null && !quotation.getItems().isEmpty()) {
                itemsToSave.addAll(quotation.getItems());
                log.info("Found {} items to save", itemsToSave.size());
            }
            
            // Clear items from quotation to prevent cascade save
            quotation.setItems(new ArrayList<>());
            
            // Calculate total value from items
            BigDecimal totalValue = BigDecimal.ZERO;
            if (!itemsToSave.isEmpty()) {
                for (QuotationItemEntity item : itemsToSave) {
                    // Ensure BigDecimal values are not null
                    BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
                    BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                    BigDecimal taxPercent = item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO;
                    
                    // Calculate line subtotal
                    BigDecimal lineSubtotal = quantity.multiply(unitPrice);
                    
                    // Calculate tax amount
                    BigDecimal taxAmount = lineSubtotal.multiply(taxPercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    
                    // Add to total
                    totalValue = totalValue.add(lineSubtotal).add(taxAmount);
                }
            }
            quotation.setTotalValue(totalValue.setScale(2, RoundingMode.HALF_UP));
            
            // Save quotation FIRST (without items)
            log.info("Saving quotation: {}", quotation.getQuoteNo());
            QuotationEntity savedQuotation = quotationRepository.save(quotation);
            log.info("Quotation saved with ID: {}", savedQuotation.getId());
            
            // Now save items with proper quotation_id reference
            if (!itemsToSave.isEmpty()) {
                log.info("Saving {} items for quotation ID: {}", itemsToSave.size(), savedQuotation.getId());
                
                for (int i = 0; i < itemsToSave.size(); i++) {
                    QuotationItemEntity item = itemsToSave.get(i);
                    
                    // Set quotation reference (this sets quotation_id)
                    item.setQuotation(savedQuotation);
                    item.setLineNo(i + 1);
                    item.setCreatedAt(LocalDateTime.now());
                    
                    // Ensure BigDecimal values have defaults
                    if (item.getQuantity() == null) {
                        item.setQuantity(BigDecimal.ONE);
                    }
                    if (item.getUnitPrice() == null) {
                        item.setUnitPrice(BigDecimal.ZERO);
                    }
                    if (item.getTaxPercent() == null) {
                        item.setTaxPercent(BigDecimal.valueOf(18));
                    }
                    
                    log.debug("Item {}: name={}, quotation_id={}", i+1, item.getItemName(), 
                             item.getQuotation() != null ? item.getQuotation().getId() : "NULL");
                }
                
                // Save all items
                List<QuotationItemEntity> savedItems = quotationItemRepository.saveAll(itemsToSave);
                log.info("Successfully saved {} items", savedItems.size());
                
                // Update the quotation with saved items
                savedQuotation.setItems(savedItems);
            }
            
            log.info("Quotation created successfully: {} (ID: {}) by user: {}", 
                    savedQuotation.getQuoteNo(), savedQuotation.getId(), userId);
            return savedQuotation;
            
        } catch (Exception e) {
            log.error("Error creating quotation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create quotation: " + e.getMessage());
        }
    }
    
    /**
     * Update quotation
     */
    @Transactional
    public QuotationEntity updateQuotation(Long id, QuotationEntity updatedQuotation) {
        QuotationEntity existing = getQuotationById(id);
        
        // Update fields
        existing.setVendorId(updatedQuotation.getVendorId());
        existing.setRfqId(updatedQuotation.getRfqId());
        existing.setValidTill(updatedQuotation.getValidTill());
        existing.setGroupName(updatedQuotation.getGroupName());
        existing.setSubGroupName(updatedQuotation.getSubGroupName());
        existing.setProjectId(updatedQuotation.getProjectId());
        existing.setVendorContact(updatedQuotation.getVendorContact());
        existing.setVendorRating(updatedQuotation.getVendorRating());
        existing.setDeliveryTime(updatedQuotation.getDeliveryTime());
        existing.setPaymentTerms(updatedQuotation.getPaymentTerms());
        existing.setWarranty(updatedQuotation.getWarranty());
        existing.setNotes(updatedQuotation.getNotes());
        existing.setCategory(updatedQuotation.getCategory());
        
        // Update items if provided
        if (updatedQuotation.getItems() != null) {
            // Delete old items
            quotationItemRepository.deleteByQuotationId(id);
            
            // Add new items
            for (int i = 0; i < updatedQuotation.getItems().size(); i++) {
                QuotationItemEntity item = updatedQuotation.getItems().get(i);
                item.setQuotation(existing);
                item.setLineNo(i + 1);
                item.setCreatedAt(LocalDateTime.now());
            }
            quotationItemRepository.saveAll(updatedQuotation.getItems());
            
            // Recalculate total
            BigDecimal totalValue = calculateTotalValue(updatedQuotation.getItems());
            existing.setTotalValue(totalValue);
        }
        
        return quotationRepository.save(existing);
    }
    
    /**
     * Update quotation status
     */
    @Transactional
    public QuotationEntity updateStatus(Long id, String newStatus) {
        QuotationEntity quotation = getQuotationById(id);
        quotation.setStatus(newStatus);
        
        log.info("Updated quotation {} status to: {}", quotation.getQuoteNo(), newStatus);
        return quotationRepository.save(quotation);
    }
    
    /**
     * Soft delete quotation
     */
    @Transactional
    public void deleteQuotation(Long id) {
        QuotationEntity quotation = getQuotationById(id);
        quotation.setDeletedAt(LocalDateTime.now());
        quotationRepository.save(quotation);
        
        log.info("Soft deleted quotation: {}", quotation.getQuoteNo());
    }
    
    /**
     * Get quotations by vendor
     */
    @Transactional(readOnly = true)
    public List<QuotationEntity> getQuotationsByVendor(Long vendorId) {
        return quotationRepository.findByVendorId(vendorId);
    }
    
    /**
     * Mark expired quotations
     */
    @Transactional
    public void markExpiredQuotations() {
        LocalDate today = LocalDate.now();
        List<QuotationEntity> expiredQuotations = quotationRepository.findExpiredQuotations(today);
        
        for (QuotationEntity quotation : expiredQuotations) {
            quotation.setStatus("Expired");
        }
        
        quotationRepository.saveAll(expiredQuotations);
        log.info("Marked {} quotations as expired", expiredQuotations.size());
    }
    
    /**
     * Get quotation statistics with project filtering
     */
    @Transactional(readOnly = true)
    public QuotationStats getStatistics(String groupName, String subGroupName, String projectId, Long userId, String userRole) {
        boolean isAdmin = isAdmin(userRole);
        
        // Get filtered quotations based on project and role
        Page<QuotationEntity> allQuotations = getQuotations(
                groupName, subGroupName, projectId, null, null,
                userId, userRole, 0, Integer.MAX_VALUE, "uploadedAt", "DESC"
        );
        
        List<QuotationEntity> quotations = allQuotations.getContent();
        
        // Calculate stats from filtered quotations
        long total = quotations.size();
        long newQuotations = quotations.stream().filter(q -> "New".equals(q.getStatus())).count();
        long shortlisted = quotations.stream().filter(q -> "Shortlisted".equals(q.getStatus())).count();
        long approved = quotations.stream().filter(q -> "Approved".equals(q.getStatus())).count();
        long rejected = quotations.stream().filter(q -> "Rejected".equals(q.getStatus())).count();
        long expired = quotations.stream().filter(q -> "Expired".equals(q.getStatus())).count();
        
        return QuotationStats.builder()
                .totalQuotations(total)
                .newQuotations(newQuotations)
                .shortlisted(shortlisted)
                .approved(approved)
                .rejected(rejected)
                .expired(expired)
                .build();
    }
    
    /**
     * Create Purchase Order from Quotation
     * Auto-creates vendor if doesn't exist
     */
    @Transactional
    public Map<String, Object> createPOFromQuotation(Long quotationId, Long userId) {
        // Get quotation
        QuotationEntity quotation = getQuotationById(quotationId);
        
        if (!"Approved".equals(quotation.getStatus())) {
            throw new RuntimeException("Only approved quotations can be converted to PO");
        }
        
        if (quotation.getPoId() != null) {
            throw new RuntimeException("PO already created for this quotation");
        }
        
        // TODO: Implement vendor creation/lookup and PO creation
        // This would call VendorService and PurchaseOrderService
        
        // For now, just update status
        quotation.setStatus("PO Created");
        quotationRepository.save(quotation);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "PO creation initiated");
        result.put("quotationId", quotationId);
        
        return result;
    }
    /**
     * Get all approved quotations that haven't had a PO created
     */
    @Transactional(readOnly = true)
    public List<QuotationEntity> getApprovedQuotations() {
        try {
            return quotationRepository.findByStatusAndPoIdIsNullAndDeletedAtIsNullOrderByUploadedAtDesc("Approved");
        } catch (Exception e) {
            log.error("Error fetching approved quotations: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch approved quotations");
        }
    }

    // Helper methods
    
    private boolean isAdmin(String userRole) {
        return "ADMIN".equalsIgnoreCase(userRole) || "SUPERADMIN".equalsIgnoreCase(userRole);
    }
    
    private String generateQuoteNumber() {
        long count = quotationRepository.countProcurementQuotations();
        return String.format("QUO-2024-%03d", count + 1);
    }
    
    private BigDecimal calculateTotalValue(List<QuotationItemEntity> items) {
        BigDecimal total = BigDecimal.ZERO;
        
        for (QuotationItemEntity item : items) {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal taxPercent = item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO;
            
            BigDecimal lineSubtotal = quantity.multiply(unitPrice);
            BigDecimal taxAmount = lineSubtotal.multiply(taxPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            
            total = total.add(lineSubtotal).add(taxAmount);
        }
        
        return total.setScale(2, RoundingMode.HALF_UP);
    }
    
    // Stats inner class
    @lombok.Data
    @lombok.Builder
    public static class QuotationStats {
        private long totalQuotations;
        private long newQuotations;
        private long shortlisted;
        private long approved;
        private long rejected;
        private long expired;
    }
}