package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PurchaseOrderDropdownWrapper;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderService {
    
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderPdfService purchaseOrderPdfService;
    private final ProjectAccessService projectAccessService;
    private final RoleHierarchyRepo roleHierarchyRepo;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final VendorRepository vendorRepository;
    private final VendorService vendorService;

/**
 * ✅ UPDATED: Get purchase orders with PAYMENT STATUS filter support
 */
@Transactional(readOnly = true)
public Page<PurchaseOrderEntity> getPurchaseOrders(
        String groupName,
        String subGroupName,
        String projectId,
        String status,
        String paymentStatus,
        String searchTerm,
        String documentType,
        Long userId,
        String userRole,
        int page,
        int size,
        String sortBy,
        String sortDirection,
        String orderDateFrom,
        String orderDateTo
) {
    Pageable pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.fromString(sortDirection), sortBy)
    );

    boolean isAdmin = isAdmin(userRole);

    // ── Document-type filter (PURCHASE_ORDER / WORK_ORDER) ──
    // Only engages when a specific type is requested; the default ("all"/blank) view falls
    // through to the existing query fan-out unchanged, so POs and WOs list together with a Type badge.
    String docTypeFilter = (documentType != null && !documentType.isEmpty()
            && !"all".equalsIgnoreCase(documentType)) ? documentType : null;
    if (docTypeFilter != null) {
        String statusFilter   = (status        != null && !status.isEmpty()        && !"all".equalsIgnoreCase(status))        ? status        : null;
        String paymentFilter  = (paymentStatus != null && !paymentStatus.isEmpty() && !"all".equalsIgnoreCase(paymentStatus)) ? paymentStatus : null;
        String groupFilter    = (groupName     != null && !groupName.isEmpty())     ? groupName     : null;
        String subGroupFilter = (subGroupName  != null && !subGroupName.isEmpty())  ? subGroupName  : null;
        String projectFilter  = (projectId     != null && !projectId.isEmpty())     ? projectId     : null;
        String searchFilter   = (searchTerm    != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

        LocalDateTime dateFrom = null, dateTo = null;
        try {
            if (orderDateFrom != null && !orderDateFrom.isEmpty()) dateFrom = LocalDate.parse(orderDateFrom).atStartOfDay();
            if (orderDateTo   != null && !orderDateTo.isEmpty())   dateTo   = LocalDate.parse(orderDateTo).atTime(23, 59, 59);
        } catch (Exception e) {
            log.warn("Invalid date filter values: from={}, to={}", orderDateFrom, orderDateTo);
        }

        // Access scope: admin = all; non-admin = restrict to accessible project IDs
        // (unless a specific, already-granted project is requested).
        List<String> accessibleProjectIds = null;
        if (!isAdmin) {
            boolean hasProjectGrant = projectFilter != null
                    && projectAccessService.canAccessProject(projectFilter, userId, userRole);
            if (!hasProjectGrant) {
                accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
                if (accessibleProjectIds == null || accessibleProjectIds.isEmpty()) {
                    return Page.empty(pageable);
                }
            }
        }

        final String fDocType = docTypeFilter, fStatus = statusFilter, fPayment = paymentFilter,
                     fGroup = groupFilter, fSubGroup = subGroupFilter, fProject = projectFilter, fSearch = searchFilter;
        final LocalDateTime fFrom = dateFrom, fTo = dateTo;
        final List<String> fProjectIds = accessibleProjectIds;

        org.springframework.data.jpa.domain.Specification<PurchaseOrderEntity> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            ps.add(cb.isNull(root.get("deletedAt")));
            ps.add(cb.equal(root.get("documentType"), fDocType));
            if (fStatus   != null) ps.add(cb.equal(root.get("status"), fStatus));
            if (fPayment  != null) ps.add(cb.equal(root.get("paymentStatus"), fPayment));
            if (fGroup    != null) ps.add(cb.equal(root.get("groupName"), fGroup));
            if (fSubGroup != null) ps.add(cb.equal(root.get("subGroupName"), fSubGroup));
            if (fProject  != null) ps.add(cb.equal(root.get("projectId"), fProject));
            if (fProjectIds != null) ps.add(root.get("projectId").in(fProjectIds));
            if (fFrom != null) ps.add(cb.greaterThanOrEqualTo(root.get("orderDate"), fFrom));
            if (fTo   != null) ps.add(cb.lessThanOrEqualTo(root.get("orderDate"), fTo));
            if (fSearch != null) {
                String like = "%" + fSearch.toLowerCase() + "%";
                ps.add(cb.or(
                    cb.like(cb.lower(root.get("poNo")), like),
                    cb.like(cb.lower(root.get("rfqId")), like),
                    cb.like(cb.lower(root.get("poRefId")), like),
                    cb.like(cb.lower(root.get("vendorName")), like)
                ));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return purchaseOrderRepository.findAll(spec, pageable);
    }

    // ── Date-range filter: use the comprehensive query that handles all combinations ──
    // This runs when either date bound is supplied, regardless of other filter state.
    if (orderDateFrom != null && !orderDateFrom.isEmpty()
            || orderDateTo != null && !orderDateTo.isEmpty()) {

        LocalDateTime dateFrom = null;
        LocalDateTime dateTo   = null;
        try {
            if (orderDateFrom != null && !orderDateFrom.isEmpty())
                dateFrom = java.time.LocalDate.parse(orderDateFrom).atStartOfDay();
            if (orderDateTo != null && !orderDateTo.isEmpty())
                dateTo = java.time.LocalDate.parse(orderDateTo).atTime(23, 59, 59);
        } catch (Exception e) {
            log.warn("Invalid date filter values: from={}, to={}", orderDateFrom, orderDateTo);
        }

        String statusFilter      = (status        != null && !status.isEmpty()        && !"all".equalsIgnoreCase(status))        ? status        : null;
        String paymentFilter     = (paymentStatus != null && !paymentStatus.isEmpty() && !"all".equalsIgnoreCase(paymentStatus)) ? paymentStatus : null;
        String groupFilter       = (groupName     != null && !groupName.isEmpty())     ? groupName     : null;
        String subGroupFilter    = (subGroupName  != null && !subGroupName.isEmpty())  ? subGroupName  : null;
        String projectFilter     = (projectId     != null && !projectId.isEmpty())     ? projectId     : null;
        String searchFilter      = (searchTerm    != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

        // Admin: full scope; non-admin: restrict to accessible project IDs
        if (isAdmin) {
            return purchaseOrderRepository.findWithDateFilter(
                groupFilter, subGroupFilter, projectFilter,
                statusFilter, paymentFilter, searchFilter,
                dateFrom, dateTo, pageable
            );
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                return purchaseOrderRepository.findWithDateFilterAndProjectIds(
                    accessibleProjectIds,
                    groupFilter, subGroupFilter,
                    statusFilter, paymentFilter, searchFilter,
                    dateFrom, dateTo, pageable
                );
            }
            // No project grants — scope to specific project if supplied, else empty
            if (projectFilter != null) {
                return purchaseOrderRepository.findWithDateFilter(
                    null, null, projectFilter,
                    statusFilter, paymentFilter, searchFilter,
                    dateFrom, dateTo, pageable
                );
            }
            return Page.empty(pageable);
        }
    }

    // User with a project access grant sees all POs for that project
    boolean hasProjectGrant = !isAdmin
            && projectId != null && !projectId.isEmpty()
            && projectAccessService.canAccessProject(projectId, userId, userRole);

    // Search: applies across all text fields AND respects status / paymentStatus / group / project scope.
    if (searchTerm != null && !searchTerm.trim().isEmpty()) {
        String statusFilter      = (status        != null && !status.isEmpty()        && !"all".equalsIgnoreCase(status))        ? status        : null;
        String paymentFilter     = (paymentStatus != null && !paymentStatus.isEmpty() && !"all".equalsIgnoreCase(paymentStatus)) ? paymentStatus : null;
        String groupFilter       = (groupName     != null && !groupName.isEmpty())     ? groupName     : null;
        String subGroupFilter    = (subGroupName  != null && !subGroupName.isEmpty())  ? subGroupName  : null;
        String projectFilter     = (projectId     != null && !projectId.isEmpty())     ? projectId     : null;

        if (isAdmin || projectFilter != null) {
            return purchaseOrderRepository.search(
                searchTerm.trim(), statusFilter, paymentFilter, groupFilter, subGroupFilter, projectFilter, pageable
            );
        } else {
            // Non-admin: restrict to accessible project IDs
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                return purchaseOrderRepository.searchWithProjectIds(
                    accessibleProjectIds, searchTerm.trim(), statusFilter, paymentFilter, groupFilter, subGroupFilter, pageable
                );
            }
            return Page.empty(pageable);
        }
    }

    // ✅ NEW: Combined filtering logic
    if (isAdmin || hasProjectGrant) {
        // Handle combined filters
        if (paymentStatus != null && !paymentStatus.isEmpty()) {
            // Payment status + other filters
            if (projectId != null && !projectId.isEmpty()) {
                if (status != null && !status.isEmpty()) {
                    // Project + Status + Payment
                    return purchaseOrderRepository.findByProjectIdAndStatusAndPaymentStatus(
                        projectId, status, paymentStatus, pageable
                    );
                }
                // Project + Payment
                return purchaseOrderRepository.findByProjectIdAndPaymentStatus(
                    projectId, paymentStatus, pageable
                );
            }
            
            if (subGroupName != null && !subGroupName.isEmpty()) {
                if (status != null && !status.isEmpty()) {
                    // Group + SubGroup + Status + Payment
                    return purchaseOrderRepository.findByGroupSubGroupStatusAndPayment(
                        groupName, subGroupName, status, paymentStatus, pageable
                    );
                }
                // Group + SubGroup + Payment
                return purchaseOrderRepository.findByGroupSubGroupAndPayment(
                    groupName, subGroupName, paymentStatus, pageable
                );
            }
            
            if (groupName != null && !groupName.isEmpty()) {
                if (status != null && !status.isEmpty()) {
                    // Group + Status + Payment
                    return purchaseOrderRepository.findByGroupStatusAndPayment(
                        groupName, status, paymentStatus, pageable
                    );
                }
                // Group + Payment
                return purchaseOrderRepository.findByGroupAndPayment(
                    groupName, paymentStatus, pageable
                );
            }
            
            if (status != null && !status.isEmpty()) {
                // Status + Payment only
                return purchaseOrderRepository.findByStatusAndPaymentStatus(
                    status, paymentStatus, pageable
                );
            }
            
            // Payment status only
            return purchaseOrderRepository.findByPaymentStatus(paymentStatus, pageable);
        }
        
        // Existing logic for other filters (without payment status)
        if (projectId != null && !projectId.isEmpty()) {
            if (status != null && !status.isEmpty()) {
                return purchaseOrderRepository.findByProjectIdAndStatus(projectId, status, pageable);
            }
            return purchaseOrderRepository.findByProjectId(projectId, pageable);
        }
        
        if (subGroupName != null && !subGroupName.isEmpty()) {
            if (status != null && !status.isEmpty()) {
                return purchaseOrderRepository.findByGroupSubGroupAndStatus(
                    groupName, subGroupName, status, pageable
                );
            }
            return purchaseOrderRepository.findByGroupAndSubGroup(groupName, subGroupName, pageable);
        }
        
        if (groupName != null && !groupName.isEmpty()) {
            if (status != null && !status.isEmpty()) {
                return purchaseOrderRepository.findByGroupAndStatus(groupName, status, pageable);
            }
            return purchaseOrderRepository.findByGroupName(groupName, pageable);
        }
        
        if (status != null && !status.isEmpty()) {
            return purchaseOrderRepository.findByStatus(status, pageable);
        }
        
        return purchaseOrderRepository.findAllActive(pageable);
        
    } else {
        // Non-admin: always scope to accessible project IDs from project_access table.
        // Previously used UserAccess (createdBy) queries for paymentStatus filter — that was wrong.
        List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
        boolean hasAccessibleProjects = accessibleProjectIds != null && !accessibleProjectIds.isEmpty();

        if (paymentStatus != null && !paymentStatus.isEmpty()) {
            // Specific project requested — check access and query directly
            if (projectId != null && !projectId.isEmpty()) {
                return purchaseOrderRepository.findByProjectIdAndPaymentStatusOnly(
                    projectId, paymentStatus, pageable
                );
            }
            if (hasAccessibleProjects) {
                if (subGroupName != null && !subGroupName.isEmpty()) {
                    return purchaseOrderRepository.findByGroupSubGroupPaymentAndProjectIds(
                        groupName, subGroupName, paymentStatus, accessibleProjectIds, pageable
                    );
                }
                if (groupName != null && !groupName.isEmpty()) {
                    return purchaseOrderRepository.findByGroupPaymentAndProjectIds(
                        groupName, paymentStatus, accessibleProjectIds, pageable
                    );
                }
                return purchaseOrderRepository.findByPaymentStatusAndProjectIds(
                    paymentStatus, accessibleProjectIds, pageable
                );
            }
            return Page.empty(pageable);
        }

        // Non-payment-status path: scope to accessible project IDs
        if (projectId != null && !projectId.isEmpty()) {
            // Specific project — query directly (access already checked at controller level)
            if (status != null && !status.isEmpty()) {
                return purchaseOrderRepository.findByProjectIdAndStatus(projectId, status, pageable);
            }
            return purchaseOrderRepository.findByProjectId(projectId, pageable);
        }
        if (hasAccessibleProjects) {
            if (subGroupName != null && !subGroupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupSubGroupAndAccessibleProjects(groupName, subGroupName, accessibleProjectIds, pageable);
            }
            if (groupName != null && !groupName.isEmpty()) {
                return purchaseOrderRepository.findByGroupNameAndAccessibleProjects(groupName, accessibleProjectIds, pageable);
            }
            return purchaseOrderRepository.findByAccessibleProjects(accessibleProjectIds, pageable);
        }
        // No project grants — return empty rather than falling back to createdBy
        // which would show POs the user created but isn't formally granted access to.
        return Page.empty(pageable);
    }
}
    
    /**
     * Get PO by ID
     */
    @Transactional(readOnly = true)
    public PurchaseOrderEntity getPurchaseOrderById(Long id) {
        PurchaseOrderEntity po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Purchase Order not found with id: " + id));
        enrichVendorName(po);
        return po;
    }

    /**
     * Always fetch the live vendor name (and contact) from the vendors table when
     * vendorId is set.  This ensures the PO response always reflects the current
     * vendor record even if the stored vendorName column is stale or blank.
     * Falls back to the stored vendorName only when the vendor row cannot be found.
     */
    public void enrichVendorName(PurchaseOrderEntity po) {
        if (po.getVendorId() != null) {
            vendorRepository.findById(po.getVendorId()).ifPresent(v -> {
                // Always override with the live name from the vendors table
                po.setVendorName(v.getName());
                // Enrich vendor code for display
                po.setVendorCode(v.getVendorCode());
                // Fill contact only when missing on the PO
                if (po.getVendorContact() == null || po.getVendorContact().isBlank()) {
                    po.setVendorContact(v.getPhone());
                }
            });
        }
    }
    
    /**
     * ✅ UPDATED: Create PO from quotation with custom data
     * Creates vendor IMMEDIATELY if new vendor, links vendorId right away
     */
    /**
     * Per-line ordering status for a quotation: quoted qty, qty already ordered across ALL
     * (non-cancelled) POs raised under this quotation, and the remaining qty. Matching is by
     * item name scoped to the quotation (mirrors the order-book allocatedQty pattern), so no
     * line-item FK is needed. Two quotation lines sharing an item name would share a cap (rare).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getQuotationRemaining(Long quotationId) {
        List<QuotationItemEntity> quotationItems = quotationItemRepository.findByQuotationId(quotationId);
        Map<String, BigDecimal> orderedByName = new java.util.HashMap<>();
        for (Object[] row : purchaseOrderItemRepository.sumOrderedQtyByItemForQuotation(quotationId)) {
            if (row == null || row[0] == null) continue;
            BigDecimal qty = row[1] != null ? new BigDecimal(row[1].toString()) : BigDecimal.ZERO;
            orderedByName.merge(row[0].toString(), qty, BigDecimal::add);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (QuotationItemEntity qi : quotationItems) {
            BigDecimal quoted = qi.getQuantity() != null ? qi.getQuantity() : BigDecimal.ZERO;
            BigDecimal ordered = orderedByName.getOrDefault(qi.getItemName(), BigDecimal.ZERO);
            BigDecimal remaining = quoted.subtract(ordered);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("quotationItemId", qi.getId());
            m.put("itemName", qi.getItemName());
            m.put("quotedQty", quoted);
            m.put("orderedQty", ordered);
            m.put("remainingQty", remaining);
            result.add(m);
        }
        return result;
    }

    /**
     * Recompute a quotation's ordering status from the live PO quantities and persist it.
     *
     * The quotation has no stored "ordered/received qty" column — the ordering state lives in
     * {@code QuotationEntity.status} ("Approved" → "Partially Ordered" → "PO Created"), while the
     * actual numbers are derived on the fly from PO line items by {@link #getQuotationRemaining}.
     * This keeps the stored status in sync whenever a PO under the quotation is created, EDITED,
     * or removed — previously only the create path did this, so decreasing a PO's line quantities
     * on edit (or deleting the PO) left a stale "PO Created" status behind.
     *
     * Only the ordering-driven statuses are managed; terminal/unrelated states
     * (Rejected, Expired, New, Under Review, Shortlisted) are left untouched.
     *   - every line fully ordered            → "PO Created"
     *   - some (but not all) qty ordered      → "Partially Ordered"
     *   - nothing ordered (e.g. PO deleted)   → "Approved" (and poId cleared)
     */
    public void syncQuotationOrderingStatus(Long quotationId) {
        if (quotationId == null) return;
        QuotationEntity quotation = quotationRepository.findById(quotationId).orElse(null);
        if (quotation == null) return;

        String current = quotation.getStatus();
        boolean orderingStatus = "Approved".equals(current)
                || "Partially Ordered".equals(current)
                || "PO Created".equals(current);
        if (!orderingStatus) {
            // e.g. Rejected/Expired/New — don't resurrect the ordering flow
            return;
        }

        List<Map<String, Object>> remaining = getQuotationRemaining(quotationId);
        boolean hasLines = !remaining.isEmpty();
        boolean anyOrdered = remaining.stream()
                .anyMatch(r -> ((BigDecimal) r.get("orderedQty")).compareTo(BigDecimal.ZERO) > 0);
        boolean fullyOrdered = hasLines && remaining.stream()
                .allMatch(r -> ((BigDecimal) r.get("remainingQty")).compareTo(BigDecimal.ZERO) == 0);

        String newStatus;
        if (fullyOrdered) {
            newStatus = "PO Created";
        } else if (anyOrdered) {
            newStatus = "Partially Ordered";
        } else {
            newStatus = "Approved";
            quotation.setPoId(null); // no live PO left under this quotation
        }

        if (!newStatus.equals(current)) {
            log.info("Quotation {} ordering status {} → {}", quotationId, current, newStatus);
        }
        quotation.setStatus(newStatus);
        quotationRepository.save(quotation);
    }

    @Transactional
    public PurchaseOrderEntity createPOFromQuotationWithCustomData(
            Long quotationId,
            Long userId,
            Long vendorId,
            String vendorName,
            String vendorContact,
            String groupName,
            String subGroupName,
            String projectId,
            String rfqId,
            String poRefId,
            String orderDateStr,
            String expectedDeliveryStr,
            String paymentTerms,
            String shippingAddress,
            String notes,
            String status,
            String documentType,
            List<Map<String, Object>> itemsData
    ) {
        try {
            log.info("Creating PO from quotation {} with custom data", quotationId);

            // Get quotation
            QuotationEntity quotation = quotationRepository.findById(quotationId)
                    .orElseThrow(() -> new RuntimeException("Quotation not found"));

            // Allow raising POs while the quotation is Approved OR still Partially Ordered
            // (multiple POs may be raised until every line's quoted qty is exhausted).
            if (!"Approved".equals(quotation.getStatus()) && !"Partially Ordered".equals(quotation.getStatus())) {
                throw new RuntimeException("Only approved quotations can be converted to PO");
            }

            // Enforce cumulative per-line cap: for each item, the qty ordered across ALL POs
            // under this quotation must not exceed the quoted qty. Computed from prior POs only
            // (this PO not saved yet).
            Map<String, BigDecimal> remainingByName = new java.util.HashMap<>();
            for (Map<String, Object> r : getQuotationRemaining(quotationId)) {
                remainingByName.merge((String) r.get("itemName"), (BigDecimal) r.get("remainingQty"), BigDecimal::add);
            }
            for (Map<String, Object> itemData : itemsData) {
                String name = itemData.get("itemName") != null ? itemData.get("itemName").toString() : "";
                BigDecimal req = safeToBigDecimal(itemData.get("quantity"), BigDecimal.ZERO);
                BigDecimal rem = remainingByName.getOrDefault(name, BigDecimal.ZERO);
                if (req.compareTo(rem) > 0) {
                    throw new RuntimeException("Cannot order " + req.stripTrailingZeros().toPlainString()
                        + " of '" + name + "' — only " + rem.stripTrailingZeros().toPlainString()
                        + " remaining under this quotation.");
                }
                remainingByName.put(name, rem.subtract(req)); // cap duplicate lines within this PO too
            }

            // ✅ CREATE VENDOR IMMEDIATELY if new vendor
            Long finalVendorId = vendorId;
            if (vendorId == null && vendorName != null && !vendorName.trim().isEmpty()) {
                log.info("Creating new vendor immediately: {}", vendorName);
                finalVendorId = createVendorNow(vendorName, vendorContact, groupName, subGroupName, projectId, userId);
                log.info("✅ Created vendor with ID: {}", finalVendorId);
            }
            
            // Parse dates
            LocalDate orderDate = LocalDate.parse(orderDateStr);
            LocalDate expectedDelivery = LocalDate.parse(expectedDeliveryStr);
            
            // Create PO WITHOUT items first
            PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                    .poNo("__TEMP_PO_" + System.nanoTime() + "__")
                    .poRefId(poRefId != null && !poRefId.trim().isEmpty() ? poRefId.trim() : null)
                    .vendorId(finalVendorId) // ✅ Use the newly created vendorId
                    .vendorName(vendorName)
                    .vendorContact(vendorContact)
                    .quotationId(quotationId)
                    .rfqId(rfqId != null ? rfqId : quotation.getRfqId())
                    .orderDate(orderDate.atStartOfDay())
                    .expectedDelivery(expectedDelivery.atStartOfDay())
                    .status(status != null && !status.trim().isEmpty() ? status : "Draft")
                    .documentType(documentType != null && !documentType.isBlank() ? documentType : "PURCHASE_ORDER")
                    .paymentStatus("Pending")
                    .groupName(groupName != null ? groupName : quotation.getGroupName())
                    .subGroupName(subGroupName != null ? subGroupName : quotation.getSubGroupName())
                    .projectId(projectId != null ? projectId : quotation.getProjectId())
                    .deliveryAddress(shippingAddress)
                    .paymentTerms(paymentTerms)
                    .notes(notes)
                    .category(quotation.getCategory())
                    .createdBy(userId)
                    .totalItemsOrdered(0)
                    .totalItemsDelivered(0)
                    .build();
            PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
            if (savedPO.getPoNo().startsWith("__TEMP_PO_")) {
                savedPO.setPoNo(buildPoCode(savedPO.getId(), savedPO.getDocumentType()));
                savedPO = purchaseOrderRepository.save(savedPO);
            }
            log.info("Saved PO with ID: {} poNo: {} and vendorId: {}", savedPO.getId(), savedPO.getPoNo(), savedPO.getVendorId());
            
            // Create PO items
            BigDecimal totalValue = BigDecimal.ZERO;
            int totalItemsOrdered = 0;
            List<PurchaseOrderItemEntity> poItems = new ArrayList<>();
            
            for (int i = 0; i < itemsData.size(); i++) {
                Map<String, Object> itemData = itemsData.get(i);
                
                BigDecimal quantity = safeToBigDecimal(itemData.get("quantity"), BigDecimal.ONE);
                BigDecimal unitPrice = safeToBigDecimal(itemData.get("unitPrice"));
                BigDecimal gst = safeToBigDecimal(itemData.get("gst"), new BigDecimal("18"));
                BigDecimal discount = itemData.containsKey("discount") 
                    ? new BigDecimal(itemData.get("discount").toString()) 
                    : BigDecimal.ZERO;
                
                // Calculate line total
                BigDecimal lineSubtotal = quantity.multiply(unitPrice);
                BigDecimal discountAmount = lineSubtotal.multiply(discount)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal taxableAmount = lineSubtotal.subtract(discountAmount);
                BigDecimal gstAmount = taxableAmount.multiply(gst)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = taxableAmount.add(gstAmount);
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Invalid quantity for item: {}, using 1", itemData.get("itemName"));
                    quantity = BigDecimal.ONE;
                }if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Invalid quantity for item: {}, using 1", itemData.get("itemName"));
                    quantity = BigDecimal.ONE;
                }
                PurchaseOrderItemEntity poItem = PurchaseOrderItemEntity.builder()
                        .purchaseOrder(savedPO)
                        .lineNo(i + 1)
                        .itemName(itemData.get("itemName").toString())
                        .unit(itemData.get("unit") != null ? itemData.get("unit").toString() : "Nos")
                        .hsnCode(itemData.get("hsnCode") != null ? itemData.get("hsnCode").toString() : null)
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
            
            // ✅ ADD THESE LINES - Update vendor stats: increment order count
            if (finalVendorId != null) {
                vendorService.updateVendorOnPOCreation(finalVendorId, totalValue);
            }

            // Link this PO to the quotation, then recompute its ordering status from the
            // remaining quantities across all POs under it (recomputed AFTER saving this PO's
            // items). Shared with the edit/delete paths so the status always stays in sync.
            quotation.setPoId(savedPO.getId());
            quotationRepository.save(quotation);
            syncQuotationOrderingStatus(quotationId);

            log.info("✅ Created PO {} with vendorId {} from quotation {} with {} items, total: {}",
                savedPO.getPoNo(), savedPO.getVendorId(), quotationId, itemsData.size(), totalValue);

            return savedPO;
            
        } catch (Exception e) {
            log.error("Error creating PO from quotation: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create PO: " + e.getMessage());
        }
    }

    /**
     * ✅ UPDATED: Create PO from order books (no quotation)
     * Creates vendor IMMEDIATELY if new vendor
     */
    @Transactional
    public PurchaseOrderEntity createPOFromOrderBooks(
            Long userId,
            Long vendorId,
            String vendorName,
            String vendorContact,
            String groupName,
            String subGroupName,
            String projectId,
            String poRefId,
            String orderDateStr,
            String expectedDeliveryStr,
            String paymentTerms,
            String shippingAddress,
            String notes,
            String status,
            String documentType,
            String vendorCategory,
            String vendorType,
            List<Map<String, Object>> itemsData
    ) {
        try {
            log.info("Creating PO from order books for project {}", projectId);

            // ✅ CREATE VENDOR IMMEDIATELY if new vendor
            Long finalVendorId = vendorId;
            if (vendorId == null && vendorName != null && !vendorName.trim().isEmpty()) {
                log.info("Creating new vendor immediately: {}", vendorName);
                finalVendorId = createVendorNow(vendorName, vendorContact, groupName, subGroupName, projectId, vendorCategory, vendorType, userId);
                log.info("✅ Created vendor with ID: {}", finalVendorId);
            }
            
            // Parse dates
            LocalDate orderDate = LocalDate.parse(orderDateStr);
            LocalDate expectedDelivery = LocalDate.parse(expectedDeliveryStr);
            
            // Create PO WITHOUT items first
            PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                    .poNo("__TEMP_PO_" + System.nanoTime() + "__")
                    .poRefId(poRefId != null && !poRefId.trim().isEmpty() ? poRefId.trim() : null)
                    .vendorId(finalVendorId) // ✅ Use the newly created vendorId
                    .vendorName(vendorName)
                    .vendorContact(vendorContact)
                    .quotationId(null) // No quotation
                    .rfqId(null)
                    .orderDate(orderDate.atStartOfDay())
                    .expectedDelivery(expectedDelivery.atStartOfDay())
                    .status(status != null && !status.trim().isEmpty() ? status : "Draft")
                    .paymentStatus("Pending")
                    .documentType(documentType != null && !documentType.isBlank() ? documentType : "PURCHASE_ORDER")
                    .groupName(groupName)
                    .subGroupName(subGroupName)
                    .projectId(projectId)
                    .deliveryAddress(shippingAddress)
                    .paymentTerms(paymentTerms)
                    .notes(notes)
                    .createdBy(userId)
                    .totalItemsOrdered(0)
                    .totalItemsDelivered(0)
                    .build();
            
            // Save PO FIRST — DB assigns auto-increment id
            PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
            if (savedPO.getPoNo().startsWith("__TEMP_PO_")) {
                savedPO.setPoNo(buildPoCode(savedPO.getId(), savedPO.getDocumentType()));
                savedPO = purchaseOrderRepository.save(savedPO);
            }
            log.info("Saved PO from order books with ID: {} poNo: {} and vendorId: {}", savedPO.getId(), savedPO.getPoNo(), savedPO.getVendorId());
            
            // Create PO items
            BigDecimal totalValue = BigDecimal.ZERO;
            int totalItemsOrdered = 0;
            List<PurchaseOrderItemEntity> poItems = new ArrayList<>();
            
            for (int i = 0; i < itemsData.size(); i++) {
                Map<String, Object> itemData = itemsData.get(i);
                
                BigDecimal quantity = safeToBigDecimal(itemData.get("quantity"), BigDecimal.ONE);
                BigDecimal unitPrice = safeToBigDecimal(itemData.get("unitPrice"));
                BigDecimal gst = safeToBigDecimal(itemData.get("gst"), new BigDecimal("18"));
                BigDecimal discount = itemData.containsKey("discount") 
                    ? new BigDecimal(itemData.get("discount").toString()) 
                    : BigDecimal.ZERO;
                
                // Calculate line total
                BigDecimal lineSubtotal = quantity.multiply(unitPrice);
                BigDecimal discountAmount = lineSubtotal.multiply(discount)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal taxableAmount = lineSubtotal.subtract(discountAmount);
                BigDecimal gstAmount = taxableAmount.multiply(gst)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal lineTotal = taxableAmount.add(gstAmount);
                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Invalid quantity for item: {}, using 1", itemData.get("itemName"));
                    quantity = BigDecimal.ONE;
                }
                PurchaseOrderItemEntity poItem = PurchaseOrderItemEntity.builder()
                        .purchaseOrder(savedPO)
                        .lineNo(i + 1)
                        .itemName(itemData.get("itemName").toString())
                        .unit(itemData.get("unit") != null ? itemData.get("unit").toString() : "Nos")
                        .hsnCode(itemData.get("hsnCode") != null ? itemData.get("hsnCode").toString() : null)
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
            
            // Update PO totals
            List<PurchaseOrderItemEntity> savedItems = purchaseOrderItemRepository.saveAll(poItems);
            log.info("Saved {} PO items from order books", savedItems.size());
            
            // Update PO totals
            savedPO.setTotalValue(totalValue.setScale(2, RoundingMode.HALF_UP));
            savedPO.setTotalItemsOrdered(totalItemsOrdered);
            savedPO.setTotalItemsDelivered(0);
            savedPO.setItems(savedItems);
            purchaseOrderRepository.save(savedPO);
            
            // ✅ ADD THESE LINES - Update vendor stats: increment order count
            if (finalVendorId != null) {
                vendorService.updateVendorOnPOCreation(finalVendorId, totalValue);
            }
            
            log.info("✅ Created PO {} with vendorId {} from order books with {} items, total: {}", 
                savedPO.getPoNo(), savedPO.getVendorId(), itemsData.size(), totalValue);
            
            return savedPO;
            
        } catch (Exception e) {
            log.error("Error creating PO from order books: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create PO: " + e.getMessage());
        }
    }

    /**
     * ✅ NEW METHOD: Create vendor immediately and return its ID
     */
    private Long createVendorNow(
            String vendorName,
            String vendorContact,
            String groupName,
            String subGroupName,
            String projectId,
            Long userId
    ) {
        return createVendorNow(vendorName, vendorContact, groupName, subGroupName, projectId, null, null, userId);
    }

    private Long createVendorNow(
            String vendorName,
            String vendorContact,
            String groupName,
            String subGroupName,
            String projectId,
            String vendorCategory,
            String vendorType,
            Long userId
    ) {
        try {
            // Check if vendor already exists with this contact
            if (vendorContact != null && !vendorContact.trim().isEmpty()) {
                Optional<VendorEntity> existingVendor = vendorRepository.findByPhone(vendorContact.trim());
                if (existingVendor.isPresent()) {
                    log.info("Vendor already exists with contact {}, returning existing vendor ID: {}",
                        vendorContact, existingVendor.get().getId());
                    return existingVendor.get().getId();
                }
            }

            // Handle null project_id
            String finalProjectId = projectId;
            if (projectId == null || projectId.trim().isEmpty()) {
                finalProjectId = "DEFAULT";
            }

            VendorEntity vendor = VendorEntity.builder()
                    .name(vendorName)
                    .phone(vendorContact)

                    .rating(0)
                    .status("Active")
                    .groupName(groupName != null ? groupName : "Others")
                    .subGroupName(subGroupName != null ? subGroupName : "General")
                    .projectId(finalProjectId)
                    .category(vendorCategory != null && !vendorCategory.trim().isEmpty() ? vendorCategory.trim() : "General")
                    .vendorType(vendorType != null && !vendorType.trim().isEmpty() ? vendorType.trim() : null)
                    .totalOrders(0)
                    .totalPurchaseValue(BigDecimal.ZERO)
                    .createdBy(userId)
                    .build();

            VendorEntity savedVendor = vendorRepository.save(vendor);
            savedVendor.setVendorCode(String.format("VEN-%06d", savedVendor.getId()));
            savedVendor = vendorRepository.save(savedVendor);
            log.info("✅ Created new vendor {} with ID: {}", vendorName, savedVendor.getId());

            return savedVendor.getId();

        } catch (Exception e) {
            log.error("Error creating vendor immediately: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create vendor: " + e.getMessage());
        }
    }
    
    /**
     * Update PO status - REMOVED vendor creation logic (now done at PO creation)
     */
    @Transactional
    public PurchaseOrderEntity updateStatus(Long id, String newStatus, Long userId) {
        PurchaseOrderEntity po = getPurchaseOrderById(id);
        
        String oldStatus = po.getStatus();

        // Guard: manually setting status to "Delivered" requires all items to be fully delivered
        if ("Delivered".equals(newStatus) && !"Delivered".equals(oldStatus)) {
            List<PurchaseOrderItemEntity> items = po.getItems();
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("Cannot mark as Delivered: purchase order has no items.");
            }
            List<String> pendingItems = items.stream()
                .filter(it -> {
                    BigDecimal ordered   = it.getQuantity()    != null ? it.getQuantity()    : BigDecimal.ZERO;
                    BigDecimal delivered = it.getDeliveredQty() != null ? it.getDeliveredQty() : BigDecimal.ZERO;
                    return delivered.compareTo(ordered) < 0;
                })
                .map(it -> it.getItemName() != null ? it.getItemName() : "Item #" + it.getId())
                .collect(java.util.stream.Collectors.toList());

            if (!pendingItems.isEmpty()) {
                String itemList = pendingItems.size() <= 3
                    ? String.join(", ", pendingItems)
                    : String.join(", ", pendingItems.subList(0, 3)) + " and " + (pendingItems.size() - 3) + " more";
                throw new IllegalArgumentException(
                    "Cannot mark as Delivered: the following items are not fully delivered — " + itemList +
                    ". Please complete item deliveries before marking this PO as Delivered.");
            }
        }

        po.setStatus(newStatus);
        
        if ("Approved".equals(newStatus)) {
            po.setApprovedBy(userId);
        }
        
