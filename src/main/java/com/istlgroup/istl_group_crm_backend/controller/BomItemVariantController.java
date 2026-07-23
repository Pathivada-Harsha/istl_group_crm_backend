package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.BomItemVariantEntity;
import com.istlgroup.istl_group_crm_backend.service.BomItemVariantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variant (make/model) CRUD for bom_items_master items. Additive to the
 * read-only BomItemsMasterController — none of that controller's GET contracts
 * change. Response envelope matches it: { success, data, count }.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BomItemVariantController {

    private final BomItemVariantService variantService;

    /** GET /bom-items-master/{itemId}/variants[?activeOnly=true] */
    @GetMapping("/bom-items-master/{itemId}/variants")
    public ResponseEntity<Map<String, Object>> listVariants(
            @PathVariable Long itemId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        try {
            List<Map<String, Object>> data = variantService.listByItem(itemId, activeOnly);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            response.put("count", data.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error listing variants for item {}", itemId, e);
            return errorResponse("Failed to fetch variants", e.getMessage());
        }
    }

    /** POST /bom-items-master/{itemId}/variants */
    @PostMapping("/bom-items-master/{itemId}/variants")
    public ResponseEntity<Map<String, Object>> createVariant(
            @PathVariable Long itemId, @RequestBody BomItemVariantEntity body) {
        try {
            Map<String, Object> data = variantService.create(itemId, body);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return notFoundResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error creating variant for item {}", itemId, e);
            return errorResponse("Failed to create variant", e.getMessage());
        }
    }

    /** PUT /bom-item-variants/{id} */
    @PutMapping("/bom-item-variants/{id}")
    public ResponseEntity<Map<String, Object>> updateVariant(
            @PathVariable Long id, @RequestBody BomItemVariantEntity body) {
        try {
            Map<String, Object> data = variantService.update(id, body);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return notFoundResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error updating variant {}", id, e);
            return errorResponse("Failed to update variant", e.getMessage());
        }
    }

    /** DELETE /bom-item-variants/{id} — deactivate (never hard-delete). */
    @DeleteMapping("/bom-item-variants/{id}")
    public ResponseEntity<Map<String, Object>> deactivateVariant(@PathVariable Long id) {
        try {
            variantService.deactivate(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Variant deactivated");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return notFoundResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error deactivating variant {}", id, e);
            return errorResponse("Failed to deactivate variant", e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> notFoundResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(String message, String details) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("error", details);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
