package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.service.OrderBookItemCatalogueService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.OrderBookItemCatalogueWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/order-book/item-catalogue")
public class OrderBookItemCatalogueController {

    @Autowired
    private OrderBookItemCatalogueService catalogueService;

    /**
     * GET /order-book/item-catalogue/search?q=cable
     *
     * Returns up to 10 matching catalogue items for the autocomplete dropdown.
     * Minimum 2 characters required (enforced in service layer too).
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestHeader("User-Id") Long userId,
            @RequestHeader("User-Role") String userRole) {

        Map<String, Object> response = new HashMap<>();
        try {
            List<OrderBookItemCatalogueWrapper> results = catalogueService.search(q);
            response.put("success", true);
            response.put("data", results);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}