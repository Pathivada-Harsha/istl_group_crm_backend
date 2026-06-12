package com.istlgroup.istl_group_crm_backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.RoleHierarchyEntity;
import com.istlgroup.istl_group_crm_backend.service.RoleHierarchyService;

@RestController
@RequestMapping("/role-hierarchy")
public class RoleHierarchyController {

    @Autowired
    private RoleHierarchyService hierarchyService;

    // GET /role-hierarchy/all
    @GetMapping("/all")
    public ResponseEntity<?> getAll() {
        try {
            List<RoleHierarchyEntity> list = hierarchyService.getAllHierarchies();
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    // GET /role-hierarchy/{roleName}
    @GetMapping("/{roleName}")
    public ResponseEntity<?> getOne(@PathVariable String roleName) {
        try {
            return ResponseEntity.ok(hierarchyService.getByRoleName(roleName.toUpperCase()));
        } catch (CustomException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    // POST /role-hierarchy/save  (create or update by roleName PK)
    @PostMapping("/save")
    public ResponseEntity<?> save(
            @RequestHeader("User-Role") String callerRole,
            @RequestBody RoleHierarchyEntity entity) {
        if (!"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only SUPERADMIN can modify role hierarchy"));
        }
        try {
            entity.setRoleName(entity.getRoleName().toUpperCase());
            RoleHierarchyEntity saved = hierarchyService.saveHierarchy(entity);
            return ResponseEntity.ok(Map.of("message", "Saved successfully", "data", saved));
        } catch (CustomException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // PUT /role-hierarchy/{roleName}
    @PutMapping("/{roleName}")
    public ResponseEntity<?> update(
            @PathVariable String roleName,
            @RequestHeader("User-Role") String callerRole,
            @RequestBody RoleHierarchyEntity entity) {
        if (!"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only SUPERADMIN can modify role hierarchy"));
        }
        try {
            RoleHierarchyEntity updated = hierarchyService.updateHierarchy(roleName.toUpperCase(), entity);
            return ResponseEntity.ok(Map.of("message", "Updated successfully", "data", updated));
        } catch (CustomException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    // DELETE /role-hierarchy/{roleName}
    @DeleteMapping("/{roleName}")
    public ResponseEntity<?> delete(
            @PathVariable String roleName,
            @RequestHeader("User-Role") String callerRole) {
        if (!"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only SUPERADMIN can delete role hierarchy entries"));
        }
        try {
            hierarchyService.deleteHierarchy(roleName.toUpperCase());
            return ResponseEntity.ok(Map.of("message", "Deleted successfully"));
        } catch (CustomException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }
}