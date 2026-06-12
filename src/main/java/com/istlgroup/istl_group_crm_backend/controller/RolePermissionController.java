package com.istlgroup.istl_group_crm_backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.istlgroup.istl_group_crm_backend.service.RolePermissionService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.AssignPermissionsRequest;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetPermissionsByRoleWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolePermissionsWrapper;

@RestController
@RequestMapping("/role-permission")
//@CrossOrigin(origins = "${cros.allowed-origins}")
public class RolePermissionController {

	
	@Autowired
	private RolePermissionService rolePermissionService;
	
	@GetMapping("/getAllRolePermissions")
	public List<GetRolePermissionsWrapper> GetAllRolePermissions() {
		return rolePermissionService.GetAllRolePermissions();
	}
	@PostMapping("/assignPermissions")
	public ResponseEntity<String> assignPermissions(@RequestBody AssignPermissionsRequest request) {
				rolePermissionService.assignPermissionsToRole(request);
	        return ResponseEntity.ok("Permissions assigned successfully");
	    }
	
	@GetMapping("/getPermissionsByRole/{roleId}")
	public List<GetPermissionsByRoleWrapper> getPermissionsByRole(@PathVariable Integer roleId) {
	    return rolePermissionService.getPermissionsByRole(roleId);
	}
}
