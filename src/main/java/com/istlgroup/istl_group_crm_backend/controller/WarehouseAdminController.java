package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.WarehouseService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.WarehouseWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin CRUD for warehouses — backs the "Warehouses" tab on the
 * Dropdown Management page.
 *
 *   GET    /admin/dropdowns/warehouses?page=&size=&search=
 *       - paged when page>=0 → Spring Page shape { content[], totalElements, totalPages, size, number }
 *       - page omitted (or negative) → flat List of all warehouses
 *   GET    /admin/dropdowns/warehouses/{id}
 *   POST   /admin/dropdowns/warehouses                body: WarehouseWrapper
 *   PUT    /admin/dropdowns/warehouses/{id}           body: WarehouseWrapper (partial allowed)
 *   DELETE /admin/dropdowns/warehouses/{id}           two-step soft-delete
 */
@RestController
@RequestMapping("/admin/dropdowns/warehouses")
@RequiredArgsConstructor
@Slf4j
public class WarehouseAdminController {

    private final WarehouseService warehouseService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "page",      required = false, defaultValue = "-1") int    page,
            @RequestParam(value = "size",      required = false, defaultValue = "10") int    size,
            @RequestParam(value = "search",    required = false, defaultValue = "")   String search,
            @RequestParam(value = "includeInactive", required = false, defaultValue = "true") boolean includeInactive) {
        try {
            if (page < 0) {
                return ResponseEntity.ok(warehouseService.list(null, null, includeInactive));
            }
            return ResponseEntity.ok(warehouseService.getPaged(search, page, size));
        } catch (Exception e) {
            log.error("Failed to list warehouses (admin)", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to load warehouses"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(warehouseService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody WarehouseWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(warehouseService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create warehouse", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to create warehouse"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody WarehouseWrapper body) {
        try {
            return ResponseEntity.ok(warehouseService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update warehouse {}", id, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to update warehouse"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            boolean hard = warehouseService.delete(id);
            return ResponseEntity.ok(Map.of(
                "success",     true,
                "hardDeleted", hard,
                "message",     hard ? "Warehouse permanently deleted" : "Warehouse deactivated"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete warehouse {}", id, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to delete warehouse"));
        }
    }
}