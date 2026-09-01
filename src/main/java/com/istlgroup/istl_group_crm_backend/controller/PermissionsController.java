package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.PermissionsEntity;
import com.istlgroup.istl_group_crm_backend.service.PermissionsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolesWrapper;

@RestController
@RequestMapping("/permissions")
public class PermissionsController {

    @Autowired
    private PermissionsService permissionsService;

    @GetMapping("/getAllPermissions")
    public List<GetRolesWrapper> GetAllPermissions() {
        return permissionsService.GetAllPermissions();
    }

    @PostMapping("/addNewPermission")
    public ResponseEntity<Map<String, String>> AddNewPermission(
            @RequestBody PermissionsEntity newPermission,
            @ActingUserId Long creatorUserId) {
        try {
            String result = permissionsService.AddNewPermission(newPermission, creatorUserId);
            return ResponseEntity.ok(Map.of("message", result));
        } catch (CustomException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to create permission"));
        }
    }
    @PutMapping("/updatePermission/{id}")
    public ResponseEntity<Map<String, String>> updatePermission(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body) {
        try {
            String result = permissionsService.updatePermission(id, body);
            return ResponseEntity.ok(Map.of("message", result));
        } catch (CustomException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            String msg = (e.getMessage() != null && e.getMessage().contains("Duplicate entry"))
                    ? "Permission name already exists"
                    : "Failed to update permission";
            return ResponseEntity.badRequest().body(Map.of("message", msg));
        }
    }

    @DeleteMapping("/deletePermission/{id}")
    public ResponseEntity<Map<String, String>> deletePermission(
            @PathVariable Integer id) {
        try {
            String result = permissionsService.deletePermission(id);
            return ResponseEntity.ok(Map.of("message", result));
        } catch (CustomException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to delete permission"));
        }
    }
}