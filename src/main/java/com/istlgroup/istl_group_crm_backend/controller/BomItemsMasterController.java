package com.istlgroup.istl_group_crm_backend.controller;

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
    @GetMapping("/{id}")
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