//        if ("Delivered".equals(newStatus) && !"Delivered".equals(oldStatus)) {
//            updateVendorStatsAfterDelivery(po);
//        }
        
        log.info("Updated PO {} status from {} to {}", po.getPoNo(), oldStatus, newStatus);
        return purchaseOrderRepository.save(po);
    }
    
    /**
     * Soft delete PO
     */
    @Transactional
    public void deletePurchaseOrder(Long id) {
        PurchaseOrderEntity po = getPurchaseOrderById(id);
        BigDecimal poTotal = po.getTotalValue();
        Long vendorId = po.getVendorId();
        Long quotationId = po.getQuotationId();

        po.setDeletedAt(LocalDateTime.now());
        po.setStatus("Cancelled");
        purchaseOrderRepository.save(po);

        // Recalculate vendor stats so totals reflect the deletion immediately
        if (vendorId != null) {
            vendorService.updateVendorOnPODeletion(vendorId, poTotal);
        }

        // Roll the linked quotation's ordering status back now that this PO is cancelled
        // (getQuotationRemaining excludes cancelled/deleted POs). Without this, the quotation
        // would stay stuck on "PO Created"/"Partially Ordered" after its PO was removed.
        if (quotationId != null) {
            syncQuotationOrderingStatus(quotationId);
        }

        log.info("Soft deleted PO: {} (vendorId={}, value={})", po.getPoNo(), vendorId, poTotal);
    }
    
    /**
     * Get POs by vendor
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderEntity> getPurchaseOrdersByVendor(Long vendorId) {
        return purchaseOrderRepository.findByVendorIdOrderByOrderDateDesc(vendorId);
    }

    /**
     * Get POs by vendor (supports both vendorId and vendorName).
     * Fixed: properly applies group/subgroup filters even without a projectId,
     * and always also searches by vendorName so that POs created via the
     * "new vendor" flow (vendorId = null, vendorName set) are included.
     */
    public List<Map<String, Object>> getPurchaseOrdersByVendor1(
            Long vendorId,
            String vendorName,
            String groupName,
            String subGroupName,
            String projectId
    ) {
        // Collect POs matched by vendorId (registered vendors)
        List<PurchaseOrderEntity> byId = new ArrayList<>();
        if (vendorId != null) {
            if (projectId != null && !projectId.isEmpty()) {
                byId = purchaseOrderRepository.findByVendorIdAndProjectId(vendorId, projectId);
            } else {
                byId = purchaseOrderRepository.findByVendorIdOrderByOrderDateDesc(vendorId);
            }
        }

        // Also collect POs matched by vendorName (covers "new vendor" POs where vendorId is null,
        // and handles the case where PO still has only vendorName stored).
        // If vendorName was not provided directly, resolve it from the vendor record.
        List<PurchaseOrderEntity> byName = new ArrayList<>();
        String resolvedVendorName = vendorName;
        if ((resolvedVendorName == null || resolvedVendorName.trim().isEmpty()) && vendorId != null) {
            resolvedVendorName = vendorRepository.findById(vendorId)
                    .map(v -> v.getName())
                    .orElse(null);
        }
        if (resolvedVendorName != null && !resolvedVendorName.trim().isEmpty()) {
            if (projectId != null && !projectId.isEmpty()) {
                byName = purchaseOrderRepository.findByVendorNameAndProjectId(resolvedVendorName, projectId);
            } else {
                byName = purchaseOrderRepository.findByVendorNameOrderByOrderDateDesc(resolvedVendorName);
            }
        }

        // Merge and de-duplicate by PO id, then apply group/subgroup filter in-memory
        Map<Long, PurchaseOrderEntity> merged = new LinkedHashMap<>();
        for (PurchaseOrderEntity po : byId)   { if (po.getId() != null) merged.put(po.getId(), po); }
        for (PurchaseOrderEntity po : byName) { if (po.getId() != null) merged.put(po.getId(), po); }

        return merged.values().stream()
            .filter(po -> po.getDeletedAt() == null)
            // Apply group/subgroup filters (handles group-only or group+subgroup selection)
            .filter(po -> groupName == null || groupName.isEmpty() || groupName.equals(po.getGroupName()))
            .filter(po -> subGroupName == null || subGroupName.isEmpty() || subGroupName.equals(po.getSubGroupName()))
            .map(po -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", po.getId());
                map.put("poNo", po.getPoNo());
                map.put("vendorId", po.getVendorId());
                map.put("vendorName", po.getVendorDisplayName());
                map.put("orderDate", po.getOrderDate());
                map.put("totalValue", po.getTotalValue());
                map.put("status", po.getStatus());
                return map;
            })
            .collect(Collectors.toList());
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
        
        // If all items delivered, change status and update vendor stats
        if (po.getTotalItemsPending() != null && po.getTotalItemsPending() == 0) {
            po.setStatus("Delivered");
            updateVendorStatsAfterDelivery(po);
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
 // ADD THIS METHOD TO PurchaseOrderService.java

    /**
     * ✅ NEW: Update PO with items
     * Updates existing purchase order with new vendor, dates, and items
     */
    @Transactional
    public PurchaseOrderEntity updatePurchaseOrderWithItems(
            Long poId,
            Long userId,
            Long vendorId,
            String vendorName,
            String vendorContact,
            String groupName,
            String subGroupName,
            String projectId,
            String poRefId,
            String orderDateStr,
            String expectedDeliveryStr,
            String paymentTerms,
            String shippingAddress,
            String notes,
            String status,
            List<Map<String, Object>> itemsData
    ) {
        try {
            log.info("Updating PO {} with {} items", poId, itemsData.size());
            
            // Get existing PO
            PurchaseOrderEntity po = getPurchaseOrderById(poId);
            BigDecimal oldTotalValue = po.getTotalValue() != null ? po.getTotalValue() : BigDecimal.ZERO;

            // Cancelled POs cannot be edited at all
            if ("Cancelled".equals(po.getStatus())) {
                throw new RuntimeException("Cannot edit cancelled purchase orders");
            }
            // Delivered POs can be edited (e.g. to attach a PO soft-copy document),
            // but the status must remain "Delivered" — it cannot be rolled back.
            
            // Update vendor if changed
            Long finalVendorId = vendorId;
            if (vendorId == null && vendorName != null && !vendorName.trim().isEmpty()) {
                // Create new vendor if needed
                log.info("Creating new vendor during update: {}", vendorName);
                finalVendorId = createVendorNow(vendorName, vendorContact, groupName, subGroupName, projectId, userId);
                log.info("✅ Created new vendor with ID: {}", finalVendorId);
            }
            
            // Parse dates
            LocalDate orderDate = LocalDate.parse(orderDateStr);
            LocalDate expectedDelivery = LocalDate.parse(expectedDeliveryStr);
            
            // Update PO fields
            po.setVendorId(finalVendorId);
            po.setVendorName(vendorName);
            po.setVendorContact(vendorContact);
            po.setGroupName(groupName);
            po.setSubGroupName(subGroupName);
            po.setProjectId(projectId);
            po.setOrderDate(orderDate.atStartOfDay());
            po.setExpectedDelivery(expectedDelivery.atStartOfDay());
            po.setDeliveryAddress(shippingAddress);
            po.setPaymentTerms(paymentTerms);
            po.setNotes(notes);
            po.setPoRefId(poRefId != null && !poRefId.trim().isEmpty() ? poRefId.trim() : null);
            // Update status only if provided and not locked by delivery tracking.
            // A PO auto-set to "Delivered" can be downgraded if there are still pending items.
            if (status != null && !status.isEmpty()
                    && !"Cancelled".equals(po.getStatus())) {
                if (!"Delivered".equals(po.getStatus())) {
                    // Normal case: not yet delivered — validate before allowing manual "Delivered"
                    if ("Delivered".equals(status)) {
                        List<PurchaseOrderItemEntity> currentItems = po.getItems();
                        if (currentItems == null || currentItems.isEmpty()) {
                            throw new IllegalArgumentException("Cannot mark as Delivered: purchase order has no items.");
                        }
                        List<String> pendingItemNames = currentItems.stream()
                            .filter(it -> {
                                BigDecimal ordered   = it.getQuantity()     != null ? it.getQuantity()     : BigDecimal.ZERO;
                                BigDecimal delivered = it.getDeliveredQty() != null ? it.getDeliveredQty() : BigDecimal.ZERO;
                                return delivered.compareTo(ordered) < 0;
                            })
                            .map(it -> it.getItemName() != null ? it.getItemName() : "Item #" + it.getId())
                            .collect(java.util.stream.Collectors.toList());
                        if (!pendingItemNames.isEmpty()) {
                            String itemList = pendingItemNames.size() <= 3
                                ? String.join(", ", pendingItemNames)
                                : String.join(", ", pendingItemNames.subList(0, 3)) + " and " + (pendingItemNames.size() - 3) + " more";
                            throw new IllegalArgumentException(
                                "Cannot mark as Delivered: the following items are not fully delivered — " + itemList +
                                ". Please complete item deliveries before marking this PO as Delivered.");
                        }
                    }
                    po.setStatus(status);
                } else {
                    // Currently "Delivered": allow downgrade only if there are still pending items
                    boolean hasPendingItems = po.getTotalItemsPending() != null && po.getTotalItemsPending() > 0;
                    if (hasPendingItems) {
                        po.setStatus(status);
                        log.info("PO {} status downgraded from Delivered to {} (pendingItems={})",
                                po.getPoNo(), status, po.getTotalItemsPending());
                    } else {
                        log.info("PO {} status unchanged - all items fully delivered, ignoring status change to {}",
                                po.getPoNo(), status);
                    }
                }
            }
            
            // ── Update PO items IN PLACE (upsert by line position) rather than delete-and-recreate.
            // Bills link to a PO item by primary key (bill_items.po_item_id → purchase_order_items.id).
            // The previous delete-all + re-insert assigned brand-new ids on every edit, ORPHANING any
            // bill raised against this PO (BillService then throws "PO Item not found" on edit/delete,
            // and delivered-qty reconciliation breaks). Reusing the existing row for each surviving
            // line position keeps its id — and its delivered-qty history (set by bills) — intact.
            List<PurchaseOrderItemEntity> existingItems =
                    purchaseOrderItemRepository.findByPurchaseOrderId(poId);
            // Map: lineNo → existing row. Rows still matched by the new item list are updated in place;
            // whatever remains here after the loop are removed lines, to be deleted.
            Map<Integer, PurchaseOrderItemEntity> existingByLineNo = new java.util.LinkedHashMap<>();
            for (PurchaseOrderItemEntity ei : existingItems) {
                if (ei.getLineNo() != null) {
                    existingByLineNo.put(ei.getLineNo(), ei);
                }
            }

            BigDecimal totalValue = BigDecimal.ZERO;
            int totalItemsOrdered = 0;
            List<PurchaseOrderItemEntity> poItems = new ArrayList<>();

            for (int i = 0; i < itemsData.size(); i++) {
                Map<String, Object> itemData = itemsData.get(i);

                BigDecimal quantity  = safeToBigDecimal(itemData.get("quantity"), BigDecimal.ONE);
                BigDecimal unitPrice = safeToBigDecimal(itemData.get("unitPrice"));
                BigDecimal gst       = safeToBigDecimal(itemData.get("gst"), new BigDecimal("18"));
                BigDecimal discount  = safeToBigDecimal(itemData.get("discount"));

                // Calculate line total
                BigDecimal lineSubtotal   = quantity.multiply(unitPrice);
                BigDecimal discountAmount = lineSubtotal.multiply(discount)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal taxableAmount  = lineSubtotal.subtract(discountAmount);
                BigDecimal gstAmount      = taxableAmount.multiply(gst)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal lineTotal      = taxableAmount.add(gstAmount);

                if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("Quantity must be greater than 0 for item: " + itemData.get("itemName"));
                }
                if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new RuntimeException("Unit price must be greater than 0 for item: " + itemData.get("itemName"));
                }

                int lineNo = i + 1;

                // Reuse the existing row at this line position (preserves its id → keeps bill links
                // and delivered-qty history); otherwise this is a newly-added line.
                PurchaseOrderItemEntity poItem = existingByLineNo.remove(lineNo);
                if (poItem == null) {
                    poItem = new PurchaseOrderItemEntity();
                    poItem.setPurchaseOrder(po);
                    poItem.setLineNo(lineNo);
                }

                // Preserve previously-recorded delivered qty for this line. You cannot reduce a
                // line below what has already been delivered/billed against it — that would
                // silently discard real delivery & bill history. Reject the edit instead.
                BigDecimal preservedDelivered = poItem.getDeliveredQty() != null
                        ? poItem.getDeliveredQty() : BigDecimal.ZERO;
                if (preservedDelivered.compareTo(quantity) > 0) {
                    throw new RuntimeException(String.format(
                        "Cannot reduce '%s' to %s: %s already delivered/billed against this line. " +
                        "Reverse the delivery or bill before lowering the ordered quantity.",
                        itemData.get("itemName"),
                        quantity.stripTrailingZeros().toPlainString(),
                        preservedDelivered.stripTrailingZeros().toPlainString()));
                }

                poItem.setItemName(itemData.get("itemName").toString());
                poItem.setUnit(itemData.get("unit") != null ? itemData.get("unit").toString() : "Nos");
                poItem.setHsnCode(itemData.get("hsnCode") != null ? itemData.get("hsnCode").toString() : null);
                poItem.setDescription(itemData.containsKey("itemDescription")
                        ? itemData.get("itemDescription").toString()
                        : "");
                poItem.setQuantity(quantity);
                poItem.setDeliveredQty(preservedDelivered);
                poItem.setUnitPrice(unitPrice);
                poItem.setTaxPercent(gst);

                poItems.add(poItem);
                totalValue = totalValue.add(lineTotal);
                totalItemsOrdered += quantity.intValue();
            }

            // Any existing rows still unmatched are lines removed in this edit — delete them.
            // (A bill referencing a removed line will still surface via the poItemId, but the line
            // truly no longer exists on the PO.)
            if (!existingByLineNo.isEmpty()) {
                log.info("Removing {} deleted line(s) from PO {}", existingByLineNo.size(), poId);
                purchaseOrderItemRepository.deleteAll(existingByLineNo.values());
            }

            // Save items: existing rows → UPDATE (id preserved); new rows → INSERT
            List<PurchaseOrderItemEntity> savedItems = purchaseOrderItemRepository.saveAll(poItems);
            log.info("Saved {} updated items", savedItems.size());

            // Recalculate delivered total from saved items (honours preserved values)
            int totalDelivered = savedItems.stream()
                    .mapToInt(it -> it.getDeliveredQty() != null ? it.getDeliveredQty().intValue() : 0)
                    .sum();

            // Update totals
            po.setTotalValue(totalValue.setScale(2, RoundingMode.HALF_UP));
            po.setTotalItemsOrdered(totalItemsOrdered);
            po.setTotalItemsDelivered(totalDelivered);   // ← FIX: was hard-coded 0
            po.setItems(savedItems);
            
            PurchaseOrderEntity updated = purchaseOrderRepository.save(po);

            // ✅ Recalculate vendor stats from live PO data (guaranteed accuracy)
            if (finalVendorId != null) {
                vendorService.recalculateVendorStatsFromPOs(finalVendorId);
            }

            // Keep the linked quotation's ordering status in sync with the edited PO quantities.
            // Decreasing (or increasing) line quantities can move it between "PO Created" and
            // "Partially Ordered". Previously this was only done on PO create, so an edit left the
            // quotation stuck showing "PO Created" even after the ordered qty dropped below quoted.
            if (updated.getQuotationId() != null) {
                syncQuotationOrderingStatus(updated.getQuotationId());
            }

            log.info("✅ Updated PO {} with vendorId {}, {} items, total: {}",
                updated.getPoNo(), updated.getVendorId(), itemsData.size(), totalValue);

            return updated;
            
        } catch (Exception e) {
            log.error("Error updating PO {}: {}", poId, e.getMessage(), e);
            throw new RuntimeException("Failed to update PO: " + e.getMessage());
        }
    }
    /**
     * Create PO manually (without quotation)
     */
    @Transactional
    public PurchaseOrderEntity createPurchaseOrder(PurchaseOrderEntity po, Long userId) {
        // PO number is derived from the DB-generated id (see below).
        po.setPoNo("__TEMP_PO_" + System.nanoTime() + "__");
        po.setCreatedBy(userId);
        po.setOrderDate(LocalDateTime.now());
        // Honour caller-supplied status; default to Draft only when absent
        if (po.getStatus() == null || po.getStatus().trim().isEmpty()) {
            po.setStatus("Draft");
        }
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
        
        // First save — DB assigns auto-increment id
        PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
        if (savedPO.getPoNo().startsWith("__TEMP_PO_")) {
            savedPO.setPoNo(buildPoCode(savedPO.getId(), savedPO.getDocumentType()));
            savedPO = purchaseOrderRepository.save(savedPO);
        }
        
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
            vendorId = createVendorNow(
                "Vendor-" + quotation.getVendorContact(),
                quotation.getVendorContact(),
                quotation.getGroupName(),
                quotation.getSubGroupName(),
                quotation.getProjectId(),
                userId
            );
            quotation.setVendorId(vendorId);
            quotationRepository.save(quotation);
        }
        
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .poNo("__TEMP_PO_" + System.nanoTime() + "__")
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
        
        // First save — DB assigns auto-increment id
        PurchaseOrderEntity savedPO = purchaseOrderRepository.save(po);
        if (savedPO.getPoNo().startsWith("__TEMP_PO_")) {
            savedPO.setPoNo(buildPoCode(savedPO.getId(), savedPO.getDocumentType()));
            savedPO = purchaseOrderRepository.save(savedPO);
        }
        
        for (int i = 0; i < quotationItems.size(); i++) {
            QuotationItemEntity qItem = quotationItems.get(i);
            
            PurchaseOrderItemEntity poItem = PurchaseOrderItemEntity.builder()
                    .purchaseOrder(savedPO)
                    .lineNo(i + 1)
                    .itemName(qItem.getItemName())
                    .unit(qItem.getUnit() != null && !qItem.getUnit().isBlank() ? qItem.getUnit() : "Nos")
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
     * Recalculate PO totals from items.
     * FIX: getTotalOrderedItems / getTotalDeliveredItems now return BigDecimal
     * (the column is DECIMAL(18,4)). Using Integer caused silent truncation for
     * large or decimal quantities, making the "Delivered" status never trigger.
     */
    private void recalculateTotals(PurchaseOrderEntity po) {
        BigDecimal totalOrdered   = purchaseOrderItemRepository.getTotalOrderedItems(po.getId());
        BigDecimal totalDelivered = purchaseOrderItemRepository.getTotalDeliveredItems(po.getId());

        BigDecimal safeOrdered   = totalOrdered   != null ? totalOrdered   : BigDecimal.ZERO;
        BigDecimal safeDelivered = totalDelivered != null ? totalDelivered : BigDecimal.ZERO;

        po.setTotalItemsOrdered(safeOrdered.intValue());
        po.setTotalItemsDelivered(safeDelivered.intValue());

        // Update PO delivery status based on recalculated totals
        if (safeDelivered.compareTo(BigDecimal.ZERO) == 0) {
            po.setStatus("Ordered");
        } else if (safeDelivered.compareTo(safeOrdered) >= 0) {
            po.setStatus("Delivered");
        } else {
            po.setStatus("Partially Delivered");
        }
    }
    
    /**
     * Get PO statistics
     */
    @Transactional(readOnly = true)
    public POStats getStatistics(String groupName, String subGroupName, String projectId, Long userId, String userRole) {
        return getStatistics(groupName, subGroupName, projectId, null, null, null, null, null, null, userId, userRole);
    }

    public POStats getStatistics(String groupName, String subGroupName, String projectId,
            String status, String paymentStatus, String searchTerm, String documentType,
            String orderDateFrom, String orderDateTo, Long userId, String userRole) {
        Page<PurchaseOrderEntity> allPOs = getPurchaseOrders(
                groupName, subGroupName, projectId, status, paymentStatus, searchTerm,
                documentType, userId, userRole, 0, Integer.MAX_VALUE, "orderDate", "DESC",
                orderDateFrom, orderDateTo
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
    
 
 /**
  * Derives the document number based on type.
  * Purchase Orders keep the deterministic id-based scheme: PO-YYYY-NNNN (e.g. PO-2026-0042).
  * Work Orders get their own contiguous per-year series: WO-YYYY-NNNN (e.g. WO-2026-0001),
  * counted independently of POs. existsByPoNo guards against concurrent-insert collisions.
  */
 private String buildPoCode(Long id, String documentType) {
     int year = java.time.LocalDate.now().getYear();
     if ("WORK_ORDER".equalsIgnoreCase(documentType)) {
         long count = purchaseOrderRepository.countFinalizedByTypeForYear("WORK_ORDER", year, "WO-%");
         String code = String.format("WO-%d-%04d", year, count + 1);
         int attempt = 0;
         while (purchaseOrderRepository.existsByPoNo(code) && attempt < 100) {
             count++;
             code = String.format("WO-%d-%04d", year, count + 1);
             attempt++;
         }
         return code;
     }
     // Purchase Order — unchanged id-based scheme
     return String.format("PO-%d-%04d", year, id);
 }

 /** @deprecated Use buildPoCode(id) after the first save instead. */
 @Deprecated
 private String generatePONumber() {
     try {
         // Get current year
         int currentYear = LocalDate.now().getYear();
         
         // Count POs for current year (excluding deleted ones)
         long countForYear = purchaseOrderRepository.countPOsForYear(currentYear);
         
         // Generate PO number with incremented count
         String poNo = String.format("PO-%d-%03d", currentYear, countForYear + 1);
         
         // Check if PO number already exists (safety check)
         int attempt = 0;
         while (purchaseOrderRepository.existsByPoNo(poNo) && attempt < 100) {
             countForYear++;
             poNo = String.format("PO-%d-%03d", currentYear, countForYear + 1);
             attempt++;
         }
         
         if (attempt >= 100) {
             // Fallback: use timestamp-based unique ID
             poNo = String.format("PO-%d-%d", currentYear, System.currentTimeMillis() % 100000);
             log.warn("Using timestamp-based PO number: {}", poNo);
         }
         
         log.info("Generated PO number: {} (count for year {}: {})", poNo, currentYear, countForYear);
         return poNo;
         
     } catch (Exception e) {
         log.error("Error generating PO number: {}", e.getMessage(), e);
         // Fallback to timestamp-based unique ID
         int currentYear = LocalDate.now().getYear();
         return String.format("PO-%d-%d", currentYear, System.currentTimeMillis() % 100000);
     }
 }
    
    /**
     * ✅ RENAMED: Update vendor stats after delivery (no longer creates vendor)
     */
    private void updateVendorStatsAfterDelivery(PurchaseOrderEntity po) {
        if (po.getVendorId() == null) {
            log.warn("Cannot update vendor stats - vendorId is null for PO: {}", po.getPoNo());
            return;
        }
        
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
    
    /**
     * Get POs for dropdown with filters
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDropdownWrapper> getPurchaseOrdersForDropdown(
            String groupName, 
            String subGroupName, 
            String projectId
    ) {
        List<PurchaseOrderEntity> pos;
        
        // Fetch filtered or all POs
        if (groupName != null || subGroupName != null || projectId != null) {
            pos = purchaseOrderRepository.findAllForDropdownFiltered(groupName, subGroupName, projectId);
        } else {
            pos = purchaseOrderRepository.findAllForDropdown();
        }
        
        // Convert to dropdown wrapper
        return pos.stream()
                .map(this::convertToDropdownWrapper)
                .collect(Collectors.toList());
    }

    /**
     * Convert PO entity to dropdown wrapper
     */
    private PurchaseOrderDropdownWrapper convertToDropdownWrapper(PurchaseOrderEntity po) {
        String vendorName = "Unknown Vendor";
        
        // Get vendor name if vendor exists
        if (po.getVendorId() != null) {
            vendorName = vendorRepository.findById(po.getVendorId())
                    .map(VendorEntity::getName)
                    .orElse("Unknown Vendor");
        }
        
        return PurchaseOrderDropdownWrapper.builder()
                .id(po.getId())
                .poNo(po.getPoNo())
                .vendorName(vendorName)
                .status(po.getStatus())
                .projectId(po.getProjectId())
                .groupName(po.getGroupName())
                .subGroupName(po.getSubGroupName())
                .build();
    }

    /**
     * Get POs by vendor for dropdown
     */
    @Transactional(readOnly = true)
    public List<PurchaseOrderDropdownWrapper> getPurchaseOrdersByVendorForDropdown(Long vendorId) {
        List<PurchaseOrderEntity> pos = purchaseOrderRepository.findByVendorId(vendorId);
        
        return pos.stream()
                .map(this::convertToDropdownWrapper)
                .collect(Collectors.toList());
    }
    private BigDecimal safeToBigDecimal(Object value) {
        return safeToBigDecimal(value, BigDecimal.ZERO);
    }
    private BigDecimal safeToBigDecimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        String strValue = value.toString().trim();
        
        // Handle empty string
        if (strValue.isEmpty()) {
            return defaultValue;
        }
        
        try {
            return new BigDecimal(strValue);
        } catch (NumberFormatException e) {
            log.warn("Invalid number format: '{}', using default: {}", strValue, defaultValue);
            return defaultValue;
        }
    }

    // =========================================================================
    // PO FILE UPLOAD
    // =========================================================================

    private static final String PO_UPLOAD_DIR = "uploads/purchase_orders/";
    private static final long MAX_PO_FILE_SIZE = 10L * 1024 * 1024; // 10MB

    @Transactional
    public String uploadPOFile(Long poId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }
        if (file.getSize() > MAX_PO_FILE_SIZE) {
            throw new RuntimeException("File size exceeds 10 MB limit");
        }
        String contentType = file.getContentType();
        if (!isValidPOFileType(contentType)) {
            throw new RuntimeException("Invalid file type. Only PDF, PNG, JPG, JPEG are allowed");
        }

        PurchaseOrderEntity po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new RuntimeException("Purchase order not found: " + poId));

        String originalFilename = file.getOriginalFilename();

        // Store file bytes directly in DB (MEDIUMBLOB — up to 16 MB)
        po.setPoFileData(file.getBytes());
        po.setPoFileContentType(contentType);
        po.setPoFileName(originalFilename);
        po.setPoFileSize(file.getSize());
        // Clear legacy path for new uploads — signals "use BLOB"
        po.setPoFilePath(null);
        purchaseOrderRepository.save(po);

        log.info("Uploaded PO file for PO id={}: {} (stored as BLOB, {} bytes)",
                 poId, originalFilename, file.getSize());
        return "blob:" + poId;
    }

    /**
     * Generate the SESOLA PO PDF from the supplied document data and store it in the
     * PO's file BLOB (same slot used by uploads, so existing download routes serve it).
     */
    @Transactional
    public String generateAndStorePoDocument(Long poId,
            com.istlgroup.istl_group_crm_backend.wrapperClasses.PoDocumentRequest req) {
        PurchaseOrderEntity po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new RuntimeException("Purchase order not found: " + poId));
        try {
            if (req.getPoNo() == null || req.getPoNo().isBlank()) req.setPoNo(po.getPoNo());
            byte[] pdf = purchaseOrderPdfService.generate(req);
            String filePrefix = "WORK_ORDER".equalsIgnoreCase(po.getDocumentType()) ? "WO_" : "PO_";
            String fileName = filePrefix + (po.getPoNo() != null ? po.getPoNo().replaceAll("[^A-Za-z0-9._-]", "_") : poId) + ".pdf";
            po.setPoFileData(pdf);
            po.setPoFileContentType("application/pdf");
            po.setPoFileName(fileName);
            po.setPoFileSize((long) pdf.length);
            po.setPoFilePath(null);
            // Persist the form data so re-generate can prefill exactly what was used.
            try {
                po.setPoDocPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req));
            } catch (Exception je) {
                log.warn("Could not serialize PO doc payload for id={}: {}", poId, je.getMessage());
            }
            purchaseOrderRepository.save(po);
            log.info("Generated PO PDF for PO id={} ({} bytes)", poId, pdf.length);
            return "blob:" + poId;
        } catch (Exception e) {
            log.error("Failed to generate PO document for id={}", poId, e);
            throw new RuntimeException("Failed to generate PO document: " + e.getMessage());
        }
    }

    /**
     * Returns the raw file bytes for a PO, regardless of where the file lives.
     * Priority: BLOB column → disk fallback (legacy files).
     */
    public byte[] getPOFileBytes(Long poId) throws IOException {
        PurchaseOrderEntity po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new RuntimeException("Purchase order not found: " + poId));

        if (po.getPoFileData() != null && po.getPoFileData().length > 0) {
            return po.getPoFileData();
        }
        if (po.getPoFilePath() != null && !po.getPoFilePath().isBlank()) {
            Path filePath = Paths.get(po.getPoFilePath());
            if (Files.exists(filePath)) {
                return Files.readAllBytes(filePath);
            }
        }
        throw new RuntimeException("No file found for PO: " + poId);
    }

    /**
     * Returns the resolved content type for a PO file.
     */
    public String getPOFileContentType(Long poId) throws IOException {
        PurchaseOrderEntity po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new RuntimeException("Purchase order not found: " + poId));

        if (po.getPoFileContentType() != null) {
            return po.getPoFileContentType();
        }
        if (po.getPoFilePath() != null) {
            Path p = Paths.get(po.getPoFilePath());
            String ct = Files.probeContentType(p);
            return ct != null ? ct : "application/octet-stream";
        }
        return "application/octet-stream";
    }

    private boolean isValidPOFileType(String contentType) {
        return contentType != null && (
                contentType.equals("application/pdf") ||
                contentType.equals("image/png") ||
                contentType.equals("image/jpg") || 
                contentType.equals("image/jpeg")
        );
    }
}