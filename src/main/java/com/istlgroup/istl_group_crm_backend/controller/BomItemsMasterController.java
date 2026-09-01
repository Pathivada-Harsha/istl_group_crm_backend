package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.service.BomCatalogueHealthService;
import com.istlgroup.istl_group_crm_backend.service.BomItemsMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/bom-items-master")
@RequiredArgsConstructor
@Slf4j
public class BomItemsMasterController {

    private final BomItemsMasterService bomItemsMasterService;
    private final BomCatalogueHealthService bomCatalogueHealthService;

    /**
     * GET /api/bom-items-master/attribute-health
     * Which makes are missing a numeric attribute that an active template's
     * auto-sizing basis depends on — the catalogue side of "the quantity came
     * out blank and nobody could say why".
     */
    @GetMapping("/attribute-health")
    public ResponseEntity<Map<String, Object>> attributeHealth() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", bomCatalogueHealthService.attributeHealth());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error computing catalogue attribute health", e);
            return createErrorResponse("Failed to check catalogue attributes", e.getMessage());
        }
    }

    /**
     * GET /api/bom-items-master/all
     * Get all active BOM items
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllBomItems() {
        try {
            List<Map<String, Object>> items = bomItemsMasterService.getAllActiveBomItems();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching all BOM items", e);
            return createErrorResponse("Failed to fetch BOM items", e.getMessage());
        }
    }

    /**
     * GET /api/bom-items-master/by-category?category=CCMS
     * Get BOM items by category
     */
    @GetMapping("/by-category")
    public ResponseEntity<Map<String, Object>> getBomItemsByCategory(
            @RequestParam String category) {
        try {
            List<Map<String, Object>> items = bomItemsMasterService.getBomItemsByCategory(category);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching BOM items by category: {}", category, e);
            return createErrorResponse("Failed to fetch BOM items by category", e.getMessage());
        }
    }

    /**
     * GET /api/bom-items-master/search?searchTerm=solar&category=EPC
     * Search BOM items (autocomplete)
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchBomItems(
            @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) String category) {
        try {
            log.info("Searching BOM items - searchTerm: {}, category: {}", searchTerm, category);
            
            List<Map<String, Object>> items = bomItemsMasterService.searchBomItems(searchTerm, category);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error searching BOM items", e);
            return createErrorResponse("Failed to search BOM items", e.getMessage());
        }
    }

    /**
     * GET /api/bom-items-master/categories
     * Get distinct categories
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> getCategories() {
        try {
            List<String> categories = bomItemsMasterService.getDistinctCategories();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", categories);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching BOM categories", e);
            return createErrorResponse("Failed to fetch categories", e.getMessage());
        }
    }

    /**
     * GET /api/bom-items-master/{id}
     * Get BOM item by ID
     */
    @GetMapping("/{id:[0-9]+}")
    public ResponseEntity<Map<String, Object>> getBomItemById(@PathVariable Long id) {
        try {
            return bomItemsMasterService.getBomItemById(id)
                    .map(item -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", item);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", false);
                        response.put("message", "BOM item not found with ID: " + id);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                    });
        } catch (Exception e) {
            log.error("Error fetching BOM item by ID: {}", id, e);
            return createErrorResponse("Failed to fetch BOM item", e.getMessage());
        }
    }

    // ── Master-data admin (write side) — additive; GET contracts above unchanged ──

    /** GET /bom-items-master/admin?search=&category= — list incl. inactive, for the admin page. */
    @GetMapping("/admin")
    public ResponseEntity<Map<String, Object>> adminList(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) {
        try {
            List<Map<String, Object>> items = bomItemsMasterService.adminList(category, search);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error listing BOM items for admin", e);
            return createErrorResponse("Failed to list BOM items", e.getMessage());
        }
    }

    /** POST /bom-items-master — create a catalog item. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody BomItemsMasterEntity body) {
        try {
            Map<String, Object> data = bomItemsMasterService.createItem(body);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error creating BOM item", e);
            return createErrorResponse("Failed to create BOM item", e.getMessage());
        }
    }

    /** PUT /bom-items-master/{id} — edit a catalog item (also reactivates via isActive). */
    @PutMapping("/{id:[0-9]+}")
    public ResponseEntity<Map<String, Object>> updateItem(
            @PathVariable Long id, @RequestBody BomItemsMasterEntity body) {
        try {
            Map<String, Object> data = bomItemsMasterService.updateItem(id, body);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error updating BOM item {}", id, e);
            return createErrorResponse("Failed to update BOM item", e.getMessage());
        }
    }

    /** DELETE /bom-items-master/{id} — deactivate (never hard-delete). */
    @DeleteMapping("/{id:[0-9]+}")
    public ResponseEntity<Map<String, Object>> deactivateItem(@PathVariable Long id) {
        try {
            bomItemsMasterService.deactivateItem(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Item deactivated");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error deactivating BOM item {}", id, e);
            return createErrorResponse("Failed to deactivate BOM item", e.getMessage());
        }
    }

    /** GET /bom-items-master/by-subgroup?subGroup=Solar_Rooftop — items used in that subgroup's template BOM. */
    @GetMapping("/by-subgroup")
    public ResponseEntity<Map<String, Object>> itemsBySubgroup(@RequestParam String subGroup) {
        try {
            List<Map<String, Object>> items = bomItemsMasterService.itemsUsedInSubgroup(subGroup);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", items);
            response.put("count", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error listing BOM items for subgroup {}", subGroup, e);
            return createErrorResponse("Failed to list subgroup BOM items", e.getMessage());
        }
    }

    /** POST /bom-items-master/adopt?templateItemId=123 — adopt a BOM line into the catalog and link it. */
    @PostMapping("/adopt")
    public ResponseEntity<Map<String, Object>> adopt(@RequestParam Long templateItemId) {
        try {
            Map<String, Object> data = bomItemsMasterService.adoptFromTemplateLine(templateItemId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        } catch (Exception e) {
            log.error("Error adopting template line {}", templateItemId, e);
            return createErrorResponse("Failed to adopt item", e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> badRequestResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Create error response
     */
    private ResponseEntity<Map<String, Object>> createErrorResponse(String message, String details) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("error", details);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}