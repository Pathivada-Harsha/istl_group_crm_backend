package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {
    
    private final VendorRepository vendorRepository;
    
    /**
     * Get vendors with role-based and project-based filtering
     * IMPORTANT: Only returns vendors we've placed POs with (totalOrders > 0)
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
        
        // Search takes priority
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            if (isAdmin) {
                return vendorRepository.searchVendors(searchTerm, pageable);
            } else {
                return vendorRepository.searchVendorsWithUserAccess(searchTerm, userId, pageable);
            }
        }
        
        // Project-based filtering
        if (isAdmin) {
            if (projectId != null && !projectId.isEmpty()) {
                return vendorRepository.findByProjectId(projectId, pageable);
            }
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return vendorRepository.findByGroupAndSubGroup(groupName, subGroupName, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return vendorRepository.findByGroupName(groupName, pageable);
            }
            return vendorRepository.findAllActiveVendors(pageable);
        } else {
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
     * Create new vendor manually (not recommended - should come from PO)
     */
    @Transactional
    public VendorEntity createVendor(VendorEntity vendor, Long userId) {
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
        
        // Update financial info (only if provided - usually auto-updated)
        if (updatedVendor.getLastPurchaseAmount() != null) {
            existing.setLastPurchaseAmount(updatedVendor.getLastPurchaseAmount());
        }
        if (updatedVendor.getTotalPurchaseValue() != null) {
            existing.setTotalPurchaseValue(updatedVendor.getTotalPurchaseValue());
        }
        
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
     * Get vendor statistics with project filtering
     */
    @Transactional(readOnly = true)
    public VendorStats getStatistics(String groupName, String subGroupName, String projectId, Long userId, String userRole) {
        boolean isAdmin = isAdmin(userRole);
        
        // Get filtered vendors based on project and role
        Page<VendorEntity> allVendors = getVendors(
                groupName, subGroupName, projectId, null, null, null, null, null,
                userId, userRole, 0, Integer.MAX_VALUE, "name", "ASC"
        );
        
        List<VendorEntity> vendors = allVendors.getContent();
        
        // Calculate stats from filtered vendors
        long total = vendors.size();
        long active = vendors.stream().filter(v -> "Active".equals(v.getStatus())).count();
        long inactive = vendors.stream().filter(v -> "Inactive".equals(v.getStatus())).count();
        
        Double avgRating = vendors.stream()
                .map(VendorEntity::getRating)
                .filter(r -> r != null && r > 0)
                .mapToDouble(Integer::doubleValue)
                .average()
                .orElse(0.0);
        
        BigDecimal totalPurchaseValue = vendors.stream()
                .map(VendorEntity::getTotalPurchaseValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return VendorStats.builder()
                .totalVendors(total)
                .activeVendors(active)
                .inactiveVendors(inactive)
                .averageRating(avgRating)
                .totalPurchaseValue(totalPurchaseValue)
                .build();
    }
    
    /**
     * Update vendor purchase stats (called by PurchaseOrderService)
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
        log.info("Updated purchase stats for vendor: {}", vendor.getName());
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
        private long pendingQuotations; // Can be added if needed
    }
}