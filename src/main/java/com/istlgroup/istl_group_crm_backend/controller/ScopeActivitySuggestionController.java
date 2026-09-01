package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserRole;
import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.entity.ScopeActivitySuggestionEntity;
import com.istlgroup.istl_group_crm_backend.repo.ScopeActivitySuggestionRepo;

/**
 * Shared, user-extensible Technical-Scope activity/item suggestions.
 *  GET  /order-book/scope-activities          → list all names
 *  POST /order-book/scope-activities  {name}  → add (or bump) a user-typed name
 */
@RestController
@RequestMapping("/order-book/scope-activities")
public class ScopeActivitySuggestionController {

    @Autowired
    private ScopeActivitySuggestionRepo repo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        List<String> names = repo.findAll().stream()
                .map(ScopeActivitySuggestionEntity::getName)
                .collect(Collectors.toList());
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("data", Map.of("names", names));
        return ResponseEntity.ok(resp);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(
            @RequestBody Map<String, Object> body,
            @ActingUserId Long userId,
            @ActingUserRole String userRole) {
        Map<String, Object> resp = new HashMap<>();
        Object raw = body.get("name");
        String name = raw == null ? "" : String.valueOf(raw).trim();
        if (name.isEmpty() || name.length() > 255) {
            resp.put("success", false);
            resp.put("message", "Invalid name");
            return ResponseEntity.badRequest().body(resp);
        }
        repo.findByNameIgnoreCase(name).ifPresentOrElse(
            e -> { e.setUseCount(e.getUseCount() + 1); repo.save(e); },
            () -> { ScopeActivitySuggestionEntity e = new ScopeActivitySuggestionEntity(); e.setName(name); repo.save(e); }
        );
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }
}