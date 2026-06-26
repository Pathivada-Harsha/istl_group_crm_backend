package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InvTransactionWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvTransactionService {

    private final InvTransactionRepository txnRepository;
    private final InventoryItemRepository  itemRepository;
    private final WarehouseRepository      warehouseRepository;
    private final InvPurchaseOrderItemRepository invPoItemRepository;
    private final BillRepository           billRepository;

    @Autowired @Lazy
    private BillService billService;

    @Autowired @Lazy
    private WarehousePaymentSyncService warehousePaymentSyncService;

    private static final Set<String> VALID_TYPES =
        Set.of("INWARD", "OUTWARD", "TRANSFER", "ADJUSTMENT", "RETURN");

    // ── List (paginated + filtered) ───────────────────────────────────────────

    public Map<String, Object> list(Long warehouseId, String groupName, String subGroupName,
                                    String projectId, String type, Long inventoryItemId,
                                    String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<InvTransactionEntity> result = txnRepository.findFiltered(
            warehouseId,
            nb(groupName), nb(subGroupName), nb(projectId),
            nb(type), inventoryItemId, nb(search),
            pageable
        );

        Map<Long, String> whNames = buildWhNames(result.getContent().stream()
            .flatMap(t -> {
                List<Long> ids = new ArrayList<>();
                if (t.getWarehouseId()     != null) ids.add(t.getWarehouseId());
                if (t.getFromWarehouseId() != null) ids.add(t.getFromWarehouseId());
                return ids.stream();
            })
            .collect(Collectors.toSet()));

        List<InvTransactionWrapper> content = result.getContent().stream()
            .map(t -> InvTransactionWrapper.from(t,
                whNames.get(t.getWarehouseId()),
                whNames.get(t.getFromWarehouseId()),
                null))
            .collect(Collectors.toList());

        return Map.of(
            "content",       content,
            "totalElements", result.getTotalElements(),
            "totalPages",    result.getTotalPages(),
            "size",          result.getSize(),
            "number",        result.getNumber()
        );
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Records one stock movement and updates inventory_items.current_qty.
     *
     * Qty sign convention (applied to stock):
     *   INWARD              → +qty (stock increases)
     *   OUTWARD / TRANSFER  → -qty (stock decreases)
     *   RETURN              → +qty (stock increases)
     *   ADJUSTMENT          → qty as-is (caller sends signed value)
     *
     * Each save uses its own Spring Data transaction (no outer @Transactional
     * on this method) so a failure here affects only this row.
     */
    public InvTransactionWrapper create(InvTransactionWrapper req, Long createdBy) {
        // ── Validate ─────────────────────────────────────────────────────────
        if (req.getType() == null || !VALID_TYPES.contains(req.getType().toUpperCase()))
            throw new IllegalArgumentException("Invalid transaction type: " + req.getType());

        BigDecimal rawQty = req.getQty();
        if (rawQty == null || rawQty.compareTo(BigDecimal.ZERO) == 0)
            throw new IllegalArgumentException("Quantity must be non-zero");

        // Resolve itemId — frontend may send either inventoryItemId or itemId
        Long itemId = req.getInventoryItemId() != null ? req.getInventoryItemId() : req.getItemId();
        if (itemId == null)
            throw new IllegalArgumentException("Item is required");

        InventoryItemEntity item = itemRepository.findById(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + itemId));

        String type = req.getType().toUpperCase();

        // Compute the signed delta applied to stock
        BigDecimal absQty = rawQty.abs();
        BigDecimal delta;
        switch (type) {
            case "INWARD":
            case "RETURN":
                delta = absQty;
                break;
            case "OUTWARD":
            case "TRANSFER":
                delta = absQty.negate();
                break;
            case "ADJUSTMENT":
                delta = rawQty; // caller sends signed value
                break;
            default:
                delta = rawQty;
        }

        // Prevent stock from going below zero for outward movements
        BigDecimal newQty = item.getCurrentQty().add(delta);
        if ("OUTWARD".equals(type) || "TRANSFER".equals(type)) {
            if (newQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                    String.format("Insufficient stock: current %.3f %s, requested %.3f",
                        item.getCurrentQty().doubleValue(), item.getUnit(), absQty.doubleValue()));
            }
        }
        if (newQty.compareTo(BigDecimal.ZERO) < 0) newQty = BigDecimal.ZERO; // safety clamp

        // ── Resolve ref (frontend sends either refNo or ref) ─────────────────
        String refNo = req.getRefNo() != null ? req.getRefNo() : req.getRef();

        // ── Resolve group / subGroupName with warehouse as last-resort fallback ─
        // Priority: (1) request field  (2) item field  (3) warehouse field
        // This ensures the value is stored even when the server's warehouse
        // records are missing group/subGroupName (common in fresh deployments).
        String resolvedGroupName    = nb(req.getGroupName())    != null ? req.getGroupName()
                                    : nb(item.getGroupName())   != null ? item.getGroupName()
                                    : null;
        String resolvedSubGroupName = nb(req.getSubGroupName()) != null ? req.getSubGroupName()
                                    : nb(item.getSubGroupName()) != null ? item.getSubGroupName()
                                    : null;

        // If still null, fall back to the item's warehouse
        if (resolvedGroupName == null || resolvedSubGroupName == null) {
            Long whId = item.getWarehouseId();
            if (whId != null) {
                WarehouseEntity wh = warehouseRepository.findById(whId).orElse(null);
                if (wh != null) {
                    if (resolvedGroupName    == null) resolvedGroupName    = nb(wh.getGroupName());
                    if (resolvedSubGroupName == null) resolvedSubGroupName = nb(wh.getSubGroupName());
                }
            }
        }

        // ── Build entity ─────────────────────────────────────────────────────
        InvTransactionEntity entity = InvTransactionEntity.builder()
            .txnNo("PENDING")
            .type(type)
            .inventoryItemId(item.getId())
            .itemCode(item.getItemCode())
            .itemName(item.getName())
            .unit(item.getUnit())
            .qty(delta)
            .warehouseId(item.getWarehouseId())
            .fromWarehouseId(req.getFromWarehouseId())
            .groupName(resolvedGroupName)
            .subGroupName(resolvedSubGroupName)
            .projectId(nb(req.getProjectId()))
            .refNo(nb(refNo))
            .notes(req.getNotes() != null ? req.getNotes() : req.getNote())
            .transactionDate(req.getTransactionDate() != null ? req.getTransactionDate() : LocalDate.now())
            .createdBy(createdBy)
            .build();

        // ── Persist transaction ───────────────────────────────────────────────
        InvTransactionEntity saved = txnRepository.saveAndFlush(entity);
        String txnNo = String.format("INV-TXN-%d-%05d", LocalDate.now().getYear(), saved.getId());
        saved.setTxnNo(txnNo);
        saved = txnRepository.saveAndFlush(saved);

        // ── Update stock ──────────────────────────────────────────────────────
        item.setCurrentQty(newQty);
        itemRepository.save(item);

        log.info("{} {} {} {} (item={}, wh={}) → stock now {}",
            txnNo, type, delta.abs(), item.getUnit(), item.getItemCode(),
            item.getWarehouseId(), newQty);

        String whName = warehouseRepository.findById(item.getWarehouseId())
            .map(WarehouseEntity::getName).orElse(null);

        return InvTransactionWrapper.from(saved, whName, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Voids a transaction by posting a reverse entry (opposite qty),
     * restoring the item's stock, then marking the original as VOIDED.
     * The reverse entry and the original both stay visible for the audit trail.
     */
    public Map<String, Object> voidTransaction(Long id, Long userId) {
        InvTransactionEntity original = txnRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));

        if (original.getNotes() != null && original.getNotes().startsWith("VOIDED"))
            throw new IllegalArgumentException("Transaction is already voided");

        // Find the item and reverse the stock effect
        if (original.getInventoryItemId() != null) {
            itemRepository.findById(original.getInventoryItemId()).ifPresent(item -> {
                BigDecimal reversed = original.getQty().negate();
                BigDecimal newQty   = item.getCurrentQty().add(reversed);
                item.setCurrentQty(newQty.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newQty);
                itemRepository.save(item);
            });
        }

        // Mark original as voided
        original.setNotes("VOIDED — reversed by user " + userId + ". " + (original.getNotes() != null ? original.getNotes() : ""));
        txnRepository.save(original);

        // Create a reverse entry for the audit trail
        InvTransactionEntity rev = InvTransactionEntity.builder()
            .txnNo("PENDING")
            .type(original.getType())
            .inventoryItemId(original.getInventoryItemId())
            .itemCode(original.getItemCode())
            .itemName(original.getItemName())
            .unit(original.getUnit())
            .qty(original.getQty().negate())
            .warehouseId(original.getWarehouseId())
            .groupName(original.getGroupName())
            .subGroupName(original.getSubGroupName())
            .projectId(original.getProjectId())
            .refNo("VOID/" + original.getTxnNo())
            .notes("Reversal of " + original.getTxnNo())
            .transactionDate(java.time.LocalDate.now())
            .createdBy(userId)
            .build();
        InvTransactionEntity savedRev = txnRepository.saveAndFlush(rev);
        savedRev.setTxnNo(String.format("INV-TXN-%d-%05d-V", java.time.LocalDate.now().getYear(), savedRev.getId()));
        txnRepository.save(savedRev);

        log.info("Voided transaction {} — reverse entry {} created", original.getTxnNo(), savedRev.getTxnNo());
        return Map.of("success", true, "message", "Transaction voided", "reversalTxnNo", savedRev.getTxnNo());
    }

    private Map<Long, String> buildWhNames(Set<Long> ids) {
        Map<Long, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        warehouseRepository.findAllById(ids).forEach(w -> m.put(w.getId(), w.getName()));
        return m;
    }

    private static String nb(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ── Update (editable metadata only — qty/item/warehouse are immutable) ────

    @Transactional
    public InvTransactionWrapper update(Long id, InvTransactionWrapper req) {
        InvTransactionEntity entity = txnRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));

        if (entity.getNotes() != null && entity.getNotes().startsWith("VOIDED"))
            throw new IllegalArgumentException("Cannot edit a voided transaction");

        if (req.getTransactionDate() != null) entity.setTransactionDate(req.getTransactionDate());
        if (nb(req.getRefNo())  != null) entity.setRefNo(nb(req.getRefNo()));
        if (nb(req.getRef())    != null) entity.setRefNo(nb(req.getRef()));
        if (req.getNotes()      != null) entity.setNotes(req.getNotes());
        if (req.getNote()       != null) entity.setNotes(req.getNote());
        if (nb(req.getProjectId())   != null) entity.setProjectId(nb(req.getProjectId()));
        if (nb(req.getGroupName())   != null) entity.setGroupName(nb(req.getGroupName()));
        if (nb(req.getSubGroupName())!= null) entity.setSubGroupName(nb(req.getSubGroupName()));

        InvTransactionEntity saved = txnRepository.save(entity);
        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvTransactionWrapper.from(saved, whName, null, null);
    }

    public InvTransactionWrapper getById(Long id) {
        InvTransactionEntity entity = txnRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));
        String whName = entity.getWarehouseId() != null
            ? warehouseRepository.findById(entity.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvTransactionWrapper.from(entity, whName, null, null);
    }

    // ── Hard Delete ───────────────────────────────────────────────────────────
    /**
     * Permanently deletes a transaction and reverses its stock effect.
     * Unlike voidTransaction, this removes the row entirely — no reversal entry created.
     * Stock is restored: INWARD/RETURN → qty subtracted; OUTWARD/TRANSFER → qty added back.
     */
    @Transactional
    public Map<String, Object> deleteTransaction(Long id, Long userId) {
        InvTransactionEntity entity = txnRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id));

        String txnNo     = entity.getTxnNo();
        String projectId = entity.getProjectId();
        String type      = entity.getType();

        // ── 1. Reverse the stock effect ───────────────────────────────────────
        if (entity.getInventoryItemId() != null) {
            itemRepository.findById(entity.getInventoryItemId()).ifPresent(item -> {
                // entity.qty is already signed (positive for INWARD, negative for OUTWARD).
                // Reversing = negate the stored delta back onto currentQty.
                BigDecimal reversed = entity.getQty().negate();
                BigDecimal newQty   = item.getCurrentQty().add(reversed);
                item.setCurrentQty(newQty.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newQty);
                itemRepository.save(item);
                log.info("Stock reversed for item {} after deleting txn {}: delta={}, newQty={}",
                    item.getItemCode(), txnNo, reversed, item.getCurrentQty());
            });
        }

        // ── 2. Delete the transaction row ─────────────────────────────────────
        txnRepository.deleteById(id);
        log.info("Transaction {} permanently deleted by user {}", txnNo, userId);

        // ── 3. For OUTWARD: cascade-delete auto-generated warehouse bill ──────
        List<String> deletedBillNos = new ArrayList<>();
        if ("OUTWARD".equals(type) && txnNo != null) {
            try {
                List<BillEntity> warehouseBills = billRepository.findWarehouseBillsByTxnRef(txnNo);
                for (BillEntity bill : warehouseBills) {
                    // Soft-delete the bill (bypass Paid status guard for WAREHOUSE bills)
                    bill.setDeletedAt(java.time.LocalDateTime.now());
                    bill.setUpdatedAt(java.time.LocalDateTime.now());
                    bill.setUpdatedBy(userId);
                    billRepository.save(bill);
                    deletedBillNos.add(bill.getBillNo());
                    log.info("Cascade-deleted warehouse bill {} (linked to txn {})", bill.getBillNo(), txnNo);
                }
            } catch (Exception e) {
                log.warn("Failed to cascade-delete warehouse bills for txn {}: {}", txnNo, e.getMessage());
            }
        }

        // ── 4. Sync project stats so dashboard/reports reflect deletion ───────
        if (projectId != null && !projectId.isBlank()) {
            try {
                billService.syncProjectBillStats(projectId);
                log.info("Synced project stats for {} after deleting txn {}", projectId, txnNo);
            } catch (Exception e) {
                log.warn("Failed to sync project stats for {} after txn delete: {}", projectId, e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Transaction deleted");
        result.put("txnNo", txnNo != null ? txnNo : id.toString());
        result.put("deletedBillNos", deletedBillNos);
        result.put("projectId", projectId);
        return result;
    }

    // ── Batch INWARD from site (PO-linked, auto-creates inventory items) ──────

    /**
     * Receives multiple items from site into a warehouse.
     * For each line:
     *   1. If inventoryItemId is null (item not in warehouse), create it via InventoryItemService.receive()
     *   2. Create an INWARD transaction
     *   3. Update InvPurchaseOrderItem.receivedQty
     */
    @Transactional
    public Map<String, Object> createBatchInward(InvTransactionWrapper req, Long userId) {
        if (req.getPoLines() == null || req.getPoLines().isEmpty())
            throw new IllegalArgumentException("At least one line item is required");
        if (req.getWarehouseId() == null)
            throw new IllegalArgumentException("Warehouse is required for inward transactions");

        WarehouseEntity wh = warehouseRepository.findById(req.getWarehouseId())
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + req.getWarehouseId()));

        // ── Phase 1: Detect conflicts (items that exist by name) ─────────────
        // Only run conflict detection when user has NOT yet made decisions
        boolean hasUndecidedConflicts = false;
        List<Map<String, Object>> conflictList = new ArrayList<>();

        for (int i = 0; i < req.getPoLines().size(); i++) {
            InvTransactionWrapper.InwardLine line = req.getPoLines().get(i);
            if (line.getQty() == null || line.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            if (line.getInventoryItemId() != null) continue; // already resolved
            if (line.getConflictAction() != null) continue;  // user already decided

            // Check by id, then by code, then by name
            InventoryItemEntity existing = null;
            if (nb(line.getItemCode()) != null) {
                existing = itemRepository.findByWarehouseIdAndItemCode(req.getWarehouseId(), line.getItemCode().trim()).orElse(null);
            }
            if (existing == null && nb(line.getItemName()) != null) {
                existing = itemRepository.findFirstByWarehouseIdAndNameIgnoreCase(req.getWarehouseId(), line.getItemName().trim()).orElse(null);
            }
            if (existing != null) {
                hasUndecidedConflicts = true;
                Map<String, Object> conflict = new HashMap<>();
                conflict.put("lineIndex",        i);
                conflict.put("itemName",          line.getItemName());
                conflict.put("qty",               line.getQty());
                conflict.put("existingItemId",    existing.getId());
                conflict.put("existingItemCode",  existing.getItemCode());
                conflict.put("existingItemName",  existing.getName());
                conflict.put("existingCurrentQty",existing.getCurrentQty());
                conflictList.add(conflict);
            }
        }

        if (hasUndecidedConflicts) {
            // Return conflicts to frontend — do NOT proceed yet
            Map<String, Object> result = new HashMap<>();
            result.put("conflicts", conflictList);
            result.put("requiresConfirmation", true);
            return result;
        }

        // ── Phase 2: Process all lines (conflicts resolved) ──────────────────
        List<String> createdTxnNos = new ArrayList<>();
        int newItemsCreated = 0;
        String sharedRef = nb(req.getRef() != null ? req.getRef() : req.getRefNo());

        for (InvTransactionWrapper.InwardLine line : req.getPoLines()) {
            if (line.getQty() == null || line.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal qty      = line.getQty().abs();
            BigDecimal unitCost = line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;

            // ── Resolve inventory item ──────────────────────────────────────
            InventoryItemEntity item = null;

            // 1. User said ADD → use existing item
            if ("ADD".equalsIgnoreCase(line.getConflictAction()) && line.getExistingItemId() != null) {
                item = itemRepository.findById(line.getExistingItemId()).orElse(null);
            }

            // 2. Explicit inventoryItemId
            if (item == null && line.getInventoryItemId() != null) {
                item = itemRepository.findById(line.getInventoryItemId()).orElse(null);
            }

            // 3. By code
            if (item == null && nb(line.getItemCode()) != null) {
                item = itemRepository.findByWarehouseIdAndItemCode(req.getWarehouseId(), line.getItemCode().trim()).orElse(null);
            }

            // 4. By name (only if user said ADD — CREATE falls through to auto-create)
            if (item == null && "ADD".equalsIgnoreCase(line.getConflictAction()) && nb(line.getItemName()) != null) {
                item = itemRepository.findFirstByWarehouseIdAndNameIgnoreCase(req.getWarehouseId(), line.getItemName().trim()).orElse(null);
            }

            // 5. Auto-create (user said CREATE, or no conflict, or item truly not found)
            if (item == null) {
                // Generate proper code: {whCode}-{year}-{paddedId}
                String placeholder = "PENDING-" + System.nanoTime();
                // Priority: req field → warehouse field — so that the form's
                // group/subGroupName is honoured even if the warehouse record
                // on the server lacks those fields.
                String itemGroupName    = nb(req.getGroupName())    != null ? req.getGroupName()    : wh.getGroupName();
                String itemSubGroupName = nb(req.getSubGroupName()) != null ? req.getSubGroupName() : wh.getSubGroupName();
                item = InventoryItemEntity.builder()
                    .warehouseId(req.getWarehouseId())
                    .itemCode(placeholder)
                    .name(line.getItemName() != null ? line.getItemName().trim() : placeholder)
                    .unit(line.getUnit() != null ? line.getUnit() : "Nos")
                    .currentQty(BigDecimal.ZERO)
                    .minQty(BigDecimal.ZERO)
                    .maxQty(BigDecimal.ZERO)
                    .unitCost(unitCost)
                    .groupName(itemGroupName)
                    .subGroupName(itemSubGroupName)
                    .projectId(nb(req.getProjectId()))
                    .isActive(Boolean.TRUE)
                    .createdBy(userId)
                    .build();
                item = itemRepository.saveAndFlush(item);

                // Generate proper item code same as InventoryItemService.create
                String whPrefix = wh.getCode() != null && !wh.getCode().isBlank()
                    ? wh.getCode().trim().substring(0, Math.min(4, wh.getCode().trim().length())).toUpperCase()
                    : ("WH" + wh.getId());
                int year   = java.time.LocalDate.now().getYear();
                long id    = item.getId();
                int digits = Math.max(4, String.valueOf(id).length());
                String autoCode = whPrefix + "-" + year + "-" + String.format("%0" + digits + "d", id);
                item.setItemCode(autoCode);
                item = itemRepository.saveAndFlush(item);
                newItemsCreated++;
                log.info("Created inventory item {} in warehouse {} from batch-inward", autoCode, req.getWarehouseId());
            }

            // ── Create INWARD transaction ───────────────────────────────────
            InvTransactionWrapper lineReq = InvTransactionWrapper.builder()
                .type("INWARD")
                .inventoryItemId(item.getId())
                .qty(qty)
                .warehouseId(req.getWarehouseId())
                .groupName(nb(req.getGroupName()) != null ? req.getGroupName() : wh.getGroupName())
                .subGroupName(nb(req.getSubGroupName()) != null ? req.getSubGroupName() : wh.getSubGroupName())
                .projectId(req.getProjectId())
                .refNo(sharedRef)
                .notes(req.getNotes() != null ? req.getNotes() : req.getNote())
                .transactionDate(req.getTransactionDate())
                .build();

            InvTransactionWrapper saved = create(lineReq, userId);
            createdTxnNos.add(saved.getTxnNo());

            // ── Update PO item receivedQty ──────────────────────────────────
            if (line.getPoItemId() != null) {
                final BigDecimal qtyFinal = qty;
                invPoItemRepository.findById(line.getPoItemId()).ifPresent(poItem -> {
                    BigDecimal prev = poItem.getReceivedQty() != null ? poItem.getReceivedQty() : BigDecimal.ZERO;
                    poItem.setReceivedQty(prev.add(qtyFinal));
                    invPoItemRepository.save(poItem);
                });
            }
        }

        log.info("Batch inward complete: {} transactions, {} new items created", createdTxnNos.size(), newItemsCreated);

        Map<String, Object> result = new HashMap<>();
        result.put("success",         true);
        result.put("createdTxnNos",   createdTxnNos);
        result.put("itemCount",       createdTxnNos.size());
        result.put("newItemsCreated", newItemsCreated);
        return result;
    }

    // ── Batch OUTWARD + auto-generate warehouse bill ───────────────────────────

    /**
     * Creates multiple OUTWARD transactions in one call (multi-item issuance),
     * then auto-generates a warehouse bill in the bills table for project cost tracking.
     *
     * Frontend calls POST /inventory/transactions/batch-outward with:
     *   type       = OUTWARD
     *   warehouseId
     *   projectId  (required for warehouse bills)
     *   groupName / subGroupName
     *   date / ref / note
     *   lines[]    → each has inventoryItemId, qty, unitCost
     */
    @Transactional
    public Map<String, Object> createBatch(InvTransactionWrapper req, Long userId) {
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new IllegalArgumentException("At least one line item is required");
        }
        if (nb(req.getProjectId()) == null) {
            throw new IllegalArgumentException("Project is required for outward transactions");
        }

        List<String> createdTxnNos = new ArrayList<>();
        List<String> itemNames     = new ArrayList<>();
        List<String> units         = new ArrayList<>();
        List<String> txnNoList     = new ArrayList<>();

        String sharedRef = nb(req.getRef() != null ? req.getRef() : req.getRefNo());

        for (InvTransactionWrapper.OutwardLine line : req.getLines()) {
            // Build a single-item wrapper and delegate to existing create()
            InvTransactionWrapper lineReq = InvTransactionWrapper.builder()
                .type("OUTWARD")
                .inventoryItemId(line.getInventoryItemId())
                .qty(line.getQty())
                .warehouseId(req.getWarehouseId())
                .groupName(req.getGroupName())
                .subGroupName(req.getSubGroupName())
                .projectId(req.getProjectId())
                .refNo(sharedRef)
                .notes(req.getNotes() != null ? req.getNotes() : req.getNote())
                .transactionDate(req.getTransactionDate())
                .build();

            InvTransactionWrapper saved = create(lineReq, userId);
            createdTxnNos.add(saved.getTxnNo());
            txnNoList.add(saved.getTxnNo());
            itemNames.add(saved.getItemName() != null ? saved.getItemName() : "");
            units.add(saved.getUnit() != null ? saved.getUnit() : "");
        }

        // ── Auto-generate warehouse bill ──────────────────────────────────────
        Long warehouseId = req.getWarehouseId();
        // Derive warehouseId from first item's warehouse if not set on request
        if (warehouseId == null && !req.getLines().isEmpty()) {
            Long itemId = req.getLines().get(0).getInventoryItemId();
            if (itemId != null) {
                warehouseId = itemRepository.findById(itemId)
                    .map(InventoryItemEntity::getWarehouseId).orElse(null);
            }
        }
        final Long resolvedWhId = warehouseId;
        String warehouseName = resolvedWhId != null
            ? warehouseRepository.findById(resolvedWhId).map(WarehouseEntity::getName).orElse("Warehouse")
            : "Warehouse";

        String invTxnRef = String.join(", ", txnNoList);
        String billNo = null;
        try {
            com.istlgroup.istl_group_crm_backend.wrapperClasses.BillDTO billDTO =
                billService.createWarehouseBill(
                    resolvedWhId,
                    warehouseName,
                    req.getProjectId(),
                    nb(req.getGroupName()),
                    nb(req.getSubGroupName()),
                    req.getLines(),
                    itemNames,
                    units,
                    invTxnRef,
                    userId
                );
            billNo = billDTO.getBillNo();
            log.info("Warehouse bill {} created for batch outward txns {}", billNo, invTxnRef);
            // ── Record VendorAdvance (BILL_PAYMENT) in its own transaction ──
            try {
                warehousePaymentSyncService.syncPayments(
                    billDTO.getId(),
                    billDTO.getBillNo(),
                    billDTO.getTotalAmount(),
                    invTxnRef,
                    req.getProjectId(),
                    nb(req.getGroupName()),
                    nb(req.getSubGroupName()),
                    resolvedWhId,
                    warehouseName,
                    userId
                );
            } catch (Exception se) {
                log.error("WarehousePaymentSync failed for bill {}: {}", billNo, se.getMessage(), se);
            }
        } catch (Exception e) {
            log.error("Failed to auto-create warehouse bill for txns {}: {}", invTxnRef, e.getMessage(), e);
            // Don't fail the whole transaction — stock was already updated
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("createdTxnNos", createdTxnNos);
        result.put("autoBillNo", billNo);
        result.put("itemCount", createdTxnNos.size());
        return result;
    }
}