package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.BomItemsMasterEntity;
import com.istlgroup.istl_group_crm_backend.repo.BomItemsMasterRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BomItemsMasterService {

    private final BomItemsMasterRepo bomItemsMasterRepo;

    /**
     * Get all active BOM items
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllActiveBomItems() {
        return bomItemsMasterRepo.findByIsActiveTrue().stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Get BOM items by category
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBomItemsByCategory(String category) {
        return bomItemsMasterRepo.findByCategoryAndIsActiveTrue(category).stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Search BOM items (autocomplete)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchBomItems(String searchTerm, String category) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            // Return all items if no search term
            if (category != null && !category.isEmpty()) {
                return getBomItemsByCategory(category);
            }
            return getAllActiveBomItems();
        }

        List<BomItemsMasterEntity> items;
        if (category != null && !category.isEmpty()) {
            items = bomItemsMasterRepo.searchBomItemsByCategory(category, searchTerm.trim());
        } else {
            items = bomItemsMasterRepo.searchBomItems(searchTerm.trim());
        }

        return items.stream()
                .limit(20) // Limit to 20 results for autocomplete
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    /**
     * Get distinct categories
     */
    @Transactional(readOnly = true)
    public List<String> getDistinctCategories() {
        return bomItemsMasterRepo.findDistinctCategories();
    }

    /**
     * Get BOM item by ID
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getBomItemById(Long id) {
        return bomItemsMasterRepo.findById(id)
                .map(this::convertToMap);
    }

    /**
     * Convert entity to map for API response
     */
    private Map<String, Object> convertToMap(BomItemsMasterEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId());
        map.put("category", entity.getCategory());
        map.put("itemName", entity.getItemName());
        map.put("description", entity.getDescription());
        map.put("specification", entity.getSpecification());
        map.put("defaultUnit", entity.getDefaultUnit());
        map.put("defaultTaxPercent", entity.getDefaultTaxPercent());
        map.put("makeBrand", entity.getMakeBrand());
        map.put("hsnCode", entity.getHsnCode());
        return map;
    }
}