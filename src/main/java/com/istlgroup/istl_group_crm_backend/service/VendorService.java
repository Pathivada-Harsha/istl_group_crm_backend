package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.repo.QuotationRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {
    
    private final VendorRepository vendorRepository;
    private final QuotationRepository quotationRepository;
    
    /**
     * Get vendors with role-based and project-based filtering + category + status
     */
    @Transactional(readOnly = true)
    public Page<VendorEntity> getVendors(
            String groupName,
            String subGroupName,
            String projectId,
            String category,
            String vendorType,
            Integer rating,
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
        
        // Normalize filters
        String categoryFilter = (category != null && !category.equals("all")) ? category : null;
        String statusFilter = (status != null && !status.equals("all")) ? status : null;
        
        // Search takes priority
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            if (isAdmin) {
                return vendorRepository.searchVendors(searchTerm, pageable);
            } else {
                return vendorRepository.searchVendorsWithUserAccess(searchTerm, userId, pageable);
            }
        }
        
        // Project-based filtering with additional filters
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                return vendorRepository.findByProjectIdAndFilters(projectId, categoryFilter, statusFilter, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return vendorRepository.findByGroupSubGroupAndFilters(groupName, subGroupName, categoryFilter, statusFilter, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return vendorRepository.findByGroupNameAndFilters(groupName, categoryFilter, statusFilter, pageable);
            }
            return vendorRepository.findByFilters(categoryFilter, statusFilter, pageable);
        } else {
            // User access - apply project filters
            if (projectId != null && !projectId.isEmpty()) {
                return vendorRepository.findByProjectIdAndUserAccess(projectId, userId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return vendorRepository.findByGroupSubGroupAndUserAccess(groupName, subGroupName, userId, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return vendorRepository.findByGroupNameAndUserAccess(groupName, userId, pageable);
            }
            return vendorRepository.findByUserAccess(userId, pageable);
        }
    }
    
    /**
     * Get vendor by ID
     */
    @Transactional(readOnly = true)
    public VendorEntity getVendorById(Long id) {
        return vendorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vendor not found with id: " + id));
    }
    
    /**
     * Create vendor from quotation
     */
    @Transactional
    public Long createVendorFromQuotation(QuotationEntity quotation, Long userId) {
        String vendorName = quotation.getVendorContact() != null 
            ? "Vendor-" + quotation.getVendorContact().substring(0, Math.min(10, quotation.getVendorContact().length()))
            : "Auto-Vendor-" + System.currentTimeMillis();
        
        // Handle null project_id
        String projectId = quotation.getProjectId();
        if (projectId == null || projectId.trim().isEmpty()) {
            projectId = "DEFAULT";
        }
        
        VendorEntity vendor = VendorEntity.builder()
                .vendorCode(generateVendorCode())
                .name(vendorName)
                .email("vendor_" + System.currentTimeMillis() + "@temp.com")
                .phone(quotation.getVendorContact())
                .rating(quotation.getVendorRating() != null ? quotation.getVendorRating().intValue() : 0)
                .status("Active")
                .groupName(quotation.getGroupName() != null ? quotation.getGroupName() : "Others")
                .subGroupName(quotation.getSubGroupName() != null ? quotation.getSubGroupName() : "General")
                .projectId(projectId)
                .category(quotation.getCategory() != null ? quotation.getCategory() : "General")
                .totalOrders(0)
                .totalPurchaseValue(BigDecimal.ZERO)
                .createdBy(userId)
                .build();
        
        VendorEntity savedVendor = vendorRepository.save(vendor);
        log.info("Created new vendor {} with ID: {}", vendorName, savedVendor.getId());
        
        return savedVendor.getId();
    }
    
    /**
     * Create new vendor manually
     */
    @Transactional
    public VendorEntity createVendor(VendorEntity vendor, Long userId) {
        // Validate project_id
        if (vendor.getProjectId() == null || vendor.getProjectId().trim().isEmpty()) {
            vendor.setProjectId("DEFAULT");
        }
        
        // Generate vendor code if not provided
        if (vendor.getVendorCode() == null || vendor.getVendorCode().isEmpty()) {
            vendor.setVendorCode(generateVendorCode());
        }
        
        vendor.setCreatedBy(userId);
        vendor.setCreatedAt(LocalDateTime.now());
        vendor.setUpdatedAt(LocalDateTime.now());
        
        // Initialize purchase tracking
        if (vendor.getTotalOrders() == null) vendor.setTotalOrders(0);
        if (vendor.getTotalPurchaseValue() == null) vendor.setTotalPurchaseValue(BigDecimal.ZERO);
        if (vendor.getLastPurchaseAmount() == null) vendor.setLastPurchaseAmount(BigDecimal.ZERO);
        
        if (vendor.getStatus() == null) vendor.setStatus("Active");
        if (vendor.getGroupName() == null) vendor.setGroupName("Others");
        if (vendor.getSubGroupName() == null) vendor.setSubGroupName("General");
        
        VendorEntity savedVendor = vendorRepository.save(vendor);
        log.info("Created vendor: {} by user: {}", vendor.getName(), userId);
        
        return savedVendor;
    }
    
    /**
     * Update vendor
     */
    @Transactional
    public VendorEntity updateVendor(Long id, VendorEntity updatedVendor) {
        VendorEntity existing = getVendorById(id);
        
        // Update basic info
        existing.setName(updatedVendor.getName());
        existing.setContactPerson(updatedVendor.getContactPerson());
        existing.setEmail(updatedVendor.getEmail());
        existing.setPhone(updatedVendor.getPhone());
        existing.setWebsite(updatedVendor.getWebsite());
        existing.setGstNumber(updatedVendor.getGstNumber());
        existing.setAddress(updatedVendor.getAddress());
        existing.setCity(updatedVendor.getCity());
        existing.setState(updatedVendor.getState());
        existing.setPincode(updatedVendor.getPincode());
        existing.setRating(updatedVendor.getRating());
        existing.setStatus(updatedVendor.getStatus());
        
        // Update project assignment
        existing.setGroupName(updatedVendor.getGroupName());
        existing.setSubGroupName(updatedVendor.getSubGroupName());
        existing.setProjectId(updatedVendor.getProjectId());
        
        // Update categorization
        existing.setVendorType(updatedVendor.getVendorType());
        existing.setCategory(updatedVendor.getCategory());
        existing.setNotes(updatedVendor.getNotes());
        existing.setAssignedTo(updatedVendor.getAssignedTo());
        
        existing.setUpdatedAt(LocalDateTime.now());
        
        return vendorRepository.save(existing);
    }
    
    /**
     * Soft delete vendor
     */
    @Transactional
    public void deleteVendor(Long id) {
        VendorEntity vendor = getVendorById(id);
        vendor.setDeletedAt(LocalDateTime.now());
        vendor.setStatus("Inactive");
        vendorRepository.save(vendor);
        
        log.info("Soft deleted vendor: {}", vendor.getName());
    }
    
    /**
     * Get vendors by category
     */
    @Transactional(readOnly = true)
    public List<VendorEntity> getVendorsByCategory(String category) {
        return vendorRepository.findByCategory(category);
    }
    
    /**
     * Get vendors by type
     */
    @Transactional(readOnly = true)
    public List<VendorEntity> getVendorsByType(String vendorType) {
        return vendorRepository.findByVendorType(vendorType);
    }
    
    /**
     * Get vendor statistics with project filtering - FIXED FOR KPI CARDS
     */
    @Transactional(readOnly = true)
    public VendorStats getStatistics(String groupName, String subGroupName, String projectId, Long userId, String userRole) {
        // Normalize parameters
        String groupFilter = (groupName != null && !groupName.isEmpty()) ? groupName : null;
        String subGroupFilter = (subGroupName != null && !subGroupName.isEmpty()) ? subGroupName : null;
        String projectFilter = (projectId != null && !projectId.isEmpty()) ? projectId : null;
        
        // Get stats using repository queries
        long totalVendors = vendorRepository.countByFilters(groupFilter, subGroupFilter, projectFilter);
        long activeVendors = vendorRepository.countActiveByFilters(groupFilter, subGroupFilter, projectFilter);
        Double avgRating = vendorRepository.getAverageRatingByFilters(groupFilter, subGroupFilter, projectFilter);
        Double totalPurchaseValue = vendorRepository.getTotalPurchaseValueByFilters(groupFilter, subGroupFilter, projectFilter);
        long pendingQuotations = vendorRepository.countPendingQuotationsByFilters(groupFilter, subGroupFilter, projectFilter);
        
        long inactiveVendors = totalVendors - activeVendors;
        
        return VendorStats.builder()
                .totalVendors(totalVendors)
                .activeVendors(activeVendors)
                .inactiveVendors(inactiveVendors)
                .averageRating(avgRating != null ? avgRating : 0.0)
                .totalPurchaseValue(totalPurchaseValue != null ? BigDecimal.valueOf(totalPurchaseValue) : BigDecimal.ZERO)
                .pendingQuotations(pendingQuotations)
                .lastUpdated(LocalDateTime.now())
                .build();
    }
    
    /**
     * Update vendor purchase stats when PO is delivered
     * Called by PurchaseOrderService
     */
    @Transactional
    public void updateVendorPurchaseStats(
            Long vendorId,
            BigDecimal purchaseAmount,
            LocalDateTime purchaseDate
    ) {
        VendorEntity vendor = getVendorById(vendorId);
        
        vendor.setLastPurchaseAmount(purchaseAmount);
        vendor.setLastPurchaseDate(purchaseDate);
        
        BigDecimal currentTotal = vendor.getTotalPurchaseValue() != null 
                ? vendor.getTotalPurchaseValue() 
                : BigDecimal.ZERO;
        vendor.setTotalPurchaseValue(currentTotal.add(purchaseAmount));
        
        Integer currentOrders = vendor.getTotalOrders() != null ? vendor.getTotalOrders() : 0;
        vendor.setTotalOrders(currentOrders + 1);
        
        vendorRepository.save(vendor);
        log.info("Updated purchase stats for vendor: {} - Total: {}, Orders: {}", 
            vendor.getName(), vendor.getTotalPurchaseValue(), vendor.getTotalOrders());
    }
    
    // Helper methods
    
    private boolean isAdmin(String userRole) {
        return "ADMIN".equalsIgnoreCase(userRole) || "SUPERADMIN".equalsIgnoreCase(userRole);
    }
    
    private String generateVendorCode() {
        long count = vendorRepository.countActiveVendors();
        return String.format("VEN-%05d", count + 1);
    }
    
    // Stats inner class
    @lombok.Data
    @lombok.Builder
    public static class VendorStats {
        private long totalVendors;
        private long activeVendors;
        private long inactiveVendors;
        private Double averageRating;
        private BigDecimal totalPurchaseValue;
        private long pendingQuotations;
        private LocalDateTime lastUpdated;
    }
    
    

    /**
     * Get vendors accessible by user (created_by or assigned_to)
     * Returns simplified list for dropdown: [{id: 1, name: "Vendor A", phone: "1234567890"}, ...]
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVendorsForDropdown(Long userId) {
        log.info("Fetching vendors dropdown for userId: {}", userId);
        return vendorRepository.findVendorsByUserIdForDropdown(userId);
    }
    
    /**
     * Get vendors who have quotation history in other projects
     * COMMENTED OUT FOR NOW - NOT BEING USED
     */
    /*
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVendorsWithQuotationHistory(Long userId, String currentProjectId) {
        log.info("Fetching vendors with quotation history for userId: {}, projectId: {}", userId, currentProjectId);
        if (currentProjectId == null || currentProjectId.isEmpty()) {
            // If no current project, return all vendors
            return vendorRepository.findVendorsByUserIdForDropdown(userId);
        }
        return vendorRepository.findVendorsWithQuotationHistoryForDropdown(userId, currentProjectId);
    }
    */
}