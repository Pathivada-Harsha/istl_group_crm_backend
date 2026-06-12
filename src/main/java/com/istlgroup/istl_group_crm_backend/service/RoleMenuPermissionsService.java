package com.istlgroup.istl_group_crm_backend.service;

import com.istlgroup.istl_group_crm_backend.entity.MenuItemsEntity;
import com.istlgroup.istl_group_crm_backend.entity.RoleMenuPermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UserMenuPermissionEntity;
import com.istlgroup.istl_group_crm_backend.repo.MenuItemsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RoleMenuPermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserMenuPermissionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.MenuPermissionWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.SaveMenuPermissionsRequest;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RoleMenuPermissionsService {

    @Autowired
    private RoleMenuPermissionsRepo roleMenuPermissionsRepo;

    @Autowired
    private MenuItemsRepo menuItemsRepo;

    @Autowired
    private UserMenuPermissionRepo userMenuRepo;

    @Autowired
    private RolesRepo rolesRepo;

    // ── Get menu permissions by role ─────────────────────────────────────────
    public List<MenuPermissionWrapper> getMenuPermissionsByRole(Integer roleId) {
        List<Object[]> rows = roleMenuPermissionsRepo.getRawMenuPermissionsByRoleId(roleId);
        return rows.stream().map(row -> {
            Integer menuId  = row[0] != null ? ((Number) row[0]).intValue() : null;
            String menuName = row[1] != null ? row[1].toString() : "";
            Boolean hasPerm = row[2] != null && ((Number) row[2]).intValue() == 1;
            return new MenuPermissionWrapper(menuId, menuName, hasPerm);
        }).collect(Collectors.toList());
    }

    // ── Get all menu items ───────────────────────────────────────────────────
    public List<MenuItemsEntity> getAllMenuItems() {
        return menuItemsRepo.findAll();
    }

    // ── FIXED: Save menu permissions using UPSERT (no delete) ───────────────
    @Transactional
    public String saveMenuPermissions(SaveMenuPermissionsRequest request) {
        List<MenuItemsEntity> allMenus = menuItemsRepo.findAll();

        for (MenuItemsEntity menu : allMenus) {
            boolean hasPermission = request.getMenu_ids() != null
                    && request.getMenu_ids().contains(menu.getId());

            RoleMenuPermissionsEntity existing =
                    roleMenuPermissionsRepo.findByRoleIdAndMenuId(
                            request.getRole_id(), menu.getId());

            if (existing != null) {
                if (existing.getHas_permission() != hasPermission) {
                    existing.setHas_permission(hasPermission);
                    roleMenuPermissionsRepo.save(existing);
                }
            } else {
                RoleMenuPermissionsEntity newEntry = new RoleMenuPermissionsEntity();
                newEntry.setRole_id(request.getRole_id());
                newEntry.setMenu_id(menu.getId());
                newEntry.setHas_permission(hasPermission);
                roleMenuPermissionsRepo.save(newEntry);
            }
        }
        return "Menu permissions updated successfully";
    }

    // ── NEW: Add menu item + cascade to both permission tables ───────────────
    @Transactional
    public ResponseEntity<Map<String, Object>> addMenuItem(String rawName, Long creatorUserId) {

        String normalised = rawName.trim().toLowerCase().replaceAll("\\s+", "_");

        if (normalised.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Menu item name cannot be empty"));
        }

        boolean exists = menuItemsRepo.findAll().stream()
                .anyMatch(m -> m.getName().equalsIgnoreCase(normalised));
        if (exists) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message",
                            "Menu item '" + normalised + "' already exists"));
        }

        // Insert into menu_items
        MenuItemsEntity newMenu = new MenuItemsEntity();
        newMenu.setName(normalised);
        MenuItemsEntity saved = menuItemsRepo.save(newMenu);
        Integer newMenuId = saved.getId();

        // Get creator's role name and ID
        String creatorRoleName = menuItemsRepo.getCreatorRoleName(creatorUserId);
        Integer creatorRoleId = (creatorRoleName != null)
                ? rolesRepo.findRoleIdByName(creatorRoleName)
                : null;

        // Insert into role_menu_permissions — creator's role gets 1, others get 0
        List<Integer> allRoleIds = roleMenuPermissionsRepo.findAllRoleIds();
        for (Integer roleId : allRoleIds) {
            boolean hasPerm = creatorRoleId != null && creatorRoleId.equals(roleId);
            RoleMenuPermissionsEntity rmp = new RoleMenuPermissionsEntity();
            rmp.setRole_id(roleId);
            rmp.setMenu_id(newMenuId);
            rmp.setHas_permission(hasPerm);
            roleMenuPermissionsRepo.save(rmp);
        }

        // Insert into user_menu_permissions — creator gets 1, others get 0
        List<Long> allUserIds = userMenuRepo.findAllUserIds();
        List<UserMenuPermissionEntity> userEntries = new ArrayList<>();
        for (Long userId : allUserIds) {
            UserMenuPermissionEntity ump = new UserMenuPermissionEntity();
            ump.setUserId(userId);
            ump.setMenuId(newMenuId.longValue());
            ump.setHasPermission(userId.equals(creatorUserId));
            userEntries.add(ump);
        }
        userMenuRepo.saveAll(userEntries);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Menu item '" + normalised + "' created successfully",
                "menuItem", Map.of("id", newMenuId, "name", normalised)
        ));
    }

    // ── NEW: Edit menu item name ─────────────────────────────────────────────
    @Transactional
    public ResponseEntity<Map<String, Object>> editMenuItem(Integer menuId, String rawName) {

        String normalised = rawName.trim().toLowerCase().replaceAll("\\s+", "_");

        if (normalised.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Name cannot be empty"));
        }

        MenuItemsEntity item = menuItemsRepo.findById(menuId).orElse(null);
        if (item == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Menu item not found"));
        }

        boolean duplicate = menuItemsRepo.findAll().stream()
                .anyMatch(m -> m.getName().equalsIgnoreCase(normalised)
                        && !m.getId().equals(menuId));
        if (duplicate) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message",
                            "Menu item '" + normalised + "' already exists"));
        }

        String oldName = item.getName();
        item.setName(normalised);
        menuItemsRepo.save(item);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Renamed '" + oldName + "' to '" + normalised + "'",
                "menuItem", Map.of("id", menuId, "name", normalised)
        ));
    }

    // ── NEW: Delete menu item + cascade delete from both permission tables ───
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteMenuItem(Integer menuId) {

        MenuItemsEntity item = menuItemsRepo.findById(menuId).orElse(null);
        if (item == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Menu item not found"));
        }

        String name = item.getName();

        // 1. Cascade: role_menu_permissions
        roleMenuPermissionsRepo.deleteByMenuId(menuId);

        // 2. Cascade: user_menu_permissions
        userMenuRepo.deleteByMenuId(menuId.longValue());

        // 3. Delete from menu_items
        menuItemsRepo.deleteById(menuId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Menu item '" + name + "' and all related permissions deleted"
        ));
    }
}