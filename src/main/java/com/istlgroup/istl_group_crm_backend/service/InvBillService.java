package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.*;
import com.istlgroup.istl_group_crm_backend.repo.*;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class InvBillService {

    private final InvBillRepository            billRepository;
    private final InvBillItemRepository        billItemRepository;
    private final InvPurchaseOrderRepository   poRepository;
    private final InvPurchaseOrderItemRepository poItemRepository;
    private final InventoryItemRepository      inventoryItemRepository;
    private final InvPaymentRepository         paymentRepository;
    private final WarehouseRepository          warehouseRepository;
    private final InvTransactionRepository     invTransactionRepository;

    // ── List ──────────────────────────────────────────────────────────────────

    public Map<String, Object> list(Long warehouseId, String groupName, String subGroupName,
                                    String projectId, String status, Long vendorId,
                                    String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<InvBillEntity> result = billRepository.findFiltered(
            warehouseId,
            nullIfBlank(groupName), nullIfBlank(subGroupName), nullIfBlank(projectId),
            nullIfBlank(status), vendorId, nullIfBlank(search),
            pageable
        );

        Map<Long, String> poNos      = buildPoNoMap(result.getContent());
        Map<String, String> whNames  = buildWarehouseNameMap(result.getContent().stream()
            .map(InvBillEntity::getWarehouseId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<InvBillWrapper> content = result.getContent().stream()
            .map(e -> InvBillWrapper.from(e, poNos.get(e.getPoId()), whNames.get(String.valueOf(e.getWarehouseId()))))
            .collect(Collectors.toList());

        return Map.of(
            "content", content,
            "totalElements", result.getTotalElements(),
            "totalPages", result.getTotalPages(),
            "size", result.getSize(),
            "number", result.getNumber()
        );
    }

    // ── Get single ────────────────────────────────────────────────────────────

    public InvBillWrapper getById(Long id) {
        InvBillEntity e = billRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + id));
        String poNo = e.getPoId() != null
            ? poRepository.findById(e.getPoId()).map(InvPurchaseOrderEntity::getPoNo).orElse(null)
            : null;
        String whName = e.getWarehouseId() != null
            ? warehouseRepository.findById(e.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvBillWrapper.from(e, poNo, whName);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public InvBillWrapper create(InvBillWrapper req, Long createdBy) {
        if (req.getVendorName() == null || req.getVendorName().isBlank())
            throw new IllegalArgumentException("Vendor name is required");
        if (req.getTotalAmount() == null || req.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Total amount must be positive");

        // If linked to a PO, inherit scope fields from it when not explicitly provided
        InvPurchaseOrderEntity linkedPo = null;
        if (req.getPoId() != null) {
            linkedPo = poRepository.findById(req.getPoId()).orElse(null);
        }

        InvBillEntity entity = new InvBillEntity();
        entity.setBillNo("PENDING");
        entity.setVendorId(req.getVendorId());
        entity.setVendorName(req.getVendorName().trim());
        entity.setPoId(req.getPoId());
        entity.setWarehouseId(req.getWarehouseId() != null ? req.getWarehouseId()
            : (linkedPo != null ? linkedPo.getWarehouseId() : null));
        entity.setGroupName(nullIfBlank(req.getGroupName()) != null ? req.getGroupName()
            : (linkedPo != null ? linkedPo.getGroupName() : null));
        entity.setSubGroupName(nullIfBlank(req.getSubGroupName()) != null ? req.getSubGroupName()
            : (linkedPo != null ? linkedPo.getSubGroupName() : null));
        entity.setProjectId(nullIfBlank(req.getProjectId()) != null ? req.getProjectId()
            : (linkedPo != null ? linkedPo.getProjectId() : null));
        entity.setBillDate(req.getBillDate() != null ? req.getBillDate() : LocalDate.now());
        entity.setDueDate(req.getDueDate());
        entity.setTotalAmount(req.getTotalAmount());
        entity.setPaidAmount(BigDecimal.ZERO);
        entity.setStatus("UNPAID");
        entity.setNotes(req.getNotes());
        entity.setCreatedBy(createdBy);

        // Build line items if provided
        if (req.getItems() != null && !req.getItems().isEmpty()) {
            List<InvBillItemEntity> itemEntities = req.getItems().stream()
                .map(iw -> {
                    InvBillItemEntity ie = new InvBillItemEntity();
                    ie.setBill(entity);
                    ie.setInventoryItemId(iw.getInventoryItemId());
                    ie.setItemCode(iw.getItemCode());
                    ie.setItemName(iw.getItemName());
                    ie.setUnit(iw.getUnit());
                    ie.setQty(iw.getQty() != null ? iw.getQty() : BigDecimal.ZERO);
                    ie.setRate(iw.getRate() != null ? iw.getRate() : BigDecimal.ZERO);
                    ie.setTaxPct(iw.getTaxPct() != null ? iw.getTaxPct() : BigDecimal.ZERO);
                    ie.setNotes(iw.getNotes());
                    return ie;
                }).collect(Collectors.toList());
            entity.setItems(itemEntities);
        }

        InvBillEntity saved = billRepository.saveAndFlush(entity);
        String billNo = String.format("INV-BILL-%d-%05d", LocalDate.now().getYear(), saved.getId());
        saved.setBillNo(billNo);
        saved = billRepository.saveAndFlush(saved);

        log.info("Created inventory bill {} for vendor '{}'", billNo, saved.getVendorName());

        // ── Auto-update PO item receivedQty from delivered quantities in the bill ──
        if (linkedPo != null && saved.getItems() != null && !saved.getItems().isEmpty()) {
            List<InvPurchaseOrderItemEntity> poItems = poItemRepository.findByPurchaseOrderId(linkedPo.getId());
            for (InvBillItemEntity billItem : saved.getItems()) {
                if (billItem.getQty() == null || billItem.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
                // Match PO item by inventoryItemId first, then by itemCode
                InvPurchaseOrderItemEntity matchedPoItem = null;
                if (billItem.getInventoryItemId() != null) {
                    matchedPoItem = poItems.stream()
                        .filter(pi -> billItem.getInventoryItemId().equals(pi.getInventoryItemId()))
                        .findFirst().orElse(null);
                }
                if (matchedPoItem == null && billItem.getItemCode() != null) {
                    matchedPoItem = poItems.stream()
                        .filter(pi -> billItem.getItemCode().equalsIgnoreCase(pi.getItemCode()))
                        .findFirst().orElse(null);
                }
                if (matchedPoItem != null) {
                    // receivedQty BEFORE this bill (may have been set by receiveGoods already)
                    BigDecimal alreadyReceived = matchedPoItem.getReceivedQty() != null
                        ? matchedPoItem.getReceivedQty() : BigDecimal.ZERO;
                    BigDecimal billQty  = billItem.getQty();
                    // newTotal = how many units have been received in total (prev + this bill).
                    // We do NOT cap at orderedQty: a vendor can deliver more than ordered,
                    // and capping here silently under-reports stock.
                    BigDecimal newTotal = alreadyReceived.add(billQty);

                    // netNewQty = qty being added to inventory by this bill
                    // (avoids double-counting if receiveGoods already ran for this item)
                    BigDecimal netNewQty = newTotal.subtract(alreadyReceived);
                    if (netNewQty.compareTo(BigDecimal.ZERO) < 0) netNewQty = BigDecimal.ZERO;

                    // Update PO item receivedQty
                    matchedPoItem.setReceivedQty(newTotal);
                    poItemRepository.save(matchedPoItem);

                    // ── Update inventory_items.current_qty ────────────────────
                    // ONLY add netNewQty — skip if receiveGoods already added this qty
                    // (i.e. alreadyReceived >= billQty means receiveGoods already handled it)
                    if (netNewQty.compareTo(BigDecimal.ZERO) > 0) {
                        InventoryItemEntity invItem = null;

                        // 1st: by inventoryItemId (most reliable)
                        if (matchedPoItem.getInventoryItemId() != null) {
                            invItem = inventoryItemRepository.findById(matchedPoItem.getInventoryItemId()).orElse(null);
                        }
                        // 2nd: by warehouseId + itemCode fallback
                        if (invItem == null && linkedPo.getWarehouseId() != null && matchedPoItem.getItemCode() != null) {
                            invItem = inventoryItemRepository
                                .findByWarehouseIdAndItemCode(linkedPo.getWarehouseId(), matchedPoItem.getItemCode())
                                .orElse(null);
                        }

                        if (invItem != null) {
                            BigDecimal updatedInvQty = (invItem.getCurrentQty() != null
                                ? invItem.getCurrentQty() : BigDecimal.ZERO).add(netNewQty);
                            invItem.setCurrentQty(updatedInvQty);
                            inventoryItemRepository.save(invItem);
                            log.info("Bill {}: inventory item {} qty +{} → {} (net-new; already-received={})",
                                billNo, invItem.getItemCode(), netNewQty, updatedInvQty, alreadyReceived);

                            // ── Record INWARD transaction for this stock movement ──
                            try {
                                InvTransactionEntity txn = InvTransactionEntity.builder()
                                    .type("INWARD")
                                    .inventoryItemId(invItem.getId())
                                    .itemCode(invItem.getItemCode())
                                    .itemName(invItem.getName())
                                    .unit(invItem.getUnit())
                                    .qty(netNewQty)
                                    .warehouseId(linkedPo.getWarehouseId())
                                    .groupName(invItem.getGroupName())
                                    .subGroupName(invItem.getSubGroupName())
                                    .projectId(linkedPo.getProjectId())
                                    .refNo(linkedPo.getPoNo())
                                    .vendorId(linkedPo.getVendorId())
                                    .vendorName(linkedPo.getVendorName())
                                    .poId(linkedPo.getId())
                                    .poNo(linkedPo.getPoNo())
                                    .unitCost(matchedPoItem.getRate())
                                    .notes("Auto-created from Bill: " + billNo)
                                    .transactionDate(java.time.LocalDate.now())
                                    .build();
                                // txnNo is auto-set after first save
                                InvTransactionEntity savedTxn = invTransactionRepository.saveAndFlush(txn);
                                String txnNo = String.format("INV-TXN-%d-%05d",
                                    java.time.LocalDate.now().getYear(), savedTxn.getId());
                                savedTxn.setTxnNo(txnNo);
                                invTransactionRepository.save(savedTxn);
                                log.info("INWARD txn {} created for bill {} item {}", txnNo, billNo, invItem.getItemCode());
                            } catch (Exception txnEx) {
                                log.warn("Failed to create INWARD transaction for bill {} item {}: {}",
                                    billNo, invItem.getItemCode(), txnEx.getMessage());
                            }
                        } else {
                            log.warn("Bill {}: no inventory item found for PO item {} (itemCode={}, warehouseId={})",
                                billNo, matchedPoItem.getId(), matchedPoItem.getItemCode(), linkedPo.getWarehouseId());
                        }
                    } else {
                        log.info("Bill {}: skipped inventory update for item {} — qty already accounted for by receiveGoods (alreadyReceived={}, billQty={})",
                            billNo, matchedPoItem.getItemCode(), alreadyReceived, billQty);
                    }
                }
            }
            // Recalculate PO status based on updated receivedQty
            List<InvPurchaseOrderItemEntity> updatedItems = poItemRepository.findByPurchaseOrderId(linkedPo.getId());
            boolean allReceived = updatedItems.stream().allMatch(pi ->
                pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0);
            boolean anyReceived = updatedItems.stream().anyMatch(pi ->
                pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(BigDecimal.ZERO) > 0);
            if (anyReceived) {
                linkedPo.setStatus(allReceived ? "RECEIVED" : "PARTIAL");
                linkedPo.setTotalItemsReceived((int) updatedItems.stream()
                    .filter(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0)
                    .count());
                poRepository.save(linkedPo);
                log.info("PO {} status updated to {} after bill {}", linkedPo.getPoNo(), linkedPo.getStatus(), billNo);
            }
        }

        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        String poNo = linkedPo != null ? linkedPo.getPoNo() : null;
        return InvBillWrapper.from(saved, poNo, whName);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public InvBillWrapper update(Long id, InvBillWrapper req) {
        InvBillEntity e = billRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + id));

        if (req.getBillDate()  != null) e.setBillDate(req.getBillDate());
        if (req.getDueDate()   != null) e.setDueDate(req.getDueDate());
        if (req.getNotes()     != null) e.setNotes(req.getNotes());
        if (req.getProjectId() != null) e.setProjectId(req.getProjectId());
        if (nullIfBlank(req.getVendorName()) != null) e.setVendorName(req.getVendorName().trim());

        boolean isPaid = "PAID".equals(e.getStatus());

        // ── Rebuild items (only allowed on non-PAID bills) ────────────────────
        if (!isPaid && req.getItems() != null && !req.getItems().isEmpty()) {
            // Snapshot old item qtys for PO delta calculation
            Map<String, BigDecimal> oldQtyByCode = new java.util.HashMap<>();
            Map<Long,   BigDecimal> oldQtyById   = new java.util.HashMap<>();
            for (InvBillItemEntity old : e.getItems()) {
                if (old.getInventoryItemId() != null)
                    oldQtyById.put(old.getInventoryItemId(), old.getQty() != null ? old.getQty() : BigDecimal.ZERO);
                if (old.getItemCode() != null)
                    oldQtyByCode.put(old.getItemCode().toLowerCase(), old.getQty() != null ? old.getQty() : BigDecimal.ZERO);
            }

            // Rebuild items in-place (orphanRemoval will clean up removed ones)
            e.getItems().clear();
            BigDecimal computedTotal = BigDecimal.ZERO;
            for (InvBillWrapper.InvBillItemWrapper iw : req.getItems()) {
                BigDecimal qty    = iw.getQty()    != null ? iw.getQty()    : BigDecimal.ZERO;
                BigDecimal rate   = iw.getRate()   != null ? iw.getRate()   : BigDecimal.ZERO;
                BigDecimal taxPct = iw.getTaxPct() != null ? iw.getTaxPct() : BigDecimal.ZERO;
                if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;

                InvBillItemEntity item = new InvBillItemEntity();
                item.setBill(e);
                item.setInventoryItemId(iw.getInventoryItemId());
                item.setItemCode(iw.getItemCode());
                item.setItemName(iw.getItemName());
                item.setUnit(iw.getUnit());
                item.setQty(qty);
                item.setRate(rate);
                item.setTaxPct(taxPct);
                item.setNotes(iw.getNotes());
                e.getItems().add(item);

                BigDecimal sub = qty.multiply(rate);
                BigDecimal tax = sub.multiply(taxPct).divide(new BigDecimal("100"));
                computedTotal = computedTotal.add(sub).add(tax);
            }

            // Update total from computed items (don't trust client-supplied total when items exist)
            if (computedTotal.compareTo(BigDecimal.ZERO) > 0) {
                e.setTotalAmount(computedTotal);
            }

            // ── Adjust PO item receivedQty for the delta ──────────────────────
            if (e.getPoId() != null) {
                poRepository.findById(e.getPoId()).ifPresent(po -> {
                    List<InvPurchaseOrderItemEntity> poItems = poItemRepository.findByPurchaseOrderId(po.getId());
                    for (InvBillItemEntity newItem : e.getItems()) {
                        BigDecimal newQty = newItem.getQty();
                        // Old qty for this item
                        BigDecimal oldQty = BigDecimal.ZERO;
                        if (newItem.getInventoryItemId() != null && oldQtyById.containsKey(newItem.getInventoryItemId()))
                            oldQty = oldQtyById.get(newItem.getInventoryItemId());
                        else if (newItem.getItemCode() != null && oldQtyByCode.containsKey(newItem.getItemCode().toLowerCase()))
                            oldQty = oldQtyByCode.get(newItem.getItemCode().toLowerCase());

                        BigDecimal delta = newQty.subtract(oldQty);  // +ve = more received, -ve = reversed
                        if (delta.compareTo(BigDecimal.ZERO) == 0) continue;

                        // Find matching PO item
                        InvPurchaseOrderItemEntity matchedPO = null;
                        if (newItem.getInventoryItemId() != null)
                            matchedPO = poItems.stream().filter(pi -> newItem.getInventoryItemId().equals(pi.getInventoryItemId())).findFirst().orElse(null);
                        if (matchedPO == null && newItem.getItemCode() != null)
                            matchedPO = poItems.stream().filter(pi -> newItem.getItemCode().equalsIgnoreCase(pi.getItemCode())).findFirst().orElse(null);

                        if (matchedPO != null) {
                            BigDecimal current = matchedPO.getReceivedQty() != null ? matchedPO.getReceivedQty() : BigDecimal.ZERO;
                            BigDecimal updated = current.add(delta);
                            if (updated.compareTo(BigDecimal.ZERO) < 0) updated = BigDecimal.ZERO;

                            // Do NOT cap receivedQty at orderedQty -- over-delivery is allowed.
                            // Only floor at 0 (cannot receive negative quantity).
                            BigDecimal effectiveDelta = delta;
                            if (updated.compareTo(BigDecimal.ZERO) < 0) {
                                effectiveDelta = BigDecimal.ZERO.subtract(current); // reverse all
                                updated = BigDecimal.ZERO;
                            }

                            matchedPO.setReceivedQty(updated);
                            poItemRepository.save(matchedPO);

                            // ── Always apply effectiveDelta to inventory ─────────────
                            // Scenario: bill was 60 → corrected to 50 → delta = -10
                            // Inventory must decrease by 10 regardless of whether GRN ran.
                            // The create() guard (netNewQty) already prevented double-add on
                            // original create, so here we just apply what changed.
                            if (effectiveDelta.compareTo(BigDecimal.ZERO) != 0) {
                                InventoryItemEntity invItem = null;
                                if (matchedPO.getInventoryItemId() != null)
                                    invItem = inventoryItemRepository.findById(matchedPO.getInventoryItemId()).orElse(null);
                                if (invItem == null && po.getWarehouseId() != null && matchedPO.getItemCode() != null)
                                    invItem = inventoryItemRepository
                                        .findByWarehouseIdAndItemCode(po.getWarehouseId(), matchedPO.getItemCode())
                                        .orElse(null);
                                if (invItem != null) {
                                    BigDecimal newInvQty = (invItem.getCurrentQty() != null
                                        ? invItem.getCurrentQty() : BigDecimal.ZERO).add(effectiveDelta);
                                    // Floor at 0 — can't go negative in stock
                                    if (newInvQty.compareTo(BigDecimal.ZERO) < 0) newInvQty = BigDecimal.ZERO;
                                    invItem.setCurrentQty(newInvQty);
                                    inventoryItemRepository.save(invItem);
                                    log.info("Bill {} update: inventory item '{}' qty delta={} → new={}",
                                        e.getBillNo(), invItem.getItemCode(), effectiveDelta, newInvQty);
                                } else {
                                    log.warn("Bill {} update: inventory item not found for PO item {} (code={}, warehouseId={})",
                                        e.getBillNo(), matchedPO.getId(), matchedPO.getItemCode(), po.getWarehouseId());
                                }
                            }
                        }
                    }
                    // Recalculate PO status
                    List<InvPurchaseOrderItemEntity> all = poItemRepository.findByPurchaseOrderId(po.getId());
                    boolean anyRec = all.stream().anyMatch(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(BigDecimal.ZERO) > 0);
                    boolean allRec = all.stream().allMatch(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0);
                    po.setStatus(allRec ? "RECEIVED" : anyRec ? "PARTIAL" : "SENT");
                    po.setTotalItemsReceived((int) all.stream().filter(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0).count());
                    poRepository.save(po);
                    log.info("PO {} receivedQty adjusted after bill {} update", po.getPoNo(), e.getBillNo());
                });
            }

        } else if (!isPaid && req.getTotalAmount() != null && req.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
            // Standalone bill (no items) — allow amount change
            e.setTotalAmount(req.getTotalAmount());
        }

        e.recalculateStatus();
        InvBillEntity saved = billRepository.save(e);
        log.info("Updated bill {}", saved.getBillNo());

        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        String poNo = saved.getPoId() != null
            ? poRepository.findById(saved.getPoId()).map(InvPurchaseOrderEntity::getPoNo).orElse(null)
            : null;
        return InvBillWrapper.from(saved, poNo, whName);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> delete(Long id) {
        InvBillEntity e = billRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + id));

        // ── Reverse PO item receivedQty for items in this bill ─────────────────
        if (e.getPoId() != null && e.getItems() != null && !e.getItems().isEmpty()) {
            poRepository.findById(e.getPoId()).ifPresent(po -> {
                List<InvPurchaseOrderItemEntity> poItems = poItemRepository.findByPurchaseOrderId(po.getId());
                for (InvBillItemEntity billItem : e.getItems()) {
                    if (billItem.getQty() == null || billItem.getQty().compareTo(BigDecimal.ZERO) <= 0) continue;
                    InvPurchaseOrderItemEntity match = null;
                    if (billItem.getInventoryItemId() != null) {
                        match = poItems.stream().filter(pi -> billItem.getInventoryItemId().equals(pi.getInventoryItemId())).findFirst().orElse(null);
                    }
                    if (match == null && billItem.getItemCode() != null) {
                        match = poItems.stream().filter(pi -> billItem.getItemCode().equalsIgnoreCase(pi.getItemCode())).findFirst().orElse(null);
                    }
                    if (match != null) {
                        BigDecimal current   = match.getReceivedQty() != null ? match.getReceivedQty() : BigDecimal.ZERO;
                        BigDecimal toReverse = billItem.getQty(); // qty this bill contributed
                        BigDecimal reversed  = current.subtract(toReverse);
                        if (reversed.compareTo(BigDecimal.ZERO) < 0) reversed = BigDecimal.ZERO;

                        // Effective reversal (may be less than toReverse if already clamped)
                        BigDecimal effectiveReversal = current.subtract(reversed); // always >= 0

                        match.setReceivedQty(reversed);
                        poItemRepository.save(match);

                        // ── Reverse inventory_items.current_qty by the same amount ────
                        if (effectiveReversal.compareTo(BigDecimal.ZERO) > 0) {
                            InventoryItemEntity invItem = null;
                            if (match.getInventoryItemId() != null)
                                invItem = inventoryItemRepository.findById(match.getInventoryItemId()).orElse(null);
                            if (invItem == null && po.getWarehouseId() != null && match.getItemCode() != null)
                                invItem = inventoryItemRepository
                                    .findByWarehouseIdAndItemCode(po.getWarehouseId(), match.getItemCode())
                                    .orElse(null);
                            if (invItem != null) {
                                BigDecimal newInvQty = (invItem.getCurrentQty() != null
                                    ? invItem.getCurrentQty() : BigDecimal.ZERO).subtract(effectiveReversal);
                                if (newInvQty.compareTo(BigDecimal.ZERO) < 0) newInvQty = BigDecimal.ZERO;
                                invItem.setCurrentQty(newInvQty);
                                inventoryItemRepository.save(invItem);
                                log.info("Bill {} deleted: reversed inventory item '{}' qty by -{} → {}",
                                    e.getBillNo(), invItem.getItemCode(), effectiveReversal, newInvQty);
                            } else {
                                log.warn("Bill {} delete: inventory item not found for PO item {} (code={}, warehouseId={})",
                                    e.getBillNo(), match.getId(), match.getItemCode(), po.getWarehouseId());
                            }
                        }
                    }
                }
                // Recalculate PO status
                List<InvPurchaseOrderItemEntity> updated = poItemRepository.findByPurchaseOrderId(po.getId());
                boolean anyReceived = updated.stream().anyMatch(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(BigDecimal.ZERO) > 0);
                boolean allReceived = updated.stream().allMatch(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0);
                po.setStatus(allReceived ? "RECEIVED" : anyReceived ? "PARTIAL" : "SENT");
                po.setTotalItemsReceived((int) updated.stream().filter(pi -> pi.getReceivedQty() != null && pi.getReceivedQty().compareTo(pi.getOrderedQty()) >= 0).count());
                poRepository.save(po);
                log.info("PO {} receivedQty reversed after bill {} deleted", po.getPoNo(), e.getBillNo());
            });
        }

        billRepository.delete(e);
        return Map.of("success", true, "message", "Bill deleted");
    }

    // Payment History for a specific bill

    @Transactional(readOnly = true)
    public List<InvPaymentWrapper> getPayments(Long billId) {
        billRepository.findById(billId)
            .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + billId));

        List<InvPaymentEntity> rows = paymentRepository.findByBillId(billId);

        // Enrich allocation rows with their source advance payment_no
        Map<Long, String> advanceNoById = new HashMap<>();
        rows.stream()
            .map(InvPaymentEntity::getAdvanceId)
            .filter(Objects::nonNull)
            .distinct()
            .forEach(advId -> paymentRepository.findById(advId)
                .ifPresent(adv -> advanceNoById.put(advId, adv.getPaymentNo())));

        return rows.stream()
            .sorted(java.util.Comparator.comparing(InvPaymentEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .map(p -> {
                InvPaymentWrapper w = InvPaymentWrapper.from(p, null, null);
                if (p.getAdvanceId() != null)
                    w.setAdvancePaymentNo(advanceNoById.get(p.getAdvanceId()));
                return w;
            })
            .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<Long, String> buildPoNoMap(List<InvBillEntity> bills) {
        Map<Long, String> m = new HashMap<>();
        bills.stream().map(InvBillEntity::getPoId).filter(Objects::nonNull)
             .collect(Collectors.toSet())
             .forEach(poId -> poRepository.findById(poId)
                 .ifPresent(po -> m.put(poId, po.getPoNo())));
        return m;
    }

    private Map<String, String> buildWarehouseNameMap(Set<Long> ids) {
        Map<String, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        warehouseRepository.findAllById(ids).forEach(w -> m.put(String.valueOf(w.getId()), w.getName()));
        return m;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}