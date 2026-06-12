package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.InventoryItemEntity;
import com.istlgroup.istl_group_crm_backend.entity.WarehouseEntity;
import com.istlgroup.istl_group_crm_backend.repo.InventoryItemRepository;
import com.istlgroup.istl_group_crm_backend.repo.WarehouseRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InventoryItemBulkWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InventoryItemWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryItemService {

    private final InventoryItemRepository itemRepository;
    private final WarehouseRepository     warehouseRepository;

    // ── Paginated read (primary path used by the Items tab) ───────────────────

    /**
     * Returns the Spring Page shape that the Inventory Items tab expects:
     *   { content:[...], totalElements, totalPages, size, number }
     *
     * Group / subgroup filtering is STRICT — an item must explicitly carry the
     * supplied groupName / subGroupName (the "null = global" pattern is NOT
     * applied to items; it only applies to warehouses).
     *
     * Status (IN_STOCK / LOW_STOCK / OUT_OF_STOCK) is derived in the wrapper,
     * not stored, so it is not filterable server-side — the front-end applies
     * that as a visual overlay on the current page.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listPaged(Long   warehouseId, String groupName, String subGroupName,
                                          String category,   String search,
                                          int page, int size,
                                          boolean includeInactive) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1));
        Page<InventoryItemEntity> result = itemRepository.findScopedPaged(
            warehouseId,
            nullIfBlank(groupName), nullIfBlank(subGroupName),
            nullIfBlank(category),  nullIfBlank(search),
            !includeInactive,
            pageable
        );

        // Batch-load warehouses for location / code display
        Map<Long, WarehouseEntity> whById = batchLoadWarehouses(result.getContent());

        Map<String, Object> resp = new HashMap<>();
        resp.put("content", result.getContent().stream()
            .map(e -> InventoryItemWrapper.from(e, whById.get(e.getWarehouseId())))
            .collect(Collectors.toList()));
        resp.put("totalElements", result.getTotalElements());
        resp.put("totalPages",    result.getTotalPages());
        resp.put("size",          result.getSize());
        resp.put("number",        result.getNumber());
        return resp;
    }

    // ── Single item ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public InventoryItemWrapper getById(Long id) {
        InventoryItemEntity e = itemRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
        WarehouseEntity wh = warehouseRepository.findById(e.getWarehouseId()).orElse(null);
        return InventoryItemWrapper.from(e, wh);
    }

    // ── Create / Update ──────────────────────────────────────────────────────

   
    @Transactional
    public InventoryItemWrapper create(InventoryItemWrapper req, Long createdBy) {
        if (req.getWarehouseId() == null)
            throw new IllegalArgumentException("Warehouse is required");
        if (req.getName() == null || req.getName().isBlank())
            throw new IllegalArgumentException("Item name is required");

        WarehouseEntity wh = warehouseRepository.findById(req.getWarehouseId())
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + req.getWarehouseId()));

        // If caller provided a code, honour it and check uniqueness.
        // If blank/null, generate it after the first save (WH{whId}-{paddedId}).
        String suppliedCode = nullIfBlank(req.getItemCode());
        if (suppliedCode != null && itemRepository.existsByWarehouseIdAndItemCode(wh.getId(), suppliedCode))
            throw new IllegalArgumentException(
                "Item code '" + suppliedCode + "' already exists in warehouse '" + wh.getName() + "'");

        InventoryItemEntity e = InventoryItemEntity.builder()
            .warehouseId(wh.getId())
            .itemCode(suppliedCode != null ? suppliedCode : "PENDING")
            .name(req.getName().trim())
            .category(nullIfBlank(req.getCategory()))
            .unit(nullIfBlank(req.getUnit()))
            .currentQty(orZero(req.getCurrentQty()))
            .minQty(orZero(req.getMinQty()))
            .maxQty(orZero(req.getMaxQty()))
            .unitCost(orZero(req.getUnitCost()))
            .groupName(nullIfBlank(req.getGroupName()) != null
                ? nullIfBlank(req.getGroupName()) : wh.getGroupName())
            .subGroupName(nullIfBlank(req.getSubGroupName()) != null
                ? nullIfBlank(req.getSubGroupName()) : wh.getSubGroupName())
            .projectId(nullIfBlank(req.getProjectId()))
            .notes(req.getNotes())
            .isActive(req.getIsActive() != null ? req.getIsActive() : Boolean.TRUE)
            .createdBy(createdBy)
            .build();

        InventoryItemEntity saved = itemRepository.saveAndFlush(e);

        // Auto-generate item code: {first4ofWhCode}-{year}-{paddedId}
        // Padding grows: 4 digits for id ≤ 9999, 5 for ≤ 99999, 6 for ≤ 999999, etc.
        if (suppliedCode == null) {
            String whPrefix = wh.getCode() != null && !wh.getCode().isBlank()
                ? wh.getCode().trim().substring(0, Math.min(4, wh.getCode().trim().length())).toUpperCase()
                : ("WH" + wh.getId());
            int year = java.time.LocalDate.now().getYear();
            long id  = saved.getId();
            // Dynamic width: min 4 digits, grows as needed
            int digits = Math.max(4, String.valueOf(id).length());
            String padded = String.format("%0" + digits + "d", id);
            String autoCode = whPrefix + "-" + year + "-" + padded;
            saved.setItemCode(autoCode);
            saved = itemRepository.saveAndFlush(saved);
        }

        log.info("Inventory item created: id={}, code={}, wh={}", saved.getId(), saved.getItemCode(), wh.getId());
        return InventoryItemWrapper.from(saved, wh);
    }

    // ── Bulk create ──────────────────────────────────────────────────────────

    /**
     * Creates many items in one call against a shared scope.
     *
     * NOTE: intentionally NOT @Transactional at the method level.
     * Each itemRepository.saveAndFlush() call runs in its own short
     * transaction (Spring Data's SimpleJpaRepository.save is itself
     * @Transactional). This gives true partial-commit semantics: one bad
     * row never rolls back the rows that already succeeded.
     *
     * @return { created:[wrapper…], failed:[{index,itemCode,name,message}…],
     *           createdCount, failedCount }
     */
    public Map<String, Object> bulkCreate(InventoryItemBulkWrapper req, Long createdBy) {
        if (req == null || req.getItems() == null || req.getItems().isEmpty())
            throw new IllegalArgumentException("No items supplied");
        if (req.getWarehouseId() == null)
            throw new IllegalArgumentException("Warehouse is required");

        WarehouseEntity wh = warehouseRepository.findById(req.getWarehouseId())
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + req.getWarehouseId()));

        String sharedGroup    = nullIfBlank(req.getGroupName());
        String sharedSubGroup = nullIfBlank(req.getSubGroupName());

        List<InventoryItemWrapper> created = new ArrayList<>();
        List<Map<String, Object>>  failed  = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>(); // within-batch dup guard (case-insensitive)

        List<InventoryItemWrapper> items = req.getItems();
        for (int idx = 0; idx < items.size(); idx++) {
            InventoryItemWrapper row = items.get(idx);
            try {
                if (row == null || row.getItemCode() == null || row.getItemCode().isBlank())
                    throw new IllegalArgumentException("Item code is required");
                if (row.getName() == null || row.getName().isBlank())
                    throw new IllegalArgumentException("Item name is required");

                String code = row.getItemCode().trim();
                if (!seenCodes.add(code.toLowerCase()))
                    throw new IllegalArgumentException("Duplicate item code in this batch: " + code);
                if (itemRepository.existsByWarehouseIdAndItemCode(wh.getId(), code))
                    throw new IllegalArgumentException(
                        "Item code '" + code + "' already exists in warehouse '" + wh.getName() + "'");

                String rowGroup = nullIfBlank(row.getGroupName()) != null ? nullIfBlank(row.getGroupName())
                                : sharedGroup != null ? sharedGroup : wh.getGroupName();
                String rowSub   = nullIfBlank(row.getSubGroupName()) != null ? nullIfBlank(row.getSubGroupName())
                                : sharedSubGroup != null ? sharedSubGroup : wh.getSubGroupName();

                InventoryItemEntity e = InventoryItemEntity.builder()
                    .warehouseId(wh.getId())
                    .itemCode(code)
                    .name(row.getName().trim())
                    .category(nullIfBlank(row.getCategory()))
                    .unit(nullIfBlank(row.getUnit()))
                    .currentQty(orZero(row.getCurrentQty()))
                    .minQty(orZero(row.getMinQty()))
                    .maxQty(orZero(row.getMaxQty()))
                    .unitCost(orZero(row.getUnitCost()))
                    .groupName(rowGroup)
                    .subGroupName(rowSub)
                    .projectId(nullIfBlank(row.getProjectId()))
                    .notes(row.getNotes())
                    .isActive(row.getIsActive() != null ? row.getIsActive() : Boolean.TRUE)
                    .createdBy(createdBy)
                    .build();

                // saveAndFlush commits this row immediately in its own transaction.
                // A failure here only affects this single row.
                InventoryItemEntity saved = itemRepository.saveAndFlush(e);
                created.add(InventoryItemWrapper.from(saved, wh));
            } catch (Exception ex) {
                // Catch broadly — DataIntegrityViolationException and other runtime DB
                // errors must not escape; they would not be re-caught otherwise.
                Map<String, Object> f = new HashMap<>();
                f.put("index",    idx);
                f.put("itemCode", row != null ? row.getItemCode() : null);
                f.put("name",     row != null ? row.getName()     : null);
                f.put("message",  ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                failed.add(f);
                log.warn("Bulk item [{}] '{}' failed: {}", idx,
                         row != null ? row.getItemCode() : "null", ex.getMessage());
            }
        }

        log.info("Bulk inventory create: wh={}, created={}, failed={}",
                 wh.getId(), created.size(), failed.size());

        Map<String, Object> resp = new HashMap<>();
        resp.put("created",      created);
        resp.put("failed",       failed);
        resp.put("createdCount", created.size());
        resp.put("failedCount",  failed.size());
        return resp;
    }

    @Transactional
    public InventoryItemWrapper update(Long id, InventoryItemWrapper req) {
        InventoryItemEntity e = itemRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));

        if (req.getWarehouseId() != null && !req.getWarehouseId().equals(e.getWarehouseId())) {
            String code = req.getItemCode() != null && !req.getItemCode().isBlank()
                ? req.getItemCode().trim() : e.getItemCode();
            if (itemRepository.existsByWarehouseIdAndItemCode(req.getWarehouseId(), code))
                throw new IllegalArgumentException("Item code already exists in target warehouse");
            e.setWarehouseId(req.getWarehouseId());
        }
        if (req.getItemCode() != null && !req.getItemCode().isBlank() && !req.getItemCode().equals(e.getItemCode())) {
            if (itemRepository.existsByWarehouseIdAndItemCode(e.getWarehouseId(), req.getItemCode().trim()))
                throw new IllegalArgumentException("Item code already exists in this warehouse");
            e.setItemCode(req.getItemCode().trim());
        }
        if (req.getName()     != null && !req.getName().isBlank()) e.setName(req.getName().trim());
        if (req.getCategory() != null) e.setCategory(nullIfBlank(req.getCategory()));
        if (req.getUnit()     != null) e.setUnit(nullIfBlank(req.getUnit()));
        if (req.getCurrentQty() != null) e.setCurrentQty(req.getCurrentQty());
        if (req.getMinQty()     != null) e.setMinQty(req.getMinQty());
        if (req.getMaxQty()     != null) e.setMaxQty(req.getMaxQty());
        if (req.getUnitCost()   != null) e.setUnitCost(req.getUnitCost());
        if (req.getGroupName()    != null) e.setGroupName(nullIfBlank(req.getGroupName()));
        if (req.getSubGroupName() != null) e.setSubGroupName(nullIfBlank(req.getSubGroupName()));
        if (req.getProjectId()    != null) e.setProjectId(nullIfBlank(req.getProjectId()));
        if (req.getNotes()    != null) e.setNotes(req.getNotes());
        if (req.getIsActive() != null) e.setIsActive(req.getIsActive());

        InventoryItemEntity saved = itemRepository.save(e);
        WarehouseEntity wh = warehouseRepository.findById(saved.getWarehouseId()).orElse(null);
        return InventoryItemWrapper.from(saved, wh);
    }

    // ── Two-step soft-delete ─────────────────────────────────────────────────


    // ── Image upload ─────────────────────────────────────────────────────────

    @Transactional
    public InventoryItemWrapper updateImage(Long id, String imageData) {
        InventoryItemEntity e = itemRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
        e.setImageData(imageData);
        InventoryItemEntity saved = itemRepository.save(e);
        WarehouseEntity wh = warehouseRepository.findById(saved.getWarehouseId()).orElse(null);
        log.info("Inventory item image {}: id={}", imageData == null ? "removed" : "updated", id);
        return InventoryItemWrapper.from(saved, wh);
    }

    @Transactional
    public boolean delete(Long id) {
        InventoryItemEntity e = itemRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Inventory item not found: " + id));
        if (Boolean.FALSE.equals(e.getIsActive())) {
            itemRepository.deleteById(id);
            log.info("Inventory item hard-deleted: id={}", id);
            return true;
        }
        e.setIsActive(false);
        itemRepository.save(e);
        log.info("Inventory item soft-deleted: id={}", id);
        return false;
    }

    // ── Stock receipt hook ───────────────────────────────────────────────────

    @Transactional
    public InventoryItemWrapper receive(Long warehouseId, String itemCode, String name, String unit,
                                        BigDecimal qty, BigDecimal unitCost,
                                        String projectId, Long createdBy) {
        if (qty == null || qty.signum() <= 0)
            throw new IllegalArgumentException("Receive quantity must be > 0");
        WarehouseEntity wh = warehouseRepository.findById(warehouseId)
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + warehouseId));

        InventoryItemEntity item = itemRepository.findByWarehouseIdAndItemCode(warehouseId, itemCode)
            .orElseGet(() -> InventoryItemEntity.builder()
                .warehouseId(warehouseId)
                .itemCode(itemCode)
                .name(name != null ? name : itemCode)
                .unit(unit)
                .currentQty(BigDecimal.ZERO)
                .minQty(BigDecimal.ZERO)
                .maxQty(BigDecimal.ZERO)
                .unitCost(orZero(unitCost))
                .projectId(projectId)
                .groupName(wh.getGroupName())
                .subGroupName(wh.getSubGroupName())
                .isActive(Boolean.TRUE)
                .createdBy(createdBy)
                .build());

        item.setCurrentQty(orZero(item.getCurrentQty()).add(qty));
        if (unitCost != null && unitCost.signum() > 0) item.setUnitCost(unitCost);

        InventoryItemEntity saved = itemRepository.save(item);
        return InventoryItemWrapper.from(saved, wh);
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static String nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
    private static BigDecimal orZero(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private Map<Long, WarehouseEntity> batchLoadWarehouses(List<InventoryItemEntity> rows) {
        if (rows.isEmpty()) return new HashMap<>();
        List<Long> ids = rows.stream().map(InventoryItemEntity::getWarehouseId).distinct().collect(Collectors.toList());
        return warehouseRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(WarehouseEntity::getId, w -> w));
    }
}