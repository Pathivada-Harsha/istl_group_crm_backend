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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvPurchaseOrderService {

    private final InvPurchaseOrderRepository     poRepository;
    private final InvPurchaseOrderItemRepository poItemRepository;
    private final InventoryItemRepository        itemRepository;
    private final WarehouseRepository            warehouseRepository;

    // ── List (paginated + filtered) ───────────────────────────────────────────

    public Map<String, Object> list(Long warehouseId, String groupName, String subGroupName,
                                    String projectId, String status, Long vendorId,
                                    String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<InvPurchaseOrderEntity> result = poRepository.findFiltered(
            warehouseId,
            nullIfBlank(groupName), nullIfBlank(subGroupName), nullIfBlank(projectId),
            nullIfBlank(status), vendorId, nullIfBlank(search),
            pageable
        );

        Map<String, String> whNames = buildWarehouseNameMap(
            result.getContent().stream()
                  .map(InvPurchaseOrderEntity::getWarehouseId)
                  .filter(Objects::nonNull)
                  .collect(Collectors.toSet())
        );

        List<InvPurchaseOrderWrapper> content = result.getContent().stream()
            .map(e -> InvPurchaseOrderWrapper.from(e, whNames.getOrDefault(
                String.valueOf(e.getWarehouseId()), null)))
            .collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content",       content);
        resp.put("totalElements", result.getTotalElements());
        resp.put("totalPages",    result.getTotalPages());
        resp.put("size",          result.getSize());
        resp.put("number",        result.getNumber());
        return resp;
    }

    // ── Get single ────────────────────────────────────────────────────────────

    public InvPurchaseOrderWrapper getById(Long id) {
        InvPurchaseOrderEntity e = poRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));
        String whName = e.getWarehouseId() != null
            ? warehouseRepository.findById(e.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvPurchaseOrderWrapper.from(e, whName);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public InvPurchaseOrderWrapper create(InvPurchaseOrderWrapper req, Long createdBy) {
        if (req.getVendorName() == null || req.getVendorName().isBlank())
            throw new IllegalArgumentException("Vendor name is required");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new IllegalArgumentException("At least one item is required");

        InvPurchaseOrderEntity entity = InvPurchaseOrderEntity.builder()
            .poNo("PENDING")    // updated below after save
            .vendorId(req.getVendorId())
            .vendorName(req.getVendorName().trim())
            .vendorContact(req.getVendorContact())
            .warehouseId(req.getWarehouseId())
            .groupName(req.getGroupName())
            .subGroupName(req.getSubGroupName())
            .projectId(req.getProjectId())
            .orderDate(req.getOrderDate() != null ? req.getOrderDate() : LocalDate.now())
            .expectedDelivery(req.getExpectedDelivery())
            .status(req.getStatus() != null && !req.getStatus().isBlank() ? req.getStatus().toUpperCase() : "DRAFT")
            .paymentStatus("PENDING")
            .paymentTerms(req.getPaymentTerms())
            .deliveryAddress(req.getDeliveryAddress())
            .notes(req.getNotes())
            .createdBy(createdBy)
            .build();

        // Persist items
        List<InvPurchaseOrderItemEntity> itemEntities = new ArrayList<>();
        for (InvPurchaseOrderWrapper.InvPoItemWrapper iw : req.getItems()) {
            InvPurchaseOrderItemEntity ie = InvPurchaseOrderItemEntity.builder()
                .purchaseOrder(entity)
                .inventoryItemId(iw.getInventoryItemId())
                .itemCode(nullIfBlank(iw.getItemCode()))
                .itemName(nullIfBlank(iw.getItemName()))
                .unit(iw.getUnit())
                .orderedQty(orZero(iw.getOrderedQty()))
                .receivedQty(BigDecimal.ZERO)
                .rate(orZero(iw.getRate()))
                .taxPct(iw.getTaxPct() != null ? iw.getTaxPct() : BigDecimal.ZERO)
                .notes(iw.getNotes())
                .build();
            itemEntities.add(ie);
        }
        entity.setItems(itemEntities);
        entity.setTotalItemsOrdered(itemEntities.size());
        entity.setTotalItemsReceived(0);
        entity.setTotalValue(computeTotal(itemEntities));

        InvPurchaseOrderEntity saved = poRepository.saveAndFlush(entity);

        // Set auto-number from persisted id
        String poNo = String.format("INV-PO-%d-%05d", LocalDate.now().getYear(), saved.getId());
        saved.setPoNo(poNo);
        saved = poRepository.saveAndFlush(saved);

        log.info("Created inventory PO {} for vendor '{}'", poNo, saved.getVendorName());

        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvPurchaseOrderWrapper.from(saved, whName);
    }

    // ── Update status ─────────────────────────────────────────────────────────

    @Transactional
    public InvPurchaseOrderWrapper updateStatus(Long id, String newStatus, Long updatedBy) {
        InvPurchaseOrderEntity e = poRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));

        // Guard: DELIVERED requires all items to be fully received
        if ("DELIVERED".equalsIgnoreCase(newStatus)) {
            List<InvPurchaseOrderItemEntity> currentItems = e.getItems();
            if (currentItems == null || currentItems.isEmpty()) {
                throw new IllegalArgumentException(
                    "Cannot mark as Delivered: purchase order has no items.");
            }
            List<String> pendingItems = currentItems.stream()
                .filter(it -> {
                    BigDecimal ordered  = it.getOrderedQty()  != null ? it.getOrderedQty()  : BigDecimal.ZERO;
                    BigDecimal received = it.getReceivedQty() != null ? it.getReceivedQty() : BigDecimal.ZERO;
                    return received.compareTo(ordered) < 0;
                })
                .map(it -> it.getItemName() != null ? it.getItemName() : it.getItemCode())
                .collect(Collectors.toList());

            if (!pendingItems.isEmpty()) {
                String itemList = pendingItems.size() <= 3
                    ? String.join(", ", pendingItems)
                    : String.join(", ", pendingItems.subList(0, 3)) + " and " + (pendingItems.size() - 3) + " more";
                throw new IllegalArgumentException(
                    "Cannot mark as Delivered: the following items are not fully received — " + itemList +
                    ". Please complete the goods receipt before marking this PO as Delivered.");
            }
        }

        e.setStatus(newStatus.toUpperCase());
        if ("APPROVED".equalsIgnoreCase(newStatus)) e.setApprovedBy(updatedBy);
        InvPurchaseOrderEntity saved = poRepository.save(e);
        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvPurchaseOrderWrapper.from(saved, whName);
    }

    /**
     * Goods Receipt Note — marks received quantities on items, updates
     * inventory_items.current_qty for each received line, and updates
     * the PO status to PARTIAL or RECEIVED accordingly.
     *
     * @param receivedLines map of { poItemId → receivedQty }
     */
    @Transactional
    public InvPurchaseOrderWrapper receiveGoods(Long poId, Map<Long, BigDecimal> receivedLines, Long userId) {
        InvPurchaseOrderEntity po = poRepository.findById(poId)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + poId));

        for (InvPurchaseOrderItemEntity item : po.getItems()) {
            BigDecimal received = receivedLines.get(item.getId());
            if (received == null || received.compareTo(BigDecimal.ZERO) <= 0) continue;

            item.setReceivedQty((item.getReceivedQty() != null ? item.getReceivedQty() : BigDecimal.ZERO).add(received));

            // Update inventory item stock if linked
            if (item.getInventoryItemId() != null) {
                itemRepository.findById(item.getInventoryItemId()).ifPresent(invItem -> {
                    invItem.setCurrentQty(invItem.getCurrentQty().add(received));
                    itemRepository.save(invItem);
                    log.info("GRN: +{} {} for item {} (warehouse {})",
                             received, invItem.getUnit(), invItem.getItemCode(), invItem.getWarehouseId());
                });
            }
        }

        // Recalculate PO status
        boolean allReceived = po.getItems().stream().allMatch(it ->
            it.getReceivedQty() != null && it.getReceivedQty().compareTo(it.getOrderedQty()) >= 0);
        boolean anyReceived = po.getItems().stream().anyMatch(it ->
            it.getReceivedQty() != null && it.getReceivedQty().compareTo(BigDecimal.ZERO) > 0);

        po.setStatus(allReceived ? "RECEIVED" : anyReceived ? "PARTIAL" : po.getStatus());
        po.setTotalItemsReceived(
            (int) po.getItems().stream()
                .filter(it -> it.getReceivedQty() != null && it.getReceivedQty().compareTo(it.getOrderedQty()) >= 0)
                .count()
        );

        InvPurchaseOrderEntity saved = poRepository.save(po);
        String whName = saved.getWarehouseId() != null
            ? warehouseRepository.findById(saved.getWarehouseId()).map(WarehouseEntity::getName).orElse(null)
            : null;
        return InvPurchaseOrderWrapper.from(saved, whName);
    }

    // ── Update full PO ────────────────────────────────────────────────────────

    @Transactional
    public InvPurchaseOrderWrapper update(Long id, InvPurchaseOrderWrapper req, Long updatedBy) {
        InvPurchaseOrderEntity po = poRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));

        if (po.getDeletedAt() != null)
            throw new IllegalArgumentException("Cannot update a deleted purchase order");

        // Update header fields
        if (req.getVendorId()       != null) po.setVendorId(req.getVendorId());
        if (nullIfBlank(req.getVendorName()) != null) po.setVendorName(req.getVendorName().trim());
        if (req.getWarehouseId()    != null) po.setWarehouseId(req.getWarehouseId());
        if (nullIfBlank(req.getGroupName())    != null) po.setGroupName(req.getGroupName());
        if (nullIfBlank(req.getSubGroupName()) != null) po.setSubGroupName(req.getSubGroupName());
        if (nullIfBlank(req.getProjectId())    != null) po.setProjectId(req.getProjectId());
        if (req.getOrderDate()      != null) po.setOrderDate(req.getOrderDate());
        if (req.getExpectedDelivery() != null) po.setExpectedDelivery(req.getExpectedDelivery());
        if (nullIfBlank(req.getPaymentTerms()) != null) po.setPaymentTerms(req.getPaymentTerms());
        if (req.getNotes()          != null) po.setNotes(req.getNotes());
        // Update status if provided and not a terminal auto-calculated state
        if (nullIfBlank(req.getStatus()) != null) {
            String newStatus = req.getStatus().toUpperCase();

            // Guard: DELIVERED requires all items to be fully received
            if ("DELIVERED".equals(newStatus)) {
                List<InvPurchaseOrderItemEntity> currentItems = po.getItems();
                if (currentItems == null || currentItems.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Cannot mark as Delivered: purchase order has no items.");
                }
                List<String> pendingItems = currentItems.stream()
                    .filter(it -> {
                        BigDecimal ordered  = it.getOrderedQty()  != null ? it.getOrderedQty()  : BigDecimal.ZERO;
                        BigDecimal received = it.getReceivedQty() != null ? it.getReceivedQty() : BigDecimal.ZERO;
                        return received.compareTo(ordered) < 0;
                    })
                    .map(it -> it.getItemName() != null ? it.getItemName() : it.getItemCode())
                    .collect(Collectors.toList());

                if (!pendingItems.isEmpty()) {
                    String itemList = pendingItems.size() <= 3
                        ? String.join(", ", pendingItems)
                        : String.join(", ", pendingItems.subList(0, 3)) + " and " + (pendingItems.size() - 3) + " more";
                    throw new IllegalArgumentException(
                        "Cannot mark as Delivered: the following items are not fully received — " + itemList +
                        ". Please complete the goods receipt before marking this PO as Delivered.");
                }
            }

            po.setStatus(newStatus);
            if ("APPROVED".equals(newStatus)) po.setApprovedBy(updatedBy);
        }

        // Rebuild line items if provided
        if (req.getItems() != null && !req.getItems().isEmpty()) {
            // Snapshot existing receivedQty values so a PO edit never resets delivery progress.
            Map<Long,   BigDecimal> oldRcvById   = new HashMap<>();
            Map<String, BigDecimal> oldRcvByCode = new HashMap<>();
            for (InvPurchaseOrderItemEntity oldItem : po.getItems()) {
                BigDecimal rcv = oldItem.getReceivedQty() != null ? oldItem.getReceivedQty() : BigDecimal.ZERO;
                if (oldItem.getInventoryItemId() != null) oldRcvById.put(oldItem.getInventoryItemId(), rcv);
                if (oldItem.getItemCode() != null)        oldRcvByCode.put(oldItem.getItemCode().toLowerCase(), rcv);
            }

            po.getItems().clear();
            for (InvPurchaseOrderWrapper.InvPoItemWrapper iw : req.getItems()) {
                // Use client-supplied receivedQty if non-zero (explicit GRN), else preserve DB value.
                BigDecimal clientReceived = iw.getReceivedQty() != null ? iw.getReceivedQty() : BigDecimal.ZERO;
                BigDecimal preserved = clientReceived;
                if (preserved.compareTo(BigDecimal.ZERO) == 0) {
                    if (iw.getInventoryItemId() != null && oldRcvById.containsKey(iw.getInventoryItemId())) {
                        preserved = oldRcvById.get(iw.getInventoryItemId());
                    } else if (iw.getItemCode() != null && oldRcvByCode.containsKey(iw.getItemCode().toLowerCase())) {
                        preserved = oldRcvByCode.get(iw.getItemCode().toLowerCase());
                    }
                }
                // Cap to new orderedQty in case it was reduced below what was received
                BigDecimal ordered = orZero(iw.getOrderedQty());
                if (preserved.compareTo(ordered) > 0) preserved = ordered;

                InvPurchaseOrderItemEntity item = InvPurchaseOrderItemEntity.builder()
                    .purchaseOrder(po)
                    .inventoryItemId(iw.getInventoryItemId())
                    .itemCode(nullIfBlank(iw.getItemCode()))
                    .itemName(nullIfBlank(iw.getItemName()))
                    .unit(iw.getUnit())
                    .orderedQty(ordered)
                    .receivedQty(preserved)
                    .rate(orZero(iw.getRate()))
                    .taxPct(iw.getTaxPct() != null ? iw.getTaxPct() : BigDecimal.ZERO)
                    .notes(iw.getNotes())
                    .build();
                po.getItems().add(item);
            }
            po.setTotalItemsOrdered(po.getItems().size());
            po.setTotalValue(computeTotal(po.getItems()));

            // Recalculate PO status after items rebuilt (only if caller did not force a status)
            boolean anyRcv = po.getItems().stream().anyMatch(it -> it.getReceivedQty() != null && it.getReceivedQty().compareTo(BigDecimal.ZERO) > 0);
            boolean allRcv = po.getItems().stream().allMatch(it -> it.getReceivedQty() != null && it.getReceivedQty().compareTo(it.getOrderedQty()) >= 0);
            if (anyRcv && nullIfBlank(req.getStatus()) == null) {
                po.setStatus(allRcv ? "RECEIVED" : "PARTIAL");
            }
            po.setTotalItemsReceived((int) po.getItems().stream()
                .filter(it -> it.getReceivedQty() != null && it.getReceivedQty().compareTo(it.getOrderedQty()) >= 0)
                .count());
        }

        WarehouseEntity wh = po.getWarehouseId() != null
            ? warehouseRepository.findById(po.getWarehouseId()).orElse(null) : null;
        InvPurchaseOrderEntity saved = poRepository.save(po);
        log.info("Updated PO {} by user {}", saved.getPoNo(), updatedBy);
        return InvPurchaseOrderWrapper.from(saved, wh != null ? wh.getName() : null);
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> delete(Long id) {
        InvPurchaseOrderEntity e = poRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));
        e.setDeletedAt(LocalDateTime.now());
        poRepository.save(e);
        return Map.of("success", true, "message", "Purchase order deleted");
    }

    // ── Items for Bill (procurement-style) ────────────────────────────────────

    /**
     * Returns PO line items enriched with delivery progress — same pattern as
     * {@code /purchase-orders/{id}/items-for-bill} used on the CRM Bills page.
     * Each row shows orderedQty, receivedQty, pendingQty so the bill modal can
     * let the user enter how many units are being invoiced this time.
     */
    public Map<String, Object> getItemsForBill(Long poId) {
        InvPurchaseOrderEntity po = poRepository.findById(poId)
            .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + poId));

        List<Map<String, Object>> items = new ArrayList<>();
        for (InvPurchaseOrderItemEntity item : po.getItems()) {
            BigDecimal ordered  = item.getOrderedQty()  != null ? item.getOrderedQty()  : BigDecimal.ZERO;
            BigDecimal received = item.getReceivedQty() != null ? item.getReceivedQty() : BigDecimal.ZERO;
            BigDecimal pending  = ordered.subtract(received);
            if (pending.compareTo(BigDecimal.ZERO) < 0) pending = BigDecimal.ZERO;

            Map<String, Object> row = new HashMap<>();
            row.put("id",              item.getId());
            row.put("inventoryItemId", item.getInventoryItemId());
            row.put("itemCode",        item.getItemCode());
            row.put("itemName",        item.getItemName());
            row.put("unit",            item.getUnit());
            row.put("orderedQty",      ordered);
            row.put("receivedQty",     received);
            row.put("pendingQty",      pending);
            // maxBillableQty is not capped at pending -- over-delivery is allowed.
            // The UI uses this as a guide, not a hard limit.
            row.put("maxBillableQty",  ordered);
            row.put("unitPrice",       item.getRate());
            row.put("taxPercent",      item.getTaxPct());
            row.put("deliveryStatus",  received.compareTo(BigDecimal.ZERO) == 0 ? "PENDING"
                : received.compareTo(ordered) >= 0 ? "COMPLETE" : "PARTIAL");
            items.add(row);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("items",   items);
        resp.put("poNo",    po.getPoNo());
        resp.put("poId",    po.getId());
        resp.put("vendorId",   po.getVendorId());
        resp.put("vendorName", po.getVendorName());
        resp.put("projectId",  po.getProjectId());
        resp.put("warehouseId",po.getWarehouseId());
        resp.put("groupName",  po.getGroupName());
        resp.put("subGroupName",po.getSubGroupName());
        return resp;
    }

    /**
     * Returns distinct vendors (id + name) who have inv_purchase_orders matching
     * the given scope. Used by the Create Bill modal to scope the vendor dropdown.
     */
    public List<Map<String, Object>> getVendorsForScope(String groupName, String subGroupName, String projectId) {
        // Load all non-deleted POs in scope, then deduplicate vendors
        List<InvPurchaseOrderEntity> pos = poRepository.findFiltered(
            null, nullIfBlank(groupName), nullIfBlank(subGroupName),
            nullIfBlank(projectId), null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 500)
        ).getContent();

        Map<Long, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (InvPurchaseOrderEntity po : pos) {
            if (po.getVendorId() == null) continue;
            byId.computeIfAbsent(po.getVendorId(), vid -> {
                Map<String, Object> v = new HashMap<>();
                v.put("id",   vid);
                v.put("name", po.getVendorName());
                v.put("contact", po.getVendorContact());
                return v;
            });
        }
        return new ArrayList<>(byId.values());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal computeTotal(List<InvPurchaseOrderItemEntity> items) {
        return items.stream()
            .map(InvPurchaseOrderItemEntity::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Returns all PO line items across every non-cancelled PO for a project,
     * enriched with poNo, poId, orderedQty, receivedQty, pendingQty, unitPrice.
     * Used by the INWARD transaction modal flat item picker.
     */
    public List<Map<String, Object>> getAllItemsByProject(String projectId) {
        List<com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderItemEntity> items =
            poItemRepository.findAllItemsByProjectId(projectId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderItemEntity item : items) {
            com.istlgroup.istl_group_crm_backend.entity.InvPurchaseOrderEntity po = item.getPurchaseOrder();

            BigDecimal ordered  = orZero(item.getOrderedQty());
            BigDecimal received = orZero(item.getReceivedQty());
            BigDecimal pending  = ordered.subtract(received);
            if (pending.compareTo(BigDecimal.ZERO) < 0) pending = BigDecimal.ZERO;

            Map<String, Object> row = new HashMap<>();
            row.put("id",              item.getId());
            row.put("poId",            po != null ? po.getId()    : null);
            row.put("poNo",            po != null ? po.getPoNo()  : null);
            row.put("vendorName",      po != null ? po.getVendorName() : null);
            row.put("inventoryItemId", item.getInventoryItemId());
            row.put("itemCode",        item.getItemCode());
            row.put("itemName",        item.getItemName());
            row.put("unit",            item.getUnit());
            row.put("orderedQty",      ordered);
            row.put("receivedQty",     received);
            row.put("pendingQty",      pending);
            row.put("unitPrice",       orZero(item.getRate()));
            row.put("taxPercent",      orZero(item.getTaxPct()));
            result.add(row);
        }
        return result;
    }
}