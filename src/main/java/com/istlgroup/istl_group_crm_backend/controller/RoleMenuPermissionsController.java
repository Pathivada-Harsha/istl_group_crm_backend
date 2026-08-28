package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.security.ActingUserId;
import com.istlgroup.istl_group_crm_backend.entity.MenuItemsEntity;
import com.istlgroup.istl_group_crm_backend.service.RoleMenuPermissionsService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.MenuPermissionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SaveMenuPermissionsRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/menu-permissions")
public class RoleMenuPermissionsController {

    @Autowired
    private RoleMenuPermissionsService roleMenuPermissionsService;

    // ── Existing endpoints ───────────────────────────────────────────────────

    @GetMapping("/getAllMenuItems")
    public List<MenuItemsEntity> getAllMenuItems() {
        return roleMenuPermissionsService.getAllMenuItems();
    }

    @GetMapping("/getByRole/{roleId}")
    public List<MenuPermissionWrapper> getByRole(@PathVariable Integer roleId) {
        return roleMenuPermissionsService.getMenuPermissionsByRole(roleId);
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, String>> save(@RequestBody SaveMenuPermissionsRequest request) {
        String result = roleMenuPermissionsService.saveMenuPermissions(request);
        return ResponseEntity.ok(Map.of("message", result));
    }

    // ── New: Menu Item CRUD ──────────────────────────────────────────────────

    /**
     * POST /menu-permissions/addMenuItem
     * Body: { "name": "New Page" }
     * Header: User-Id: <creatorUserId>
     */
    @PostMapping("/addMenuItem")
    public ResponseEntity<Map<String, Object>> addMenuItem(
            @RequestBody Map<String, String> body,
            @ActingUserId Long creatorUserId) {

        String name = body.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Name is required"));
        }
        return roleMenuPermissionsService.addMenuItem(name, creatorUserId);
    }

    /**
     * PUT /menu-permissions/editMenuItem/{menuId}
     * Body: { "name": "Updated Name" }
     */
    @PutMapping("/editMenuItem/{menuId}")
    public ResponseEntity<Map<String, Object>> editMenuItem(
            @PathVariable Integer menuId,
            @RequestBody Map<String, String> body) {

        String name = body.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Name is required"));
        }
        return roleMenuPermissionsService.editMenuItem(menuId, name);
    }

    /**
     * DELETE /menu-permissions/deleteMenuItem/{menuId}
     * Cascades: removes from role_menu_permissions + user_menu_permissions first
     */
    @DeleteMapping("/deleteMenuItem/{menuId}")
    public ResponseEntity<Map<String, Object>> deleteMenuItem(@PathVariable Integer menuId) {
        return roleMenuPermissionsService.deleteMenuItem(menuId);
    }
}