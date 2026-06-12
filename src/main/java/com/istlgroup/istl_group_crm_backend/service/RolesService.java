package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.RolesEntity;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRoleWithStatsWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.GetRolesWrapper;

@Service
public class RolesService {

	@Autowired
	private RolesRepo rolesRepo;
	
	public List<GetRolesWrapper> GetAllRoles() {
		return rolesRepo.getAllRolesWithIds();
	}

	public String AddNewRole(RolesEntity newRole) throws CustomException {
		// Normalise name to UPPER_SNAKE_CASE before duplicate check and save
		newRole.setName(RoleNormalizer.normalizeRequired(newRole.getName()));
		newRole.setCreated_at(LocalDateTime.now());
		Integer isRoleExist = rolesRepo.findRole(newRole);
		if (isRoleExist > 0) {
		    throw new CustomException("Role already exist");
		}
	    rolesRepo.save(newRole);
	    return "New Role Created Successfully";
	}

	public String DeleteRole(Integer roleId) throws CustomException {
		if (!rolesRepo.existsById(roleId))
			throw new CustomException("Role not found");
		rolesRepo.deleteById(roleId);
		return "Role deleted successfully";
	}
	
	public List<GetRoleWithStatsWrapper> GetAllRolesWithStats() {
	    return rolesRepo.getAllRolesWithStats();
	}
}