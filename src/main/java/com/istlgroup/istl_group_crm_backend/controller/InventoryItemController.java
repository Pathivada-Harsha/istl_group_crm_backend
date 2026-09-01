package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.service.InventoryItemService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InventoryItemBulkWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.InventoryItemWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Inventory item endpoints — backs the Items tab on the Inventory page.
 *
 *   GET /inventory/items
 *       ?warehouseId=   — required for scoped view (null = all warehouses)
 *       &groupName=     — STRICT filter: only items with this exact groupName
 *       &subGroupName=  — STRICT filter: only items with this exact subGroupName
 *       &category=
 *       &search=
 *       &page=0         — zero-based
 *       &size=20
 *       &includeInactive=false
 *       → { content:[], totalElements, totalPages, size, number }
 */
@RestController
@RequestMapping("/inventory/items")
@RequiredArgsConstructor
@Slf4j
public class InventoryItemController {

    private final InventoryItemService itemService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false)                          Long    warehouseId,
            @RequestParam(required = false)                          String  groupName,
            @RequestParam(required = false)                          String  subGroupName,
            @RequestParam(required = false)                          String  category,
            @RequestParam(required = false)                          String  search,
            @RequestParam(required = false, defaultValue = "0")      int     page,
            @RequestParam(required = false, defaultValue = "20")     int     size,
            @RequestParam(required = false, defaultValue = "false")  boolean includeInactive,
            @ActingUserId  Long    userId,
            @ActingUserRole  String  userRole) {
        try {
            return ResponseEntity.ok(
                itemService.listPaged(warehouseId, groupName, subGroupName,
                                      category, search, page, size, includeInactive));
        } catch (Exception e) {
            log.error("Failed to list inventory items", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to load items"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(itemService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody InventoryItemWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(itemService.create(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create inventory item", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to create item"));
        }
    }

    /**
     * Bulk create — one shared scope, many rows.
     * Returns { created:[…], failed:[…], createdCount, failedCount } with HTTP 200
     * even on partial success; only a fully invalid request (no items, missing
     * warehouse) returns 400.
     */
    @PostMapping("/bulk")
    public ResponseEntity<?> createBulk(
            @RequestBody InventoryItemBulkWrapper body,
            @ActingUserId Long userId) {
        try {
            return ResponseEntity.ok(itemService.bulkCreate(body, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to bulk-create inventory items", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to create items"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody InventoryItemWrapper body) {
        try {
            return ResponseEntity.ok(itemService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update inventory item {}", id, e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Failed to update item"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            boolean hard = itemService.delete(id);
            return ResponseEntity.ok(Map.of(
                "success",     true,
                "hardDeleted", hard,
                "message",     hard ? "Item permanently deleted" : "Item deactivated"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Upload or replace the image for an inventory item.
     * Expects JSON body: { "imageData": "data:image/png;base64,..." }
     * Frontend enforces 5 MB max; server rejects anything over 10 MB.
     */
    @PostMapping("/{id}/image")
    public ResponseEntity<?> uploadImage(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        try {
            String imageData = body.get("imageData");
            if (imageData == null || imageData.isBlank())
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "imageData is required"));

            // 10 MB limit (base64 ~= raw * 4/3, so 10 MB base64 ≈ 7.5 MB raw)
            int maxBytes = 10 * 1024 * 1024;
            if (imageData.length() > maxBytes)
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Image too large (max 10 MB)"));

            return ResponseEntity.ok(itemService.updateImage(id, imageData));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to upload image for item {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Failed to save image"));
        }
    }

    /** Remove the image from an inventory item. */
    @DeleteMapping("/{id}/image")
    public ResponseEntity<?> deleteImage(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(itemService.updateImage(id, null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
        }
    }}