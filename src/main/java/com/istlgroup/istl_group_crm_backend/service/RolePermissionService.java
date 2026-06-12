package com.istlgroup.istl_group_crm_backend.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.entity.RolePermissionsEntity;
import com.istlgroup.istl_group_crm_backend.repo.PermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolePermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.AssignPermissionsRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetPermissionsByRoleWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolePermissionsWrapper;

import jakarta.transaction.Transactional;

@Service
public class RolePermissionService {

	
	@Autowired
	private RolePermissionsRepo rolePermissionsRepo;
	
	@Autowired
	private RolesRepo rolesRepo;
	
//	@Autowired
//	private PermissionsRepo permissionsRepo;
	
	public List<GetRolePermissionsWrapper> GetAllRolePermissions() {
	    return rolePermissionsRepo.GetAllRolePermissions();
	}

	@Transactional
	public void assignPermissionsToRole(AssignPermissionsRequest request) {

	        
		 rolesRepo.findById(request.getRole_id())
	            .orElseThrow(() -> new RuntimeException("Invalid Role ID"));

	     
	        // 3️⃣ Remove existing permissions
	        rolePermissionsRepo.deleteByRoleId(request.getRole_id());

	        // 4️⃣ Assign new permissions
	        request.getPermission_ids().forEach(pid -> {
	        	RolePermissionsEntity rp = new RolePermissionsEntity();
	            rp.setRole_id(request.getRole_id());	            
	            rp.setPermission_id(pid);
	            rolePermissionsRepo.save(rp);
	        });
	    }

	public List<GetPermissionsByRoleWrapper> getPermissionsByRole(Integer roleId) {
	    return rolePermissionsRepo.findPermissionsByRoleId(roleId);
	}
}
