package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.entity.QuotationEntity;
import com.istlgroup.istl_group_crm_backend.entity.VendorEntity;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderRepository;
import com.istlgroup.istl_group_crm_backend.repo.QuotationRepository;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {
    
    private final VendorRepository vendorRepository;
    private final QuotationRepository quotationRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProjectAccessService projectAccessService;
    private final RoleHierarchyRepo roleHierarchyRepo;
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
            String createdAtFromStr,
            String createdAtToStr,
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
        
        // Normalize filters
        String categoryFilter = (category != null && !category.equals("all")) ? category : null;
        String statusFilter = (status != null && !status.equals("all")) ? status : null;
        // Normalize group/project filters upfront so search can use them too
        String groupFilter    = (groupName    != null && !groupName.isEmpty())    ? groupName    : null;
        String subGroupFilter = (subGroupName != null && !subGroupName.isEmpty()) ? subGroupName : null;
        String projectFilter  = (projectId    != null && !projectId.isEmpty())    ? projectId    : null;

        // Parse date filters — createdAtTo is extended to end-of-day so the full "to" date is included
        java.time.LocalDateTime fromDt = null;
        java.time.LocalDateTime toDt   = null;
        if (createdAtFromStr != null && !createdAtFromStr.isBlank()) {
            try { fromDt = java.time.LocalDate.parse(createdAtFromStr).atStartOfDay(); } catch (Exception ignored) {}
        }
        if (createdAtToStr != null && !createdAtToStr.isBlank()) {
            try { toDt = java.time.LocalDate.parse(createdAtToStr).atTime(23, 59, 59); } catch (Exception ignored) {}
        }

        boolean isAdmin = userRole != null &&
                (userRole.equalsIgnoreCase("SUPERADMIN") || userRole.equalsIgnoreCase("ADMIN") ||
                 userRole.toUpperCase().startsWith("ACCOUNTS_"));

        // Search: applies across all text fields AND respects status / category / group / project scope.
        // Previously this did an early return passing only searchTerm, which caused status, category,
        // and group filters to be silently ignored whenever a search term was present.
        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            if (isAdmin) {
                return vendorRepository.searchVendors(
                        searchTerm.trim(), categoryFilter, statusFilter,
                        groupFilter, subGroupFilter, projectFilter, fromDt, toDt, pageable);
            } else {
                return vendorRepository.searchVendorsWithUserAccess(
                        searchTerm.trim(), categoryFilter, statusFilter,
                        groupFilter, subGroupFilter, projectFilter, userId, fromDt, toDt, pageable);
            }
        }

        // Specific project selected — same for all roles
        if (projectId != null && !projectId.isEmpty()) {
            return vendorRepository.findByProjectIdAndFiltersOrHasPOs(projectId, categoryFilter, statusFilter, fromDt, toDt, pageable);
        }

        if (isAdmin) {
            // Admin: group/subgroup filter with no restriction
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return vendorRepository.findByGroupSubGroupAndFilters(groupName, subGroupName, categoryFilter, statusFilter, fromDt, toDt, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return vendorRepository.findByGroupNameAndFilters(groupName, categoryFilter, statusFilter, fromDt, toDt, pageable);
            }
            return vendorRepository.findByFilters(categoryFilter, statusFilter, fromDt, toDt, pageable);
        } else {
            // Non-admin: show vendors from accessible project POs UNION vendors created/assigned to this user.
            // The union ensures that vendors the user just created (but not yet used in a PO)
            // always appear in dropdowns on other pages (e.g. Purchase Orders create modal).
            Set<Long> vendorIds = new java.util.HashSet<>();

            // 1. Vendors linked via POs in accessible projects
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                List<PurchaseOrderEntity> pos;
                if (subGroupName != null && !subGroupName.isEmpty()) {
                    pos = purchaseOrderRepository.findByGroupNameAndSubGroupName(groupName, subGroupName);
                } else if (groupName != null && !groupName.isEmpty()) {
                    pos = purchaseOrderRepository.findByGroupName(groupName);
                } else {
                    pos = new ArrayList<>();
                    for (String pid : accessibleProjectIds) {
                        pos.addAll(purchaseOrderRepository.findByProjectId(pid));
                    }
                }
                pos.stream()
                    .filter(po -> po.getDeletedAt() == null && po.getVendorId() != null)
                    .map(PurchaseOrderEntity::getVendorId)
                    .forEach(vendorIds::add);
            }

            // 2. Vendors created by or assigned to this user (always visible regardless of project access)
            if (userId != null) {
                vendorRepository.findByCreatedByOrAssignedToAndDeletedAtIsNull(userId)
                    .forEach(v -> vendorIds.add(v.getId()));
            }

            if (!vendorIds.isEmpty()) {
                return vendorRepository.findByIdsAndFilters(new ArrayList<>(vendorIds), categoryFilter, statusFilter, fromDt, toDt, pageable);
            }

            // Fallback: show all vendors (access controlled at page permission level)
            return vendorRepository.findByFilters(categoryFilter, statusFilter, fromDt, toDt, pageable);
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
                .vendorCode(null)  // assigned after save using DB id
                .name(vendorName)
                .email(null)
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
        // Assign vendor code from DB id — COUNT(*) breaks when vendors are deleted/deactivated
        savedVendor.setVendorCode(String.format("VEN-%06d", savedVendor.getId()));
        savedVendor = vendorRepository.save(savedVendor);
        log.info("Created new vendor {} with ID: {}", vendorName, savedVendor.getId());
        
        return savedVendor.getId();
    }
 // ============================================
 // ADD TO VendorService.java
 // ============================================

 /**
  * Get vendors for bills page
  * Combines vendors from vendors table + new vendors from POs
  * Filtered by project and deduplicated
  */
 @Transactional
 public List<Map<String, Object>> getVendorsForBills(
         String groupName, 
         String subGroupName, 
         String projectId
 ) {
     // Key insight: fetch vendors that have PURCHASE ORDERS under this project/group.
     // The vendors table's own project_id is where the vendor was registered, not
     // necessarily where they supplied goods — so we use PO linkage as the source of truth.

     // Step 1: Get all POs for this project/group scope
     // Uses List-returning queries — Pageable not appropriate here since we need all POs
     // to build a deduplicated vendor list.
     List<PurchaseOrderEntity> pos;
     if (projectId != null && !projectId.isEmpty()) {
         pos = purchaseOrderRepository.findListByProjectId(projectId);
     } else if (subGroupName != null && !subGroupName.isEmpty()) {
         pos = purchaseOrderRepository.findListByGroupNameAndSubGroupName(groupName, subGroupName);
     } else if (groupName != null && !groupName.isEmpty()) {
         pos = purchaseOrderRepository.findListByGroupName(groupName);
     } else {
         pos = purchaseOrderRepository.findByDeletedAtIsNull();
     }

     // Step 2: Build deduplicated vendor list from POs
     // - POs with vendorId → look up real vendor from vendors table (get name/contact from there)
     // - POs with only vendorName (legacy null-vendorId) → auto-create vendor record, update PO
     Map<Long, Map<String, Object>> byVendorId = new java.util.LinkedHashMap<>();

     for (PurchaseOrderEntity po : pos) {
         if (po.getDeletedAt() != null) continue; // skip deleted POs

         if (po.getVendorId() != null) {
             // Existing vendor — fetch from vendors table for accurate name/contact
             if (!byVendorId.containsKey(po.getVendorId())) {
                 Map<String, Object> vMap = new java.util.LinkedHashMap<>();
                 vMap.put("id", po.getVendorId());
                 // Prefer vendors table for name; fall back to PO vendorName
                 vendorRepository.findById(po.getVendorId()).ifPresentOrElse(
                     v -> {
                         vMap.put("name", v.getName());
                         vMap.put("contact", v.getPhone());
                         vMap.put("email", v.getEmail());
                         vMap.put("gstNumber", v.getGstNumber());
                         vMap.put("source", "vendors_table");
                     },
                     () -> {
                         vMap.put("name", po.getVendorName() != null ? po.getVendorName() : "Vendor #" + po.getVendorId());
                         vMap.put("contact", po.getVendorContact());
                         vMap.put("source", "po_vendor");
                     }
                 );
                 byVendorId.put(po.getVendorId(), vMap);
             }
         } else if (po.getVendorName() != null && !po.getVendorName().trim().isEmpty()) {
             // Legacy PO: vendorId is NULL but has a vendor_name.
             // Auto-migrate: find or create a vendor record, then update the PO.
             String phone = po.getVendorContact() != null ? po.getVendorContact().trim() : "";
             String name  = po.getVendorName().trim();

             Long resolvedVendorId = null;

             // 1. Try to find by phone
             if (!phone.isEmpty()) {
                 resolvedVendorId = vendorRepository.findByPhone(phone)
                         .map(VendorEntity::getId).orElse(null);
             }
             // 2. Try to find by name
             if (resolvedVendorId == null) {
                 resolvedVendorId = vendorRepository.findByName(name)
                         .map(VendorEntity::getId).orElse(null);
             }
             // 3. Create new vendor record to fix the data gap
             if (resolvedVendorId == null) {
                 VendorEntity newVendor = VendorEntity.builder()
                         .name(name)
                         .phone(phone.isEmpty() ? null : phone)
                         .email(null)
                         .rating(0)
                         .status("Active")
                         .groupName(po.getGroupName() != null ? po.getGroupName() : "Others")
                         .subGroupName(po.getSubGroupName() != null ? po.getSubGroupName() : "General")
                         .projectId(po.getProjectId() != null ? po.getProjectId() : "DEFAULT")
                         .totalOrders(0)
                         .totalPurchaseValue(java.math.BigDecimal.ZERO)
                         .build();
                 resolvedVendorId = vendorRepository.save(newVendor).getId();
                 log.info("Auto-migrated legacy inline vendor '{}' from PO {} → new vendorId {}",
                          name, po.getId(), resolvedVendorId);
             }

             // 4. Back-fill the PO so it won't be legacy next time
             final Long finalVendorId = resolvedVendorId;
             if (!java.util.Objects.equals(po.getVendorId(), finalVendorId)) {
                 po.setVendorId(finalVendorId);
                 purchaseOrderRepository.save(po);
             }

             // 5. Add to result if not already there
             if (!byVendorId.containsKey(resolvedVendorId)) {
                 Map<String, Object> vMap = new java.util.LinkedHashMap<>();
                 vMap.put("id", resolvedVendorId);
                 vMap.put("name", name);
                 vMap.put("contact", phone.isEmpty() ? null : phone);
                 vMap.put("source", "migrated_legacy");
                 byVendorId.put(resolvedVendorId, vMap);
             }
         }
     }

     // Step 3: Convert to list and sort by name
     List<Map<String, Object>> result = new ArrayList<>(byVendorId.values());

     // Sort by name
     result.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));

     log.info("Found {} vendors for project={} group={} subGroup={} (from PO linkage)",
              result.size(), projectId, groupName, subGroupName);
     return result;
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
        
        // Vendor code assigned after first save — do not set it here
        
        vendor.setCreatedBy(userId);
        vendor.setCreatedAt(LocalDateTime.now());
        vendor.setUpdatedAt(LocalDateTime.now());

        // Normalize empty email to null — prevents unique constraint violations
        if (vendor.getEmail() != null && vendor.getEmail().trim().isEmpty()) {
            vendor.setEmail(null);
        }
        
        // Initialize purchase tracking
        if (vendor.getTotalOrders() == null) vendor.setTotalOrders(0);
        if (vendor.getTotalPurchaseValue() == null) vendor.setTotalPurchaseValue(BigDecimal.ZERO);
        if (vendor.getLastPurchaseAmount() == null) vendor.setLastPurchaseAmount(BigDecimal.ZERO);
        
        if (vendor.getStatus() == null) vendor.setStatus("Active");
        if (vendor.getGroupName() == null) vendor.setGroupName("Others");
        if (vendor.getSubGroupName() == null) vendor.setSubGroupName("General");
        
        VendorEntity savedVendor = vendorRepository.save(vendor);
        // Assign vendor code from DB id — guaranteed unique
        if (savedVendor.getVendorCode() == null || savedVendor.getVendorCode().isEmpty()) {
            savedVendor.setVendorCode(String.format("VEN-%06d", savedVendor.getId()));
            savedVendor = vendorRepository.save(savedVendor);
        }
        log.info("Created vendor: {} by user: {}", savedVendor.getName(), userId);
        
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
        // Normalize empty email to null
        String newEmail = updatedVendor.getEmail();
        existing.setEmail((newEmail != null && newEmail.trim().isEmpty()) ? null : newEmail);
        existing.setPhone(updatedVendor.getPhone());
        existing.setWebsite(updatedVendor.getWebsite());
        existing.setGstNumber(updatedVendor.getGstNumber());
        existing.setAddress(updatedVendor.getAddress());
        existing.setCity(updatedVendor.getCity());
        existing.setState(updatedVendor.getState());
        existing.setDistrict(updatedVendor.getDistrict());
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
        return getStatistics(groupName, subGroupName, projectId, null, null, null, null, null, userId, userRole);
    }

    public VendorStats getStatistics(String groupName, String subGroupName, String projectId,
            String status, String category, String searchTerm,
            String createdAtFromStr, String createdAtToStr,
            Long userId, String userRole) {
        // Normalize parameters
        String groupFilter    = (groupName    != null && !groupName.isEmpty())    ? groupName    : null;
        String subGroupFilter = (subGroupName != null && !subGroupName.isEmpty()) ? subGroupName : null;
        String projectFilter  = (projectId    != null && !projectId.isEmpty())    ? projectId    : null;
        String statusFilter   = (status    != null && !status.trim().isEmpty()    && !"all".equalsIgnoreCase(status.trim()))    ? status.trim()    : null;
        String categoryFilter = (category  != null && !category.trim().isEmpty()  && !"all".equalsIgnoreCase(category.trim()))  ? category.trim()  : null;
        String searchFilter   = (searchTerm != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

        // Parse date range — to-date extended to end-of-day so the full "to" date is included
        java.time.LocalDateTime fromDt = null;
        java.time.LocalDateTime toDt   = null;
        if (createdAtFromStr != null && !createdAtFromStr.isBlank()) {
            try { fromDt = java.time.LocalDate.parse(createdAtFromStr).atStartOfDay(); } catch (Exception ignored) {}
        }
        if (createdAtToStr != null && !createdAtToStr.isBlank()) {
            try { toDt = java.time.LocalDate.parse(createdAtToStr).atTime(23, 59, 59); } catch (Exception ignored) {}
        }

        // Get stats using repository queries — all four now respect the date range
        long totalVendors       = vendorRepository.countByFilters(groupFilter, subGroupFilter, projectFilter, statusFilter, categoryFilter, searchFilter, fromDt, toDt);
        long activeVendors      = vendorRepository.countActiveByFilters(groupFilter, subGroupFilter, projectFilter, statusFilter, categoryFilter, searchFilter, fromDt, toDt);
        Double avgRating        = vendorRepository.getAverageRatingByFilters(groupFilter, subGroupFilter, projectFilter, statusFilter, categoryFilter, searchFilter, fromDt, toDt);
        Double totalPurchaseValue = vendorRepository.getTotalPurchaseValueByFilters(groupFilter, subGroupFilter, projectFilter, statusFilter, categoryFilter, searchFilter, fromDt, toDt);
        long pendingQuotations  = vendorRepository.countPendingQuotationsByFilters(groupFilter, subGroupFilter, projectFilter);

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
        
        // ❌ REMOVE THESE 2 LINES (do NOT increment totalOrders here):
        // Integer currentOrders = vendor.getTotalOrders() != null ? vendor.getTotalOrders() : 0;
        // vendor.setTotalOrders(currentOrders + 1);
        
        vendorRepository.save(vendor);
        log.info("Updated purchase stats for vendor: {} - Total: {}, Orders: {}", 
            vendor.getName(), vendor.getTotalPurchaseValue(), vendor.getTotalOrders());
    }
    
    // Helper methods
    
    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * NOTE: The inline isAdmin boolean in getVendors() already uses startsWith("ACCOUNTS_")
     * correctly. This helper aligns with the same standard for any future callers.
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
    
    // generateVendorCode() removed — vendor code now assigned from DB id after first save
    
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
    
    @Transactional
    public void incrementVendorOrderCount(Long vendorId) {
        if (vendorId == null) {
            log.warn("Cannot update vendor order count - vendorId is null");
            return;
        }
        
        vendorRepository.findById(vendorId).ifPresent(vendor -> {
            Integer currentOrders = vendor.getTotalOrders() != null ? vendor.getTotalOrders() : 0;
            vendor.setTotalOrders(currentOrders + 1);
            vendorRepository.save(vendor);
            log.info("✅ Incremented order count for vendor '{}' (ID: {}): {} total orders", 
                vendor.getName(), vendorId, currentOrders + 1);
        });
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
    
 // ADD TO VendorService.java

    /**
     * Get vendors filtered by group and subgroup
     * Used by Quotations module to show only relevant vendors
     */
    @Transactional(readOnly = true)
    public List<VendorEntity> getVendorsByGroupAndSubGroup(String groupName, String subGroupName) {
        try {
            log.info("Fetching vendors - group: {}, subGroup: {}", groupName, subGroupName);
            
            // Get all active vendors
            List<VendorEntity> vendors = vendorRepository.findByDeletedAtIsNull();
            
            // Filter by group if provided
            if (groupName != null && !groupName.trim().isEmpty()) {
                vendors = vendors.stream()
                        .filter(v -> groupName.equals(v.getGroupName()))
                        .collect(Collectors.toList());
            }
            
            // Filter by subgroup if provided
            if (subGroupName != null && !subGroupName.trim().isEmpty()) {
                vendors = vendors.stream()
                        .filter(v -> subGroupName.equals(v.getSubGroupName()))
                        .collect(Collectors.toList());
            }
            
            log.info("Found {} vendors for group: {}, subGroup: {}", vendors.size(), groupName, subGroupName);
            return vendors;
            
        } catch (Exception e) {
            log.error("Error fetching vendors by group/subgroup", e);
            return new ArrayList<>();
        }
    }
    
    /**
 * ✅ FIXED: Update vendor stats when PO is created
 * Increments order count and adds PO value to total purchase value
 * Called by PurchaseOrderService immediately after creating a PO
 */
@Transactional
public void updateVendorOnPOCreation(Long vendorId, final BigDecimal poTotalValue) {
    if (vendorId == null) {
        log.warn("Cannot update vendor stats - vendorId is null");
        return;
    }
    
    // Use local variable instead of reassigning parameter
    BigDecimal totalValue = (poTotalValue == null) ? BigDecimal.ZERO : poTotalValue;
    
    vendorRepository.findById(vendorId).ifPresent(vendor -> {
        // Increment order count
        Integer currentOrders = vendor.getTotalOrders() != null ? vendor.getTotalOrders() : 0;
        vendor.setTotalOrders(currentOrders + 1);
        
        // Add PO value to total purchase value
        BigDecimal currentTotal = vendor.getTotalPurchaseValue() != null 
                ? vendor.getTotalPurchaseValue() 
                : BigDecimal.ZERO;
        vendor.setTotalPurchaseValue(currentTotal.add(totalValue));
        
        vendorRepository.save(vendor);
        log.info("✅ Updated vendor '{}' (ID: {}) on PO creation - Orders: {}, Total Value: {}", 
            vendor.getName(), vendorId, vendor.getTotalOrders(), vendor.getTotalPurchaseValue());
    });
}

/**
 * ✅ FIXED: Update vendor stats when PO is updated
 * Adjusts total purchase value by the difference between old and new PO values
 * Called by PurchaseOrderService when updating a PO
 */
@Transactional
public void updateVendorOnPOUpdate(Long vendorId, final BigDecimal oldTotal, final BigDecimal newTotal) {
    if (vendorId == null) {
        log.warn("Cannot update vendor stats - vendorId is null");
        return;
    }
    
    // Use local variables instead of reassigning parameters
    BigDecimal oldValue = (oldTotal == null) ? BigDecimal.ZERO : oldTotal;
    BigDecimal newValue = (newTotal == null) ? BigDecimal.ZERO : newTotal;
    
    vendorRepository.findById(vendorId).ifPresent(vendor -> {
        // Calculate difference
        BigDecimal difference = newValue.subtract(oldValue);
        
        // Only update if there's a difference
        if (difference.compareTo(BigDecimal.ZERO) != 0) {
            // Update total purchase value
            BigDecimal currentTotal = vendor.getTotalPurchaseValue() != null 
                    ? vendor.getTotalPurchaseValue() 
                    : BigDecimal.ZERO;
            vendor.setTotalPurchaseValue(currentTotal.add(difference));
            
            vendorRepository.save(vendor);
            log.info("✅ Updated vendor '{}' (ID: {}) on PO update - Difference: {}, New Total: {}", 
                vendor.getName(), vendorId, difference, vendor.getTotalPurchaseValue());
        } else {
            log.info("No change in PO total value for vendor '{}' (ID: {}), skipping update", 
                vendor.getName(), vendorId);
        }
    });
}

/**
 * Called when a PO is soft-deleted.
 * Subtracts the deleted PO's value from vendor totals and decrements order count.
 * Uses recalculation from live PO data to guarantee accuracy.
 */
@Transactional
public void updateVendorOnPODeletion(Long vendorId, BigDecimal deletedPoTotal) {
    if (vendorId == null) {
        log.warn("Cannot update vendor stats on deletion - vendorId is null");
        return;
    }
    // Recalculate from live data to guarantee correctness regardless of prior drift
    recalculateVendorStatsFromPOs(vendorId);
    log.info("✅ Recalculated vendor {} stats after PO deletion (was ₹{})", vendorId, deletedPoTotal);
}

/**
 * Recalculate vendor totalOrders and totalPurchaseValue directly from
 * non-deleted purchase_orders records. Idempotent — safe to call anytime.
 */
@Transactional
public void recalculateVendorStatsFromPOs(Long vendorId) {
    if (vendorId == null) return;
    vendorRepository.findById(vendorId).ifPresent(vendor -> {
        List<com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity> activePOs =
            purchaseOrderRepository.findByVendorId(vendorId);
        int    count = activePOs.size();
        BigDecimal total = activePOs.stream()
            .map(po -> po.getTotalValue() != null ? po.getTotalValue() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        vendor.setTotalOrders(count);
        vendor.setTotalPurchaseValue(total);
        vendorRepository.save(vendor);
        log.info("♻ Recalculated vendor '{}' (ID:{}) → {} orders, ₹{}", vendor.getName(), vendorId, count, total);
    });
}
}