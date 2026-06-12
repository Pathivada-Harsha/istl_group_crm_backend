package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.PermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UserPagePermissionEntity;
import com.istlgroup.istl_group_crm_backend.repo.PermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolePermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserPagePermissionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolesWrapper;

import jakarta.transaction.Transactional;

@Service
public class PermissionsService {

    @Autowired
    private PermissionsRepo permissionsRepo;

    @Autowired
    private RolePermissionsRepo rolePermissionsRepo;
    
    @Autowired
    private UserPagePermissionRepo userPagePermissionRepo;

    public List<GetRolesWrapper> GetAllPermissions() {
        return permissionsRepo.getAllPermissionsWithIds();
    }

    @Transactional
    public String AddNewPermission(PermissionsEntity newPermissions, Long creatorUserId) throws CustomException {
        newPermissions.setCreated_at(LocalDateTime.now());
        Integer isPermissionExist = permissionsRepo.findPermission(newPermissions);
        if (isPermissionExist > 0) {
            throw new CustomException("Permission already exist");
        }
        PermissionsEntity saved = permissionsRepo.save(newPermissions);

        // Creator gets has_permission = true, all others get false
        // Mirrors addMenuItem pattern exactly
        List<Long> allUserIds = userPagePermissionRepo.findAllUserIds();
        List<UserPagePermissionEntity> entries = new ArrayList<>();
        for (Long userId : allUserIds) {
            UserPagePermissionEntity entry = new UserPagePermissionEntity();
            entry.setUserId(userId);
            entry.setPermissionId(saved.getId());
            entry.setHasPermission(userId.equals(creatorUserId));
            entries.add(entry);
        }
        userPagePermissionRepo.saveAll(entries);

        return "New Permission Created Successfully";
    }
    
    @Transactional
    public String updatePermission(Integer id, Map<String, String> body) throws CustomException {
        PermissionsEntity existing = permissionsRepo.findById(id)
                .orElseThrow(() -> new CustomException("Permission not found"));
        String newName = body.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            throw new CustomException("Permission name is required");
        }
        // Check duplicate — exclude the current record itself
        Integer dupCount = permissionsRepo.countByNameExcludingId(newName.trim(), id);
        if (dupCount != null && dupCount > 0) {
            throw new CustomException("Permission \"" + newName.trim() + "\" already exists");
        }
        existing.setName(newName.trim());
        permissionsRepo.save(existing);
        return "Permission updated successfully";
    }

    @Transactional
    public String deletePermission(Integer id) throws CustomException {
        if (!permissionsRepo.existsById(id)) {
            throw new CustomException("Permission not found");
        }
        // 1. Remove from role_permissions (role-level)
        rolePermissionsRepo.deleteByPermissionId(id);
        // 2. Remove from user_page_permissions (user-level) — same as how menu item deletion
        //    cascades to user_menu_permissions
        userPagePermissionRepo.deleteByPermissionId(id);
        // 3. Remove from permissions master table
        permissionsRepo.deleteById(id);
        return "Permission deleted successfully";
    }
}