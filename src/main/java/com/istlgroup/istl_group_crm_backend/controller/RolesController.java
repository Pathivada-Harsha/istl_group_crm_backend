package com.istlgroup.istl_group_crm_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.RolesEntity;
import com.istlgroup.istl_group_crm_backend.service.RolesService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRoleWithStatsWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolesWrapper;

@RestController
@RequestMapping("/roles")
//@CrossOrigin(origins = "${cros.allowed-origins}")
public class RolesController {

	@Autowired
	private RolesService rolesService;
	
	@GetMapping("/getAllRoles")
	public List<GetRolesWrapper> GetAllRoles() {
		return rolesService.GetAllRoles();
	}
	
	@PostMapping("/addNewRole")
	public ResponseEntity<Map<String, String>> AddNewRole(@RequestBody RolesEntity newRole) throws CustomException {
	    String result = rolesService.AddNewRole(newRole);
	    return ResponseEntity.ok(Map.of("message", result));
	}
	
	@GetMapping("/getAllRolesWithStats")
	public List<GetRoleWithStatsWrapper> GetAllRolesWithStats() {
	    return rolesService.GetAllRolesWithStats();
	}

	@DeleteMapping("/deleteRole/{id}")
	public ResponseEntity<Map<String, String>> DeleteRole(@PathVariable Integer id) throws CustomException {
	    String result = rolesService.DeleteRole(id);
	    return ResponseEntity.ok(Map.of("message", result));
	}
}