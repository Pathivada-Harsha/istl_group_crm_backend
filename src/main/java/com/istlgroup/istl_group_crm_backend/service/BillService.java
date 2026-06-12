package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.BillEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.BillPaymentEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderEntity;
import com.istlgroup.istl_group_crm_backend.entity.PurchaseOrderItemEntity;
import com.istlgroup.istl_group_crm_backend.repo.BillItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillPaymentRepository;
import com.istlgroup.istl_group_crm_backend.repo.BillRepository;
import com.istlgroup.istl_group_crm_backend.repo.ProjectRepository;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.PurchaseOrderRepository;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.repo.VendorRepository;
import com.istlgroup.istl_group_crm_backend.repo.WarehouseRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvTransactionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillItemDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.BillStatsDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentDTO;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.PaymentHistoryDTO;

import com.istlgroup.istl_group_crm_backend.service.ProjectAccessService;
import com.istlgroup.istl_group_crm_backend.repo.RoleHierarchyRepo;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillService {

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final BillPaymentRepository billPaymentRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final UsersRepo usersRepo;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProjectRepository projectRepository;
    private final ProjectStatsService projectStatsService;
    private final ProjectAccessService projectAccessService;
    private final RoleHierarchyRepo roleHierarchyRepo;
    private final WarehouseRepository warehouseRepository;
    private static final String UPLOAD_DIR = "uploads/bills/";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    // =========================================================================
    // GET / LIST
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<BillDTO> getBills(
            String projectId, String groupId, String subGroupId,
            String status, Long vendorId, Long poId, String searchTerm,
            String billDateFromStr, String billDateToStr,
            int page, int size, String sortBy, String sortDirection,
            boolean isAdmin, Long userId, String userRole
    ) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.Direction.fromString(sortDirection), sortBy);

        // Parse date range — billDate is LocalDate so no time component needed
        java.time.LocalDate fromDate = null, toDate = null;
        if (billDateFromStr != null && !billDateFromStr.isBlank()) {
            try { fromDate = java.time.LocalDate.parse(billDateFromStr); } catch (Exception ignored) {}
        }
        if (billDateToStr != null && !billDateToStr.isBlank()) {
            try { toDate = java.time.LocalDate.parse(billDateToStr); } catch (Exception ignored) {}
        }

        Page<BillEntity> bills;

        if (projectId != null && !projectId.isEmpty()) {
            bills = billRepository.findByProjectIdWithFilters(projectId, status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
        } else if (isAdmin) {
            if (subGroupId != null && !subGroupId.isEmpty()) {
                bills = billRepository.findBySubGroupWithFilters(groupId, subGroupId, status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
            } else if (groupId != null && !groupId.isEmpty()) {
                bills = billRepository.findByGroupWithFilters(groupId, status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
            } else {
                bills = billRepository.findAllWithFilters(status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
            }
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            boolean hasAccessibleProjects = accessibleProjectIds != null && !accessibleProjectIds.isEmpty();

            if (hasAccessibleProjects) {
                if (subGroupId != null && !subGroupId.isEmpty()) {
                    bills = billRepository.findByGroupSubGroupAndAccessibleProjects(groupId, subGroupId, accessibleProjectIds, pageable);
                } else if (groupId != null && !groupId.isEmpty()) {
                    bills = billRepository.findByGroupIdAndAccessibleProjects(groupId, accessibleProjectIds, pageable);
                } else {
                    bills = billRepository.findByAccessibleProjects(accessibleProjectIds, pageable);
                }
            } else {
                if (subGroupId != null && !subGroupId.isEmpty()) {
                    bills = billRepository.findBySubGroupWithFilters(groupId, subGroupId, status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
                } else if (groupId != null && !groupId.isEmpty()) {
                    bills = billRepository.findByGroupWithFilters(groupId, status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
                } else {
                    bills = billRepository.findAllWithFilters(status, vendorId, poId, searchTerm, fromDate, toDate, pageable);
                }
            }
        }
        return bills.map(this::enrichBillEntity);
    }

    // =========================================================================
    // OUTSTANDINGS — dedicated method for BillsOutstandingsTab
    // No pagination; returns lightweight DTOs (no items/paymentHistory loaded).
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> getOutstandings(
            String projectId, String groupId, String subGroupId,
            boolean isAdmin, Long userId, String userRole
    ) {
        // ── 1. Fetch all bills (no pagination, stable billDate sort) ──────────
        List<BillEntity> allBills;

        if (projectId != null && !projectId.isBlank()) {
            allBills = billRepository.findAllForOutstandingsByProject(projectId);
        } else if (isAdmin) {
            if (subGroupId != null && !subGroupId.isBlank()) {
                allBills = billRepository.findAllForOutstandingsBySubGroup(groupId, subGroupId);
            } else if (groupId != null && !groupId.isBlank()) {
                allBills = billRepository.findAllForOutstandingsByGroup(groupId);
            } else {
                allBills = billRepository.findAllForOutstandings();
            }
        } else {
            List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
            boolean hasAccess = accessibleProjectIds != null && !accessibleProjectIds.isEmpty();

            if (hasAccess) {
                if (subGroupId != null && !subGroupId.isBlank()) {
                    allBills = billRepository.findAllForOutstandingsBySubGroupAndAccessibleProjects(groupId, subGroupId, accessibleProjectIds);
                } else if (groupId != null && !groupId.isBlank()) {
                    allBills = billRepository.findAllForOutstandingsByGroupAndAccessibleProjects(groupId, accessibleProjectIds);
                } else {
                    allBills = billRepository.findAllForOutstandingsByAccessibleProjects(accessibleProjectIds);
                }
            } else {
                // Non-admin with no accessible projects — return empty
                allBills = java.util.Collections.emptyList();
            }
        }

        // ── 2. Enrich (lightweight — skip items & paymentHistory) ────────────
        List<BillDTO> enrichedAll = allBills.stream()
                .map(this::enrichBillEntityLightweight)
                .collect(Collectors.toList());

        // ── 3. Split: outstanding = has remaining balance, not paid/cancelled ─
        List<BillDTO> outstanding = enrichedAll.stream()
                .filter(b -> {
                    String s = b.getStatus() == null ? "" : b.getStatus();
                    if (s.equalsIgnoreCase("Paid") || s.equalsIgnoreCase("Cancelled")) return false;
                    java.math.BigDecimal bal = b.getBalanceAmount() != null
                            ? b.getBalanceAmount()
                            : (b.getTotalAmount() != null ? b.getTotalAmount() : java.math.BigDecimal.ZERO)
                                    .subtract(b.getPaidAmount() != null ? b.getPaidAmount() : java.math.BigDecimal.ZERO);
                    return bal.compareTo(new java.math.BigDecimal("0.01")) > 0;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("allBills",    enrichedAll);
        result.put("outstanding", outstanding);
        return result;
    }

    /** Lightweight enrich — skips items and paymentHistory to keep response fast. */
    private BillDTO enrichBillEntityLightweight(BillEntity bill) {
        BillDTO dto = new BillDTO();
        dto.setId(bill.getId());
        dto.setBillNo(bill.getBillNo());
        dto.setBillRefId(bill.getBillRefId());
        dto.setVendorId(bill.getVendorId());
        dto.setPoId(bill.getPoId());
        dto.setBillDate(bill.getBillDate());
        dto.setDueDate(bill.getDueDate());
        dto.setTotalAmount(bill.getTotalAmount());
        dto.setPaidAmount(bill.getPaidAmount());
        dto.setBalanceAmount(bill.getBalanceAmount());
        dto.setStatus(bill.getStatus());
        dto.setProjectId(bill.getProjectId());
        dto.setGroupId(bill.getGroupId());
        dto.setSubGroupId(bill.getSubGroupId());
        dto.setSourceType(bill.getSourceType() != null ? bill.getSourceType() : "VENDOR");
        dto.setWarehouseId(bill.getWarehouseId());
        dto.setInvTxnRef(bill.getInvTxnRef());

        if ("WAREHOUSE".equalsIgnoreCase(bill.getSourceType())) {
            if (bill.getWarehouseId() != null) {
                warehouseRepository.findById(bill.getWarehouseId())
                        .ifPresent(wh -> dto.setWarehouseName(wh.getName()));
            }
        } else {
            if (bill.getVendorId() != null) {
                vendorRepository.findById(bill.getVendorId())
                        .ifPresent(v -> dto.setVendorName(v.getName()));
            }
        }

        if (bill.getPoId() != null) {
            purchaseOrderRepository.findById(bill.getPoId()).ifPresent(po -> {
                dto.setPoNumber(po.getPoNo());
                dto.setPoRefId(po.getPoRefId());
            });
        }

        return dto;
    }

    @Transactional(readOnly = true)
    public BillDTO getBillById(Long id) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + id));
        return enrichBillEntity(bill);
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    @Transactional
    public BillDTO createBill(BillDTO dto, Long userId) {
        log.info("Creating bill for vendor: {}, PO: {}", dto.getVendorId(), dto.getPoId());

        boolean isWarehouseBill = "WAREHOUSE".equalsIgnoreCase(dto.getSourceType());

        if (!isWarehouseBill && dto.getVendorId() == null) {
            throw new RuntimeException("Vendor ID is required");
        }

        if (!isWarehouseBill && dto.getVendorId() != null) {
            vendorRepository.findById(dto.getVendorId())
                    .orElseThrow(() -> new RuntimeException("Vendor not found"));
        }

        PurchaseOrderEntity po = null;
        if (dto.getPoId() != null) {
            po = purchaseOrderRepository.findById(dto.getPoId())
                    .orElseThrow(() -> new RuntimeException("Purchase Order not found"));
        }

        // Bill number is derived from the DB-generated id (see below).
        // Use a temp placeholder to satisfy NOT NULL constraint.
        BillEntity bill = new BillEntity();
        bill.setBillNo("__TEMP_BILL_" + System.nanoTime() + "__");
        bill.setBillRefId(dto.getBillRefId());
        bill.setVendorId(dto.getVendorId());
        bill.setPoId(dto.getPoId());
        bill.setBillDate(dto.getBillDate());
        bill.setDueDate(dto.getDueDate());
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setStatus("Pending");
        bill.setProjectId(dto.getProjectId());
        bill.setGroupId(dto.getGroupId());
        bill.setSubGroupId(dto.getSubGroupId());
        bill.setNotes(dto.getNotes());
        bill.setCreatedBy(userId);
        bill.setCreatedAt(LocalDateTime.now());
        bill.setUploadedBy(userId);
        bill.setUploadedOn(LocalDateTime.now());

        // ── Warehouse-outward fields ─────────────────────────────────────────
        bill.setSourceType(dto.getSourceType() != null ? dto.getSourceType().toUpperCase() : "VENDOR");
        bill.setWarehouseId(dto.getWarehouseId());
        bill.setInvTxnRef(dto.getInvTxnRef());

        // First save — DB assigns auto-increment id
        bill = billRepository.save(bill);
        // Derive bill number from the DB id — only if no real code exists yet.
        String existingBillNo = bill.getBillNo();
        if (existingBillNo == null || existingBillNo.isBlank() || existingBillNo.startsWith("__TEMP_")) {
            String billNo = String.format("BILL-%d-%04d",
                java.time.LocalDate.now().getYear(), bill.getId());
            bill.setBillNo(billNo);
            bill = billRepository.save(bill);
        }
        log.info("Bill saved with ID: {} and number: {}", bill.getId(), bill.getBillNo());

        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            for (BillItemDTO itemDTO : dto.getItems()) {
                BigDecimal billQty = itemDTO.getQuantity() != null
                        ? itemDTO.getQuantity()
                        : BigDecimal.ONE;

                BillItemEntity item = new BillItemEntity();

                if (itemDTO.getPoItemId() != null) {
                    // PO-linked item: validate pending qty and update delivered qty
                    PurchaseOrderItemEntity poItem = purchaseOrderItemRepository
                            .findById(itemDTO.getPoItemId())
                            .orElseThrow(() -> new RuntimeException("PO Item not found"));

                    BigDecimal pending = poItem.getPendingQty() != null
                            ? poItem.getPendingQty()
                            : BigDecimal.ZERO;

                    if (billQty.compareTo(pending) > 0) {
                        throw new RuntimeException(
                            String.format(
                                "Cannot bill %.2f for item '%s'. Only %.2f pending delivery " +
                                "(Ordered: %.2f, Delivered: %.2f)",
                                billQty,
                                poItem.getItemName() != null ? poItem.getItemName() : "Unknown",
                                pending,
                                poItem.getQuantity(),
                                poItem.getDeliveredQty()
                            )
                        );
                    }

                    item.setPoItemId(itemDTO.getPoItemId());
                    item.setItemName(poItem.getItemName());        // store PO item name
                    item.setDescription(poItem.getDescription());
                    item.setQuantity(billQty);
                    item.setUnitPrice(poItem.getUnitPrice());
                    item.setTaxPercent(poItem.getTaxPercent());

                    bill.addItem(item);

                    BigDecimal newDeliveredQty = poItem.getDeliveredQty().add(billQty);
                    poItem.setDeliveredQty(newDeliveredQty);
                    purchaseOrderItemRepository.save(poItem);

                    log.info("Updated PO item {} - Delivered: {} → {}, Pending: {}",
                             poItem.getId(),
                             poItem.getDeliveredQty().subtract(billQty),
                             poItem.getDeliveredQty(),
                             poItem.getPendingQty());
                } else {
                    // Manual item (no PO linked): use values directly from DTO
                    item.setPoItemId(null);
                    item.setItemName(itemDTO.getItemName() != null ? itemDTO.getItemName() : "");
                    item.setDescription(itemDTO.getDescription() != null ? itemDTO.getDescription() : "");
                    item.setQuantity(billQty);
                    item.setUnitPrice(itemDTO.getUnitPrice() != null ? itemDTO.getUnitPrice() : BigDecimal.ZERO);
                    item.setTaxPercent(itemDTO.getTaxPercent() != null ? itemDTO.getTaxPercent() : BigDecimal.ZERO);

                    bill.addItem(item);

                    log.info("Added manual item '{}' - Qty: {}, Price: {}",
                             item.getDescription(), billQty, item.getUnitPrice());
                }
            }
        }

        recalculateBillTotal(bill);
        bill = billRepository.save(bill);

        log.info("Created bill: {} with {} items", bill.getBillNo(), bill.getItems().size());

        if (dto.getPoId() != null) {
            updatePOStatusAfterBill(dto.getPoId());
        }

        // ── Sync project bill stats (replaces deleted trigger) ──────────────
        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            syncProjectBillStats(bill.getProjectId());
        }

        return enrichBillEntity(bill);
    }

    // =========================================================================
    // UPDATE PO STATUS AFTER BILL
    // FIX 1: Changed Integer → BigDecimal to match DECIMAL(18,4) column.
    //        Integer truncation caused totalDelivered.compareTo(totalOrdered) to
    //        never return 0 for large/decimal quantities, so status stayed
    //        "Partially Delivered" even when 100% was billed.
    // FIX 2: Use compareTo() instead of .equals() for BigDecimal equality.
    // FIX 3: Use >= instead of == so floating-point rounding never causes
    //        100% delivery to be missed.
    // =========================================================================

    @Transactional
    private void updatePOStatusAfterBill(Long poId) {
        if (poId == null) return;

        PurchaseOrderEntity po = purchaseOrderRepository.findById(poId).orElse(null);
        if (po == null) return;

        // FIX: getTotalDeliveredItems / getTotalOrderedItems now return BigDecimal
        BigDecimal totalDelivered = purchaseOrderItemRepository.getTotalDeliveredItems(poId);
        BigDecimal totalOrdered   = purchaseOrderItemRepository.getTotalOrderedItems(poId);

        BigDecimal safeDelivered = totalDelivered != null ? totalDelivered : BigDecimal.ZERO;
        BigDecimal safeOrdered   = totalOrdered   != null ? totalOrdered   : BigDecimal.ZERO;

        // Store integer summary on the PO header for display
        po.setTotalItemsDelivered(safeDelivered.intValue());
        po.setTotalItemsOrdered(safeOrdered.intValue());

        // FIX: Use compareTo() for BigDecimal, and >= to guard against rounding
        if (safeDelivered.compareTo(BigDecimal.ZERO) == 0) {
            po.setStatus("Ordered");
        } else if (safeDelivered.compareTo(safeOrdered) >= 0) {
            po.setStatus("Delivered");           // ← now fires correctly at 100%
        } else {
            po.setStatus("Partially Delivered");
        }

        purchaseOrderRepository.save(po);

        log.info("Updated PO {} - Delivered: {}/{}, Status: {}",
                 po.getPoNo(), safeDelivered, safeOrdered, po.getStatus());
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @Transactional
    public BillDTO updateBill(Long id, BillDTO dto, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found"));

        if (dto.getBillDate() != null) bill.setBillDate(dto.getBillDate());
        if (dto.getDueDate() != null)  bill.setDueDate(dto.getDueDate());
        if (dto.getNotes() != null)    bill.setNotes(dto.getNotes());
        bill.setBillRefId(dto.getBillRefId());

        if (dto.getItems() != null) {
            log.info("========================================");
            log.info("UPDATING BILL: {}", bill.getBillNo());
            log.info("========================================");

            // STEP 1: Restore old delivered quantities
            log.info("STEP 1: RESTORING old quantities...");
            for (BillItemEntity oldItem : bill.getItems()) {
                if (oldItem.getPoItemId() != null) {
                    PurchaseOrderItemEntity poItem = purchaseOrderItemRepository
                            .findById(oldItem.getPoItemId())
                            .orElseThrow(() -> new RuntimeException("PO Item not found"));

                    BigDecimal oldDelivered = poItem.getDeliveredQty();
                    BigDecimal oldBillQty   = oldItem.getQuantity();
                    BigDecimal restored     = oldDelivered.subtract(oldBillQty);

                    if (restored.compareTo(BigDecimal.ZERO) < 0) {
                        log.warn("Prevented negative delivered qty for PO item {}: {} - {} would be negative",
                                 poItem.getId(), oldDelivered, oldBillQty);
                        restored = BigDecimal.ZERO;
                    }

                    poItem.setDeliveredQty(restored);
                    purchaseOrderItemRepository.save(poItem);
                    purchaseOrderItemRepository.flush();

                    log.info("Restored PO item {} | Item: {} | Delivered: {} - {} = {}",
                             poItem.getId(), poItem.getItemName(), oldDelivered, oldBillQty, restored);
                }
                // Manual items (no poItemId) have no delivered-qty side-effect, skip
            }

            bill.getItems().clear();
            billItemRepository.flush();
            log.info("Cleared old bill items");

            log.info("----------------------------------------");
            log.info("STEP 2: APPLYING new quantities...");

            // STEP 2: Add new items
            for (int i = 0; i < dto.getItems().size(); i++) {
                BillItemDTO itemDTO = dto.getItems().get(i);

                if (itemDTO.getPoItemId() != null) {
                    // PO-linked item
                    PurchaseOrderItemEntity poItem = purchaseOrderItemRepository
                            .findById(itemDTO.getPoItemId())
                            .orElseThrow(() -> new RuntimeException("PO Item not found"));

                    BigDecimal newQty          = itemDTO.getQuantity();
                    BigDecimal currentDelivered = poItem.getDeliveredQty(); // already restored in Step 1
                    // pendingQty is a DB-computed column — stale in memory after Step 1 restore.
                    // Compute available directly from restored deliveredQty to get the correct value.
                    BigDecimal available = poItem.getQuantity().subtract(currentDelivered);

                    log.info("Item {}: {} | New Qty: {} | Available: {} (Delivered after restore: {}, Ordered: {})",
                             i + 1, poItem.getItemName(), newQty, available,
                             currentDelivered, poItem.getQuantity());

                    if (newQty.compareTo(available) > 0) {
                        String error = String.format(
                            "Item '%s': Cannot bill %.2f units. Only %.2f available. " +
                            "(Ordered: %.2f, Already delivered: %.2f, Available: %.2f)",
                            poItem.getItemName(), newQty, available,
                            poItem.getQuantity(), currentDelivered, available
                        );
                        log.error(error);
                        throw new RuntimeException(error);
                    }

                    BillItemEntity item = BillItemEntity.builder()
                            .bill(bill)
                            .poItemId(itemDTO.getPoItemId())
                            .itemName(poItem.getItemName())
                            .description(poItem.getDescription())
                            .quantity(newQty)
                            .unitPrice(poItem.getUnitPrice())
                            .taxPercent(poItem.getTaxPercent())
                            .build();
                    bill.addItem(item);

                    BigDecimal newDelivered = currentDelivered.add(newQty);
                    poItem.setDeliveredQty(newDelivered);
                    purchaseOrderItemRepository.save(poItem);
                    purchaseOrderItemRepository.flush();

                    log.info("Applied | Delivered: {} + {} = {}", currentDelivered, newQty, newDelivered);
                } else {
                    // Manual item (no PO linked)
                    BigDecimal newQty = itemDTO.getQuantity() != null ? itemDTO.getQuantity() : BigDecimal.ONE;

                    BillItemEntity item = BillItemEntity.builder()
                            .bill(bill)
                            .poItemId(null)
                            .itemName(itemDTO.getItemName() != null ? itemDTO.getItemName() : "")
                            .description(itemDTO.getDescription() != null ? itemDTO.getDescription() : "")
                            .quantity(newQty)
                            .unitPrice(itemDTO.getUnitPrice() != null ? itemDTO.getUnitPrice() : BigDecimal.ZERO)
                            .taxPercent(itemDTO.getTaxPercent() != null ? itemDTO.getTaxPercent() : BigDecimal.ZERO)
                            .build();
                    bill.addItem(item);

                    log.info("Item {}: Manual item '{}' | Qty: {}", i + 1, item.getDescription(), newQty);
                }
            }

            recalculateBillTotal(bill);
        }

        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);

        if (bill.getPoId() != null) {
            updatePOStatusAfterBill(bill.getPoId());
        }

        // ── Sync project bill stats (replaces deleted trigger) ──────────────
        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            syncProjectBillStats(bill.getProjectId());
        }

        log.info("========================================");
        log.info("BILL UPDATED SUCCESSFULLY: {}", bill.getBillNo());
        log.info("========================================");

        return enrichBillEntity(bill);
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @Transactional
    public void deleteBill(Long id, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new RuntimeException("Bill not found"));

        if ("Paid".equals(bill.getStatus())) {
            throw new RuntimeException("Cannot delete paid bills");
        }

        for (BillItemEntity item : bill.getItems()) {
            if (item.getPoItemId() != null) {
                purchaseOrderItemRepository.findById(item.getPoItemId())
                    .ifPresent(poItem -> {
                        BigDecimal newDeliveredQty = poItem.getDeliveredQty()
                                .subtract(item.getQuantity());

                        if (newDeliveredQty.compareTo(BigDecimal.ZERO) < 0) {
                            newDeliveredQty = BigDecimal.ZERO;
                        }

                        poItem.setDeliveredQty(newDeliveredQty);
                        purchaseOrderItemRepository.save(poItem);

                        log.info("Restored PO item {} - Delivered: {} (reduced by {})",
                                 poItem.getId(), poItem.getDeliveredQty(), item.getQuantity());
                    });
            }
        }

        bill.setDeletedAt(LocalDateTime.now());
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);

        if (bill.getPoId() != null) {
            updatePOStatusAfterBill(bill.getPoId());
        }

        // ── Sync project bill stats (replaces deleted trigger) ──────────────
        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            syncProjectBillStats(bill.getProjectId());
        }

        log.info("Deleted bill: {} and restored PO item quantities", bill.getBillNo());
    }

    // =========================================================================
    // FILE UPLOAD
    // =========================================================================

    @Transactional
    public String uploadBillFile(Long billId, MultipartFile file, Long userId) throws IOException {
        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new RuntimeException("File size exceeds 10MB limit");
        }

        String contentType = file.getContentType();
        if (!isValidFileType(contentType)) {
            throw new RuntimeException("Invalid file type. Only PDF, PNG, JPG, JPEG allowed");
        }

        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        String originalFilename = file.getOriginalFilename();

        // Store file bytes directly in DB (MEDIUMBLOB — up to 16 MB)
        bill.setBillFileData(file.getBytes());
        bill.setBillFileContentType(contentType);
        bill.setBillFileName(originalFilename);
        bill.setBillFileSize(file.getSize());
        // Keep billFilePath null for new uploads — signals "use BLOB"
        bill.setBillFilePath(null);
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        billRepository.save(bill);

        log.info("Uploaded file for bill: {} by user: {} (stored as BLOB, {} bytes)",
                 bill.getBillNo(), userId, file.getSize());
        return "blob:" + billId; // caller just needs a non-null truthy value
    }

    /**
     * Returns the raw file bytes for a bill, regardless of where the file lives.
     * Priority: BLOB column → disk fallback (legacy files).
     */
    public byte[] getBillFileBytes(Long billId) throws IOException {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        // New path: bytes in DB
        if (bill.getBillFileData() != null && bill.getBillFileData().length > 0) {
            return bill.getBillFileData();
        }

        // Legacy fallback: read from disk
        if (bill.getBillFilePath() != null && !bill.getBillFilePath().isBlank()) {
            Path filePath = Paths.get(bill.getBillFilePath());
            if (Files.exists(filePath)) {
                return Files.readAllBytes(filePath);
            }
        }

        throw new RuntimeException("No file found for bill: " + billId);
    }

    /**
     * Returns the resolved content type for a bill file.
     */
    public String getBillFileContentType(Long billId) throws IOException {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        if (bill.getBillFileContentType() != null) {
            return bill.getBillFileContentType();
        }
        // Probe from legacy file on disk
        if (bill.getBillFilePath() != null) {
            Path p = Paths.get(bill.getBillFilePath());
            String ct = Files.probeContentType(p);
            return ct != null ? ct : "application/octet-stream";
        }
        return "application/octet-stream";
    }

    // =========================================================================
    // PAYMENTS
    // =========================================================================

    @Transactional
    public BillDTO addPayment(Long billId, PaymentDTO paymentDTO, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        BigDecimal paymentAmount = paymentDTO.getAmount();
        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Payment amount must be greater than zero");
        }

        BigDecimal balanceAmount = bill.getTotalAmount().subtract(bill.getPaidAmount());
        if (paymentAmount.compareTo(balanceAmount) > 0) {
            throw new RuntimeException("Payment amount exceeds balance: " + balanceAmount);
        }

        BillPaymentEntity payment = BillPaymentEntity.builder()
                .bill(bill)
                .paymentDate(paymentDTO.getPaymentDate())
                .paymentMode(paymentDTO.getPaymentMode())
                .referenceNumber(paymentDTO.getReferenceNumber())
                .amount(paymentAmount)
                .paidBy(userId)
                .createdAt(LocalDateTime.now())
                .notes(paymentDTO.getNotes())
                .build();

        bill.addPayment(payment);
        bill.setPaidAmount(bill.getPaidAmount().add(paymentAmount));
        bill.recalculateStatus();
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);

        log.info("Added payment of {} to bill: {} by user: {}", paymentAmount, bill.getBillNo(), userId);

        // FIX: Sync project paid_bill_value immediately after payment is recorded.
        // paid_bill_value = SUM(bill_payments) + SUM(vendor_advances) for the project,
        // so a single sync correctly reflects the new payment without drift.
        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            syncProjectBillStats(bill.getProjectId());
            log.info("Synced project [{}] paid_bill_value after payment of {}", bill.getProjectId(), paymentAmount);
        }

        return enrichBillEntity(bill);
    }

    @Transactional
    public BillDTO markAsPaid(Long billId, Long userId) {
        BillEntity bill = billRepository.findByIdAndNotDeleted(billId)
                .orElseThrow(() -> new RuntimeException("Bill not found: " + billId));

        if ("Paid".equals(bill.getStatus())) {
            throw new RuntimeException("Bill is already paid");
        }

        BigDecimal remainingAmount = bill.getTotalAmount().subtract(bill.getPaidAmount());

        // Block silent ghost-payment creation: if there is still a balance due,
        // the user must record it via Add Payment (with proper receipt details) first.
        if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new RuntimeException(
                "Bill still has a pending balance of ₹" + remainingAmount
                + ". Please record the receipt via 'Add Payment' before marking as paid.");
        }

        // Balance is already 0 — just flip the status
        bill.setStatus("Paid");
        bill.setUpdatedBy(userId);
        bill.setUpdatedAt(LocalDateTime.now());
        bill = billRepository.save(bill);

        log.info("Marked bill as paid: {} by user: {}", bill.getBillNo(), userId);

        // FIX: Sync project stats after markAsPaid
        if (bill.getProjectId() != null && !bill.getProjectId().isBlank()) {
            syncProjectBillStats(bill.getProjectId());
            log.info("Synced project [{}] paid_bill_value after markAsPaid", bill.getProjectId());
        }

        return enrichBillEntity(bill);
    }

    // =========================================================================
    // STATISTICS
    // FIX 3: Accept userId + userRole so stats are scoped to what the user can
    //        actually see. A user without PO access but with bill menu access was
    //        seeing global KPI numbers because the stats endpoint had no
    //        role/access filter. Now non-admin users only see stats for the
    //        project/group scope they belong to.
    // =========================================================================

    @Transactional(readOnly = true)
    public BillStatsDTO getStatistics(
            String projectId,
            String groupId,
            String subGroupId,
            Long userId,
            String userRole
    ) {
        return getStatistics(projectId, groupId, subGroupId, userId, userRole, null, null);
    }

    public BillStatsDTO getStatistics(
            String projectId,
            String groupId,
            String subGroupId,
            Long userId,
            String userRole,
            String statusFilter
    ) {
        return getStatistics(projectId, groupId, subGroupId, userId, userRole, statusFilter, null);
    }

    public BillStatsDTO getStatistics(
            String projectId, String groupId, String subGroupId,
            Long userId, String userRole,
            String statusFilter, String searchTerm
    ) {
        return getStatistics(projectId, groupId, subGroupId, userId, userRole, statusFilter, searchTerm, null, null);
    }

    public BillStatsDTO getStatistics(
            String projectId, String groupId, String subGroupId,
            Long userId, String userRole,
            String statusFilter, String searchTerm,
            String billDateFromStr, String billDateToStr
    ) {
        try {
            log.info("Calculating bill statistics for projectId: {}, groupId: {}, subGroupId: {}, " +
                     "userId: {}, userRole: {}, statusFilter: {}, searchTerm: {}", projectId, groupId, subGroupId, userId, userRole, statusFilter, searchTerm);

            boolean isAdmin = isAdminRole(userRole);

            // Normalize empty/all to null for null-safe query
            String statusF = (statusFilter != null && !statusFilter.trim().isEmpty() && !"all".equalsIgnoreCase(statusFilter.trim())) ? statusFilter.trim() : null;
            String searchF = (searchTerm  != null && !searchTerm.trim().isEmpty()) ? searchTerm.trim() : null;

            long totalBills; BigDecimal totalAmount, paidAmount;

            // Parse date range
            java.time.LocalDate fromDate = null, toDate = null;
            if (billDateFromStr != null && !billDateFromStr.isBlank()) { try { fromDate = java.time.LocalDate.parse(billDateFromStr); } catch (Exception ignored) {} }
            if (billDateToStr   != null && !billDateToStr.isBlank())   { try { toDate   = java.time.LocalDate.parse(billDateToStr);   } catch (Exception ignored) {} }

            if (projectId != null && !projectId.isEmpty()) {
                totalBills  = billRepository.countBillsWithStatus(projectId, null, null, statusF, searchF, fromDate, toDate);
                totalAmount = billRepository.sumTotalAmountWithStatus(projectId, null, null, statusF, searchF, fromDate, toDate);
                paidAmount  = billRepository.sumPaidAmountWithStatus(projectId, null, null, statusF, searchF, fromDate, toDate);
            } else if (isAdmin) {
                totalBills  = billRepository.countBillsWithStatus(null, groupId, subGroupId, statusF, searchF, fromDate, toDate);
                totalAmount = billRepository.sumTotalAmountWithStatus(null, groupId, subGroupId, statusF, searchF, fromDate, toDate);
                paidAmount  = billRepository.sumPaidAmountWithStatus(null, groupId, subGroupId, statusF, searchF, fromDate, toDate);
            } else {
                List<String> accessibleProjectIds = projectAccessService.getAccessibleProjectIds(userId, userRole);
                if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
                    totalBills  = billRepository.countAccessibleBillsWithStatus(accessibleProjectIds, groupId, subGroupId, statusF, searchF, fromDate, toDate);
                    totalAmount = billRepository.sumAccessibleTotalAmountWithStatus(accessibleProjectIds, groupId, subGroupId, statusF, searchF, fromDate, toDate);
                    paidAmount  = billRepository.sumAccessiblePaidAmountWithStatus(accessibleProjectIds, groupId, subGroupId, statusF, searchF, fromDate, toDate);
                } else {
                    // No grants — return zeros to avoid showing all data
                    log.warn("Non-admin user {} has no project grants — returning zero bill stats.", userId);
                    return BillStatsDTO.builder()
                            .totalBills(0).totalAmount(BigDecimal.ZERO)
                            .paidAmount(BigDecimal.ZERO).pendingAmount(BigDecimal.ZERO).build();
                }
            }

            if (totalAmount == null) totalAmount = BigDecimal.ZERO;
            if (paidAmount  == null) paidAmount  = BigDecimal.ZERO;
            BigDecimal pendingAmount = totalAmount.subtract(paidAmount).max(BigDecimal.ZERO);

            log.info("Bill stats — total: {}, totalAmount: {}, paidAmount: {}, pendingAmount: {}",
                     totalBills, totalAmount, paidAmount, pendingAmount);

            return BillStatsDTO.builder()
                    .totalBills(totalBills)
                    .totalAmount(totalAmount)
                    .paidAmount(paidAmount)
                    .pendingAmount(pendingAmount)
                    .build();

        } catch (Exception e) {
            log.error("Error calculating bill statistics", e);
            return BillStatsDTO.builder()
                    .totalBills(0)
                    .totalAmount(BigDecimal.ZERO)
                    .paidAmount(BigDecimal.ZERO)
                    .pendingAmount(BigDecimal.ZERO)
                    .build();
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private BillDTO enrichBillEntity(BillEntity bill) {
        BillDTO dto = new BillDTO();
        dto.setId(bill.getId());
        dto.setBillNo(bill.getBillNo());
        dto.setBillRefId(bill.getBillRefId());
        dto.setVendorId(bill.getVendorId());
        dto.setPoId(bill.getPoId());
        dto.setBillDate(bill.getBillDate());
        dto.setDueDate(bill.getDueDate());
        dto.setTotalAmount(bill.getTotalAmount());
        dto.setPaidAmount(bill.getPaidAmount());
        dto.setBalanceAmount(bill.getBalanceAmount());
        dto.setStatus(bill.getStatus());
        dto.setProjectId(bill.getProjectId());
        dto.setGroupId(bill.getGroupId());
        dto.setSubGroupId(bill.getSubGroupId());
        dto.setNotes(bill.getNotes());
        dto.setBillFilePath(bill.getBillFilePath());
        dto.setBillFileName(bill.getBillFileName());
        dto.setBillFileSize(bill.getBillFileSize());
        dto.setUploadedOn(bill.getUploadedOn());
        dto.setCreatedAt(bill.getCreatedAt());
        dto.setUpdatedAt(bill.getUpdatedAt());

        // ── Warehouse-outward fields ─────────────────────────────────────────
        dto.setSourceType(bill.getSourceType() != null ? bill.getSourceType() : "VENDOR");
        dto.setWarehouseId(bill.getWarehouseId());
        dto.setInvTxnRef(bill.getInvTxnRef());

        if ("WAREHOUSE".equalsIgnoreCase(bill.getSourceType())) {
            // For warehouse bills: enrich warehouse name instead of vendor
            if (bill.getWarehouseId() != null) {
                warehouseRepository.findById(bill.getWarehouseId()).ifPresent(wh ->
                    dto.setWarehouseName(wh.getName())
                );
            }
        } else {
            // Normal vendor bill
            if (bill.getVendorId() != null) {
                vendorRepository.findById(bill.getVendorId()).ifPresent(vendor ->
                    dto.setVendorName(vendor.getName())
                );
            }
        }

        if (bill.getPoId() != null) {
            purchaseOrderRepository.findById(bill.getPoId()).ifPresent(po -> {
                dto.setPoNumber(po.getPoNo());
                dto.setPoRefId(po.getPoRefId());
                dto.setQuotationId(po.getQuotationId() != null ? po.getQuotationId().toString() : null);
            });
        }

        if (bill.getUploadedBy() != null) {
            usersRepo.findById(bill.getUploadedBy()).ifPresent(user ->
                dto.setUploadedByName(user.getName())
            );
        }

        List<BillItemDTO> items = bill.getItems().stream()
                .map(item -> {
                    BillItemDTO itemDTO = new BillItemDTO();
                    itemDTO.setId(item.getId());
                    itemDTO.setPoItemId(item.getPoItemId());
                    itemDTO.setDescription(item.getDescription());
                    itemDTO.setQuantity(item.getQuantity());
                    itemDTO.setUnitPrice(item.getUnitPrice());
                    itemDTO.setTaxPercent(item.getTaxPercent());
                    itemDTO.setLineTotal(item.getLineTotal());
                    // Use stored itemName first; for PO-linked items also try live PO lookup
                    itemDTO.setItemName(item.getItemName());
                    if (item.getPoItemId() != null && (itemDTO.getItemName() == null || itemDTO.getItemName().isBlank())) {
                        purchaseOrderItemRepository.findById(item.getPoItemId()).ifPresent(poItem ->
                            itemDTO.setItemName(poItem.getItemName())
                        );
                    }
                    // Last resort: use description so the field is never empty
                    if (itemDTO.getItemName() == null || itemDTO.getItemName().isBlank()) {
                        itemDTO.setItemName(item.getDescription());
                    }
                    return itemDTO;
                })
                .collect(Collectors.toList());
        dto.setItems(items);

        List<PaymentHistoryDTO> payments = bill.getPayments().stream()
                .map(payment -> {
                    PaymentHistoryDTO paymentDTO = new PaymentHistoryDTO();
                    paymentDTO.setId(payment.getId());
                    paymentDTO.setPaymentDate(payment.getPaymentDate());
                    paymentDTO.setPaymentMode(payment.getPaymentMode());
                    paymentDTO.setReferenceNumber(payment.getReferenceNumber());
                    paymentDTO.setAmount(payment.getAmount());

                    if (payment.getPaidBy() != null) {
                        usersRepo.findById(payment.getPaidBy()).ifPresent(user ->
                            paymentDTO.setPaidByName(user.getName())
                        );
                    }

                    return paymentDTO;
                })
                .collect(Collectors.toList());
        dto.setPaymentHistory(payments);

        return dto;
    }

    private void recalculateBillTotal(BillEntity bill) {
        BigDecimal subtotal = bill.getItems().stream()
                .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal taxAmount = bill.getItems().stream()
                .map(BillItemEntity::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        bill.setTotalAmount(subtotal.add(taxAmount));
    }

    private String generateBillNumber() {
        String year   = String.valueOf(LocalDateTime.now().getYear());
        String prefix = "BILL-" + year + "-";
        String maxBillNo = billRepository.findMaxBillNoWithPrefix(prefix + "%");

        int nextNumber = 1;
        if (maxBillNo != null) {
            String numberPart = maxBillNo.substring(maxBillNo.lastIndexOf("-") + 1);
            nextNumber = Integer.parseInt(numberPart) + 1;
        }

        return prefix + String.format("%03d", nextNumber);
    }

    private boolean isValidFileType(String contentType) {
        return contentType != null && (
                contentType.equals("application/pdf") ||
                contentType.equals("image/png")       ||
                contentType.equals("image/jpeg")      ||
                contentType.equals("image/jpg")
        );
    }

    // =========================================================================
    // SYNC PROJECT BILL STATS  (replaces dropped DB triggers)
    // Delegates to ProjectStatsService which reloads the entity fresh,
    // sets all bill fields directly, and saves via JPA — no native SQL issues.
    // =========================================================================

    @Transactional
    public void syncProjectBillStats(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("syncProjectBillStats called with null/blank projectId – skipping");
            return;
        }
        try {
            projectStatsService.updateProjectAfterBillPayment(projectId);
            log.info("Synced bill stats for project [{}] via ProjectStatsService", projectId);
        } catch (Exception e) {
            log.error("Failed to sync bill stats for project [{}]: {}", projectId, e.getMessage(), e);
        }
    }

    /**
     * Returns true when the user should see ALL data without project-access filtering.
     * Rules:
     *   1. ADMIN / SUPERADMIN always bypass.
     *   2. Any role whose name starts with ACCOUNTS_ bypasses (covers ACCOUNTS_EXECUTIVE,
     *      ACCOUNTS_MANAGER, ACCOUNTS_CFO, or any future ACCOUNTS_* role).
     *   3. Any role whose level_order in role_hierarchy is <= 2 bypasses
     *      (top-level roles configured dynamically in the DB).
     */
    private boolean isAdminRole(String userRole) {
        if (userRole == null) return false;
        String r = userRole.trim().toUpperCase();
        if (r.equals("ADMIN") || r.equals("SUPERADMIN")) return true;
        if (r.startsWith("ACCOUNTS_")) return true;
        return roleHierarchyRepo.findLevelOrderByRoleName(RoleNormalizer.normalize(userRole))
                .map(level -> level <= 2)
                .orElse(false);
    }

    // =========================================================================
    // CREATE WAREHOUSE BILL  (auto-generated when OUTWARD transaction is saved)
    //
    // Creates a bill in the bills table with:
    //   source_type  = WAREHOUSE
    //   vendor_id    = NULL
    //   warehouse_id = the issuing warehouse
    //   paid_amount  = total_amount  (fully paid — stock was already purchased)
    //   status       = Paid
    //   due_date     = today (no outstanding balance)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillDTO createWarehouseBill(
            Long warehouseId,
            String warehouseName,
            String projectId,
            String groupId,
            String subGroupId,
            List<InvTransactionWrapper.OutwardLine> lines,
            List<String> itemNames,
            List<String> units,
            String invTxnRef,
            Long userId) {

        log.info("Auto-creating warehouse bill for warehouse={}, project={}, txnRef={}",
                 warehouseId, projectId, invTxnRef);

        BillEntity bill = new BillEntity();
        bill.setBillNo("__TEMP_WH_" + System.nanoTime() + "__");
        bill.setSourceType("WAREHOUSE");
        bill.setWarehouseId(warehouseId);
        bill.setVendorId(null); // no vendor for warehouse issuances
        bill.setPoId(null);
        bill.setBillDate(LocalDate.now());
        bill.setDueDate(LocalDate.now());
        bill.setStatus("Pending"); // recalculated below after setting paid amount
        bill.setProjectId(projectId);
        bill.setGroupId(groupId);
        bill.setSubGroupId(subGroupId);
        bill.setNotes("Auto-generated from warehouse issuance. Warehouse: " + warehouseName
                      + ". Ref: " + invTxnRef);
        bill.setInvTxnRef(invTxnRef);
        bill.setCreatedBy(userId);
        bill.setCreatedAt(LocalDateTime.now());
        bill.setUploadedBy(userId);
        bill.setUploadedOn(LocalDateTime.now());
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setPaidAmount(BigDecimal.ZERO);

        // First save to get DB id
        bill = billRepository.save(bill);
        String billNo = String.format("BILL-%d-%04d", LocalDate.now().getYear(), bill.getId());
        bill.setBillNo(billNo);
        bill = billRepository.save(bill);

        // Add line items
        for (int i = 0; i < lines.size(); i++) {
            InvTransactionWrapper.OutwardLine line = lines.get(i);
            BigDecimal qty      = line.getQty() != null ? line.getQty().abs() : BigDecimal.ZERO;
            BigDecimal unitCost = line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;
            String itemName     = (itemNames != null && i < itemNames.size()) ? itemNames.get(i) : "Item";
            String unit         = (units != null && i < units.size()) ? units.get(i) : "";

            BillItemEntity item = new BillItemEntity();
            item.setItemName(itemName);
            item.setDescription(unit.isBlank() ? itemName : itemName + " (" + unit + ")");
            item.setQuantity(qty);
            item.setUnitPrice(unitCost);
            item.setTaxPercent(BigDecimal.ZERO);
            bill.addItem(item);
        }

        // Calculate total — leave status=Pending, paidAmount=0.
        // Payment will be recorded via VendorAdvanceService.createAdvance
        // (BILL_PAYMENT type) which updates paidAmount + status to Paid.
        recalculateBillTotal(bill);

        bill = billRepository.saveAndFlush(bill);

        log.info("Created warehouse bill {} for project {} (total={})",
                 bill.getBillNo(), projectId, bill.getTotalAmount());

        // Sync project stats so totalSpent reflects this issuance
        if (projectId != null && !projectId.isBlank()) {
            syncProjectBillStats(projectId);
        }

        return enrichBillEntity(bill);
    }

}