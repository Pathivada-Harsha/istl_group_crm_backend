package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.WarehouseEntity;
import com.istlgroup.istl_group_crm_backend.repo.WarehouseRepository;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.WarehouseWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    // ── Read (scoped, for inventory page) ────────────────────────────────────

    /**
     * Returns warehouses visible under the supplied group / sub-group filter.
     * By default INACTIVE warehouses are excluded. Warehouses are NOT scoped
     * by project — that parameter does not exist.
     */
    @Transactional(readOnly = true)
    public List<WarehouseWrapper> list(String groupName, String subGroupName, boolean includeInactive) {
        return warehouseRepository
            .findScoped(nullIfBlank(groupName), nullIfBlank(subGroupName), !includeInactive)
            .stream()
            .map(WarehouseWrapper::from)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WarehouseWrapper getById(Long id) {
        return warehouseRepository.findById(id)
            .map(WarehouseWrapper::from)
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + id));
    }

    // ── Paginated admin list (Dropdown Management page) ─────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getPaged(String search, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);
        String s = (search != null && !search.isBlank()) ? search.trim() : "";
        Page<WarehouseEntity> p = warehouseRepository.searchPaged(s, pageable);

        Map<String, Object> resp = new HashMap<>();
        resp.put("content",       p.getContent().stream().map(WarehouseWrapper::from).collect(Collectors.toList()));
        resp.put("totalElements", p.getTotalElements());
        resp.put("totalPages",    p.getTotalPages());
        resp.put("size",          p.getSize());
        resp.put("number",        p.getNumber());
        return resp;
    }

    // ── Create / Update ──────────────────────────────────────────────────────

    @Transactional
    public WarehouseWrapper create(WarehouseWrapper req, Long createdBy) {
        if (req.getCode() == null || req.getCode().isBlank())
            throw new IllegalArgumentException("Warehouse code is required");
        if (req.getName() == null || req.getName().isBlank())
            throw new IllegalArgumentException("Warehouse name is required");
        if (warehouseRepository.existsByCode(req.getCode().trim()))
            throw new IllegalArgumentException("Warehouse code already exists: " + req.getCode());

        WarehouseEntity e = WarehouseEntity.builder()
            .code(req.getCode().trim())
            .name(req.getName().trim())
            .city(req.getCity())
            .address(req.getAddress())
            .inCharge(req.getInCharge())
            .phone(req.getPhone())
            .groupName(nullIfBlank(req.getGroupName()))
            .subGroupName(nullIfBlank(req.getSubGroupName()))
            .isActive(req.getIsActive() != null ? req.getIsActive() : Boolean.TRUE)
            .notes(req.getNotes())
            .createdBy(createdBy)
            .build();
        WarehouseEntity saved = warehouseRepository.save(e);
        log.info("Warehouse created: id={}, code={}", saved.getId(), saved.getCode());
        return WarehouseWrapper.from(saved);
    }

    @Transactional
    public WarehouseWrapper update(Long id, WarehouseWrapper req) {
        WarehouseEntity e = warehouseRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + id));

        if (req.getCode() != null && !req.getCode().isBlank() && !req.getCode().equals(e.getCode())) {
            if (warehouseRepository.existsByCode(req.getCode().trim()))
                throw new IllegalArgumentException("Warehouse code already exists: " + req.getCode());
            e.setCode(req.getCode().trim());
        }
        if (req.getName()     != null && !req.getName().isBlank()) e.setName(req.getName().trim());
        if (req.getCity()     != null) e.setCity(req.getCity());
        if (req.getAddress()  != null) e.setAddress(req.getAddress());
        if (req.getInCharge() != null) e.setInCharge(req.getInCharge());
        if (req.getPhone()    != null) e.setPhone(req.getPhone());
        if (req.getGroupName()    != null) e.setGroupName(nullIfBlank(req.getGroupName()));
        if (req.getSubGroupName() != null) e.setSubGroupName(nullIfBlank(req.getSubGroupName()));
        if (req.getIsActive() != null) e.setIsActive(req.getIsActive());
        if (req.getNotes()    != null) e.setNotes(req.getNotes());

        WarehouseEntity saved = warehouseRepository.save(e);
        log.info("Warehouse updated: id={}", saved.getId());
        return WarehouseWrapper.from(saved);
    }

    // ── Two-step soft-delete ────────────────────────────────────────────────

    @Transactional
    public boolean delete(Long id) {
        WarehouseEntity e = warehouseRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + id));
        if (Boolean.FALSE.equals(e.getIsActive())) {
            warehouseRepository.deleteById(id);
            log.info("Warehouse hard-deleted: id={}", id);
            return true;
        }
        e.setIsActive(false);
        warehouseRepository.save(e);
        log.info("Warehouse soft-deleted (deactivated): id={}", id);
        return false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}