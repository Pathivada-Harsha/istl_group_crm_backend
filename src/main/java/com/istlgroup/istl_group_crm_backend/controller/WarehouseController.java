package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.WarehouseService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.WarehouseWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Public warehouse endpoints — used by the Inventory Management page.
 *
 *   GET /warehouses                       — list, optionally scoped by group / sub-group
 *   GET /warehouses/{id}                  — single
 *
 * Note: there is no projectId parameter — warehouses are independent of projects.
 * Admin CRUD lives in {@link WarehouseAdminController} at /admin/dropdowns/warehouses.
 */
@RestController
@RequestMapping("/warehouses")
@RequiredArgsConstructor
@Slf4j
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String  groupName,
            @RequestParam(required = false) String  subGroupName,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestHeader(value = "X-User-Id",   required = false) Long   userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        try {
            List<WarehouseWrapper> list = warehouseService.list(groupName, subGroupName, includeInactive);
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            log.error("Failed to list warehouses", e);
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
}