package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.OrderBookEntity;
import com.istlgroup.istl_group_crm_backend.entity.OrderBookItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.entity.QuotationItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.QuotationRepository;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookItemRepo;
import com.istlgroup.istl_group_crm_backend.repo.OrderBookRepo;
import com.istlgroup.istl_group_crm_backend.repo.QuotationItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
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
    private final OrderBookRepo orderBookRepo;
    private final OrderBookItemRepo orderBookItemRepo;
    private final ProjectAccessService projectAccessService;
    private final RoleHierarchyRepo roleHierarchyRepo;
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
            String uploadedFromStr,
            String uploadedToStr,
            Long userId,
            String userRole,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        // When date filter active → force sort by uploadedAt ASC for chronological display
        boolean isDateFiltered = (uploadedFromStr != null && !uploadedFromStr.isBlank())
                              || (uploadedToStr   != null && !uploadedToStr.isBlank());
        String activeSortBy  = isDateFiltered ? "uploadedAt"  : sortBy;
        String activeSortDir = isDateFiltered ? "ASC"         : sortDirection;

        Pageable pageable = PageRequest.of(
                page, size,
                Sort.by(Sort.Direction.fromString(activeSortDir), activeSortBy)
        );

        boolean isAdmin = isAdmin(userRole);

        // Parse date range — to-date extended to end-of-day so full "to" date is included
        java.time.LocalDateTime fromDt = null;
        java.time.LocalDateTime toDt   = null;
        if (uploadedFromStr != null && !uploadedFromStr.isBlank()) {
            try { fromDt = java.time.LocalDate.parse(uploadedFromStr).atStartOfDay(); } catch (Exception ignored) {}
        }
        if (uploadedToStr != null && !uploadedToStr.isBlank()) {
            try { toDt = java.time.LocalDate.parse(uploadedToStr).atTime(23, 59, 59); } catch (Exception ignored) {}
        }

        // User with a project access grant sees all quotations for that project
        boolean hasProjectGrant = !isAdmin
                && projectId != null && !projectId.isEmpty()
                && projectAccessService.canAccessProject(projectId, userId, userRole);

        // Search takes priority — but non-admin must still be scoped to accessible projects
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            if (isAdmin || (projectId != null && !projectId.isEmpty())) {
                return quotationRepository.searchProcurement(searchTerm, fromDt, toDt, pageable);
            } else {
                List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
                if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                    return quotationRepository.searchProcurementWithProjectIds(
                            searchTerm, accessibleProjectIds, fromDt, toDt, pageable);
                }
                return Page.empty(pageable);
            }
        }

        // Project-based filtering — admin OR user with project access grant
        if (isAdmin || hasProjectGrant) {
            if (projectId != null && !projectId.isEmpty()) {
                return quotationRepository.findByProjectId(projectId, fromDt, toDt, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return quotationRepository.findByGroupAndSubGroup(groupName, subGroupName, fromDt, toDt, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return quotationRepository.findByGroupName(groupName, fromDt, toDt, pageable);
            }
            return quotationRepository.findAllProcurement(fromDt, toDt, pageable);
        } else {
            // Non-admin: scope to accessible project IDs
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            boolean hasAccessibleProjects = accessibleProjectIds != null && !accessibleProjectIds.isEmpty();

            if (projectId != null && !projectId.isEmpty()) {
                return quotationRepository.findByProjectIdAndUserAccess(projectId, userId, fromDt, toDt, pageable);
            } else if (hasAccessibleProjects) {
                if (subGroupName != null && !subGroupName.isEmpty()) {
                    return quotationRepository.findByGroupSubGroupAndAccessibleProjects(groupName, subGroupName, accessibleProjectIds, fromDt, toDt, pageable);
                } else if (groupName != null && !groupName.isEmpty()) {
                    return quotationRepository.findByGroupNameAndAccessibleProjects(groupName, accessibleProjectIds, fromDt, toDt, pageable);
                } else {
                    return quotationRepository.findByAccessibleProjects(accessibleProjectIds, fromDt, toDt, pageable);
                }
            } else {
                if (subGroupName != null && !subGroupName.isEmpty()) {
                    return quotationRepository.findByGroupSubGroupAndUserAccess(groupName, subGroupName, userId, fromDt, toDt, pageable);
                } else if (groupName != null && !groupName.isEmpty()) {
                    return quotationRepository.findByGroupNameAndUserAccess(groupName, userId, fromDt, toDt, pageable);
                }
                return quotationRepository.findByUserAccess(userId, fromDt, toDt, pageable);
            }
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
            // Quotation number is derived from the DB-generated id (see below).
            // Use a temp placeholder to satisfy NOT NULL constraint.
            if (quotation.getQuoteNo() == null || quotation.getQuoteNo().isEmpty()) {
                quotation.setQuoteNo("__TEMP_QUO_" + System.nanoTime() + "__");
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
            
            // Save quotation FIRST — DB assigns auto-increment id
            QuotationEntity savedQuotation = quotationRepository.save(quotation);
            // Derive quotation number from the DB id: QUO-YYYY-NNNN
            // Only update if we put a temp placeholder (not if caller supplied a number)
            if (savedQuotation.getQuoteNo() != null && savedQuotation.getQuoteNo().startsWith("__TEMP_QUO_")) {
                String quoteNo = String.format("QUO-%d-%04d",
                    java.time.Year.now().getValue(), savedQuotation.getId());
                savedQuotation.setQuoteNo(quoteNo);
                savedQuotation = quotationRepository.save(savedQuotation);
            }
            log.info("Quotation saved with ID: {} and number: {}", savedQuotation.getId(), savedQuotation.getQuoteNo());
            
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
    log.info("🔄 Updating quotation ID: {}", id);
    
    QuotationEntity existing = getQuotationById(id);
    
    // Update basic fields
    existing.setVendorId(updatedQuotation.getVendorId());
    existing.setVendorName(updatedQuotation.getVendorName());
    existing.setVendorContact(updatedQuotation.getVendorContact());
    existing.setRfqId(updatedQuotation.getRfqId());
    existing.setValidTill(updatedQuotation.getValidTill());
    existing.setGroupName(updatedQuotation.getGroupName());
    existing.setSubGroupName(updatedQuotation.getSubGroupName());
    existing.setProjectId(updatedQuotation.getProjectId());
    existing.setVendorRating(updatedQuotation.getVendorRating());
    existing.setDeliveryTime(updatedQuotation.getDeliveryTime());
    existing.setPaymentTerms(updatedQuotation.getPaymentTerms());
    existing.setWarranty(updatedQuotation.getWarranty());
    existing.setNotes(updatedQuotation.getNotes());
    existing.setCategory(updatedQuotation.getCategory());
    existing.setStatus(updatedQuotation.getStatus()); // NEW: Update status
    
    // Update file if provided (new file uploaded)
    if (updatedQuotation.getQuotationFile() != null && updatedQuotation.getQuotationFile().length > 0) {
        existing.setQuotationFile(updatedQuotation.getQuotationFile());
        existing.setFileName(updatedQuotation.getFileName());
        existing.setFileType(updatedQuotation.getFileType());
        existing.setFileSize(updatedQuotation.getFileSize());
        log.info("📄 Updated file: {}", updatedQuotation.getFileName());
    }
    
    // Handle items update - THE FIX
    if (updatedQuotation.getItems() != null) {
        log.info("📦 Updating items - {} new items provided", updatedQuotation.getItems().size());
        
        // Step 1: Delete ALL existing items (clean slate approach)
        if (existing.getItems() != null && !existing.getItems().isEmpty()) {
            log.info("🗑️ Deleting {} existing items", existing.getItems().size());
            quotationItemRepository.deleteAll(existing.getItems());
            quotationItemRepository.flush(); // Ensure deletion is committed
            existing.getItems().clear();
        }
        
        // Step 2: Create fresh item entities from the updated data
        List<QuotationItemEntity> newItems = new ArrayList<>();
        for (int i = 0; i < updatedQuotation.getItems().size(); i++) {
            QuotationItemEntity itemData = updatedQuotation.getItems().get(i);
            
            // Create a completely new entity (not attached to any session)
            QuotationItemEntity newItem = new QuotationItemEntity();
            newItem.setQuotation(existing); // Link to parent quotation
            newItem.setItemName(itemData.getItemName());
            newItem.setDescription(itemData.getDescription());
            newItem.setQuantity(itemData.getQuantity() != null ? itemData.getQuantity() : BigDecimal.ONE);
            newItem.setUnitPrice(itemData.getUnitPrice() != null ? itemData.getUnitPrice() : BigDecimal.ZERO);
            newItem.setTaxPercent(itemData.getTaxPercent() != null ? itemData.getTaxPercent() : BigDecimal.valueOf(18));
            newItem.setLineNo(i + 1);
            newItem.setCreatedAt(LocalDateTime.now());
            
            // Calculate line total
            BigDecimal qty = newItem.getQuantity();
            BigDecimal price = newItem.getUnitPrice();
            BigDecimal tax = newItem.getTaxPercent();
            BigDecimal subtotal = qty.multiply(price);
            BigDecimal taxAmount = subtotal.multiply(tax).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            newItem.setLineTotal(subtotal.add(taxAmount));
            
            newItems.add(newItem);
        }
        
        // Step 3: Save new items
        if (!newItems.isEmpty()) {
            log.info("💾 Saving {} new items", newItems.size());
            List<QuotationItemEntity> savedItems = quotationItemRepository.saveAll(newItems);
            existing.setItems(savedItems);
            log.info("✅ Items saved successfully");
        }
        
        // Step 4: Recalculate total value
        BigDecimal totalValue = calculateTotalValue(newItems);
        existing.setTotalValue(totalValue);
        log.info("💰 Recalculated total value: {}", totalValue);
    }
    
    // Save the updated quotation
    QuotationEntity saved = quotationRepository.save(existing);
    log.info("✅ Quotation {} updated successfully", saved.getQuoteNo());
    
    return saved;
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
    return getStatistics(groupName, subGroupName, projectId, null, null, null, null, userId, userRole);
}

public QuotationStats getStatistics(String groupName, String subGroupName, String projectId,
        String status, String searchTerm, Long userId, String userRole) {
    return getStatistics(groupName, subGroupName, projectId, status, searchTerm, null, null, userId, userRole);
}

public QuotationStats getStatistics(String groupName, String subGroupName, String projectId,
        String status, String searchTerm,
        String uploadedFromStr, String uploadedToStr,
        Long userId, String userRole) {
    boolean isAdmin = isAdmin(userRole);

    log.info("📊 Fetching stats - group: {}, subGroup: {}, project: {}, status: {}, search: {}, uploadedFrom: {}, uploadedTo: {}, user: {}, isAdmin: {}",
             groupName, subGroupName, projectId, status, searchTerm, uploadedFromStr, uploadedToStr, userId, isAdmin);

    List<QuotationEntity> quotations;

    // Determine filtering level based on what's provided
    if (projectId != null && !projectId.trim().isEmpty()) {
        log.info("🎯 Filtering by PROJECT: {}", projectId);
        quotations = quotationRepository.findByTypeAndProjectIdAndDeletedAtIsNull("Procurement", projectId);

    } else if (isAdmin) {
        if (subGroupName != null && !subGroupName.trim().isEmpty() && groupName != null) {
            log.info("🎯 Admin: GROUP + SUBGROUP: {} / {}", groupName, subGroupName);
            quotations = quotationRepository.findByTypeAndGroupNameAndSubGroupNameAndDeletedAtIsNull(
                "Procurement", groupName, subGroupName);
        } else if (groupName != null && !groupName.trim().isEmpty()) {
            log.info("🎯 Admin: GROUP: {}", groupName);
            quotations = quotationRepository.findByTypeAndGroupNameAndDeletedAtIsNull("Procurement", groupName);
        } else {
            log.info("🎯 Admin: ALL quotations");
            quotations = quotationRepository.findByTypeAndDeletedAtIsNull("Procurement");
        }

    } else {
        List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
        if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
            log.info("🎯 Non-admin: {} accessible projects", accessibleProjectIds.size());
            quotations = quotationRepository.findByTypeAndDeletedAtIsNull("Procurement").stream()
                .filter(q -> accessibleProjectIds.contains(q.getProjectId()))
                .filter(q -> groupName == null || groupName.isBlank() || groupName.equalsIgnoreCase(q.getGroupName()))
                .filter(q -> subGroupName == null || subGroupName.isBlank() || subGroupName.equalsIgnoreCase(q.getSubGroupName()))
                .collect(java.util.stream.Collectors.toList());
        } else {
            log.info("🎯 Non-admin: no accessible projects — using preparedBy filter");
            if (subGroupName != null && !subGroupName.trim().isEmpty() && groupName != null) {
                quotations = quotationRepository.findByTypeAndGroupNameAndSubGroupNameAndPreparedByAndDeletedAtIsNull(
                    "Procurement", groupName, subGroupName, userId);
            } else if (groupName != null && !groupName.trim().isEmpty()) {
                quotations = quotationRepository.findByTypeAndGroupNameAndPreparedByAndDeletedAtIsNull(
                    "Procurement", groupName, userId);
            } else {
                quotations = quotationRepository.findByTypeAndPreparedByAndDeletedAtIsNull("Procurement", userId);
            }
        }
    }

    log.info("📦 Found {} quotations for stats calculation (before filters)", quotations.size());

    // Apply uploaded-date range filter
    if (uploadedFromStr != null && !uploadedFromStr.isBlank()) {
        try {
            final java.time.LocalDateTime from = java.time.LocalDate.parse(uploadedFromStr).atStartOfDay();
            quotations = quotations.stream()
                .filter(q -> q.getUploadedAt() != null && !q.getUploadedAt().isBefore(from))
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception ignored) {}
    }
    if (uploadedToStr != null && !uploadedToStr.isBlank()) {
        try {
            final java.time.LocalDateTime to = java.time.LocalDate.parse(uploadedToStr).atTime(23, 59, 59);
            quotations = quotations.stream()
                .filter(q -> q.getUploadedAt() != null && !q.getUploadedAt().isAfter(to))
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception ignored) {}
    }

    // Apply status filter if provided
    if (status != null && !status.trim().isEmpty() && !"all".equalsIgnoreCase(status.trim())) {
        final String s = status.trim();
        quotations = quotations.stream()
            .filter(q -> s.equals(q.getStatus()))
            .collect(java.util.stream.Collectors.toList());
    }

    // Apply search filter
    if (searchTerm != null && !searchTerm.trim().isEmpty()) {
        final String term = searchTerm.trim();
        List<QuotationEntity> searchMatches = quotationRepository.searchProcurementList(term);
        final java.util.Set<Long> scopedIds = quotations.stream()
            .map(QuotationEntity::getId)
            .collect(java.util.stream.Collectors.toSet());
        quotations = searchMatches.stream()
            .filter(q -> scopedIds.contains(q.getId()))
            .collect(java.util.stream.Collectors.toList());
    }

    log.info("📦 {} quotations after all filters", quotations.size());

    long total        = quotations.size();
    long newQuotations = quotations.stream().filter(q -> "New".equals(q.getStatus())).count();
    long underReview  = quotations.stream().filter(q -> "Under Review".equals(q.getStatus())).count();
    long shortlisted  = quotations.stream().filter(q -> "Shortlisted".equals(q.getStatus())).count();
    long approved     = quotations.stream().filter(q -> "Approved".equals(q.getStatus())).count();
    long rejected     = quotations.stream().filter(q -> "Rejected".equals(q.getStatus())).count();
    long expired      = quotations.stream().filter(q -> "Expired".equals(q.getStatus())).count();
    long poCreated    = quotations.stream().filter(q -> "PO Created".equals(q.getStatus())).count();

    QuotationStats stats = QuotationStats.builder()
            .totalQuotations(total)
            .newQuotations(newQuotations)
            .underReview(underReview)
            .shortlisted(shortlisted)
            .approved(approved)
            .rejected(rejected)
            .expired(expired)
            .poCreated(poCreated)
            .build();

    log.info("✅ Stats calculated - Total: {}, New: {}, Approved: {}, Rejected: {}",
             total, newQuotations, approved, rejected);

    return stats;
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
    
    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * Rules:
     *   1. ADMIN / SUPERADMIN always bypass.
     *   2. Any role starting with ACCOUNTS_ bypasses (ACCOUNTS_EXECUTIVE, ACCOUNTS_CFO, etc.).
     *   3. Any role whose level_order in role_hierarchy is <= 2 bypasses.
     */
    private boolean isAdmin(String userRole) {
        if (userRole == null) return false;
        String r = userRole.trim().toUpperCase();
        if (r.equals("ADMIN") || r.equals("SUPERADMIN")) return true;
        if (r.startsWith("ACCOUNTS_")) return true;
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(userRole))
                .map(level -> level <= 2)
                .orElse(false);
    }
    
    private String generateQuoteNumber() {

        int currentYear = java.time.Year.now().getValue(); // 2026

        List<String> lastQuoteList =
                quotationRepository.findLastQuoteNoByYear(
                        currentYear,
                        org.springframework.data.domain.PageRequest.of(0, 1)
                );

        // Default start
        int nextNumber = 1;
        int paddingLength = 3; // default → 001

        if (!lastQuoteList.isEmpty()) {
            String lastQuoteNo = lastQuoteList.get(0); // QUO-2026-00022

            String numericPart = lastQuoteNo.substring(lastQuoteNo.lastIndexOf("-") + 1);

            paddingLength = numericPart.length();       // 5
            nextNumber = Integer.parseInt(numericPart) + 1; // 23
        }

        String formattedSequence =
                String.format("%0" + paddingLength + "d", nextNumber);

        return "QUO-" + currentYear + "-" + formattedSequence;
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

/**
 * Get order book items for a project
 * Used to pre-populate quotation items from order book
 */
@Transactional(readOnly = true)
public List<Map<String, Object>> getOrderBookItemsByProject(String projectId) {
    try {
        log.info("Fetching order book items for project: {}", projectId);
        
        if (projectId == null || projectId.trim().isEmpty()) {
            log.warn("Project ID is empty");
            return new ArrayList<>();
        }
        
        // Find all order books for this project
        List<OrderBookEntity> orderBooks = orderBookRepo.findByProjectIdAndDeletedAtIsNull(projectId);
        
        if (orderBooks.isEmpty()) {
            log.info("No order books found for project: {}", projectId);
            return new ArrayList<>();
        }
        
        log.info("Found {} order books for project {}", orderBooks.size(), projectId);
        
        List<Map<String, Object>> allItems = new ArrayList<>();
        
        // Collect items from all order books
        for (OrderBookEntity orderBook : orderBooks) {
            List<OrderBookItemEntity> items = orderBookItemRepo
                .findByOrderBookIdOrderByLineNo(orderBook.getId());
            
            log.info("Order book {} has {} items", orderBook.getOrderBookNo(), items.size());
            
            for (OrderBookItemEntity item : items) {
                Map<String, Object> itemMap = new HashMap<>();
                
                // Basic item info
                itemMap.put("id", item.getId());
                itemMap.put("orderBookId", orderBook.getId());
                itemMap.put("orderBookNo", orderBook.getOrderBookNo());
                
                // Item details
                itemMap.put("itemName", item.getItemName() != null ? item.getItemName() : "");
                itemMap.put("specification", item.getSpecification() != null ? item.getSpecification() : "");
                itemMap.put("description", item.getDescription() != null ? item.getDescription() : "");
                
                // Pricing and quantity
                itemMap.put("quantity", item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO);
                itemMap.put("unit", item.getUnit() != null ? item.getUnit() : "Nos");
                itemMap.put("unitPrice", item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
                itemMap.put("taxPercent", item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO);
                itemMap.put("discountPercent", item.getDiscountPercent() != null ? item.getDiscountPercent() : BigDecimal.ZERO);
                
                // Additional fields
//                itemMap.put("hsnCode", item.getHsnCode());
                itemMap.put("lineNo", item.getLineNo());
                
                // Calculate total (quantity * unitPrice * (1 + tax/100))
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                BigDecimal price = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal tax = item.getTaxPercent() != null ? item.getTaxPercent() : BigDecimal.ZERO;
                
                BigDecimal subtotal = qty.multiply(price);
                BigDecimal taxAmount = subtotal.multiply(tax).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal total = subtotal.add(taxAmount);
                
                itemMap.put("totalAmount", total);
                
                allItems.add(itemMap);
            }
        }
        
        log.info("Returning {} total order book items for project {}", allItems.size(), projectId);
        return allItems;
        
    } catch (Exception e) {
        log.error("Error fetching order book items for project {}: {}", projectId, e.getMessage(), e);
        return new ArrayList<>();
    }
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
        private long underReview;
        private long poCreated;
    }
}