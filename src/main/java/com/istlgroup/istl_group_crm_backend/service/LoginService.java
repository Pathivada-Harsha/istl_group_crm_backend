package com.istlgroup.istl_group_crm_backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.MenuPermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.PermissionsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.repo.MenuPermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.PermissionsRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LoginCredentialsWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LoginResponseWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UserWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UsersResponseWrapper;


@Service
public class LoginService {
	
	@Autowired
	private LoginRepo loginRepo;
	
	@Autowired
	private MenuPermissionsRepo menu_permissions;
	
	@Autowired
	private PermissionsRepo page_permissions;

	public ResponseEntity<LoginResponseWrapper> AuthenticateUser(Map<String, String> credentials) throws CustomException{
		
		String username=credentials.get("username");
		String password=credentials.get("password");
		
		LoginEntity response=loginRepo.AuthenticateUser(username,password);
		if (response == null) {
	        throw new CustomException("Invalid Credentials");
	    }
		
		Long byId = response.getCreated_by();

		String Name = loginRepo.findRoleByUserId(byId)
		        .orElseGet(() -> {
		            if ("SUPERADMIN".equals(response.getRole().toUpperCase())) {
		                return "SUPERADMIN";
		            }
		            throw new RuntimeException("User not found");
		        });

		
		LoginCredentialsWrapper wrappedData=new LoginCredentialsWrapper();
		wrappedData.setId(response.getId());
		wrappedData.setName(response.getName());
		wrappedData.setRole(response.getRole());
		wrappedData.setUser_id(response.getUser_id());
		wrappedData.setEmail(response.getEmail());
		wrappedData.setPhone(response.getPhone());
		wrappedData.setIs_active(response.getIs_active());
		wrappedData.setCreated_at(response.getCreated_at());
		wrappedData.setLast_login_at(response.getLast_login_at());
		
		response.setLast_login_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
		loginRepo.save(response);
		
		MenuPermissionsEntity permissions=menu_permissions.findByUsersId(response.getId());
		List<String> permissionsMenu = new ArrayList<>();

		if (permissions == null) {
			 throw new CustomException("No menu permissions assigned. Please contact " + Name);
		} else {
		    permissionsMenu = extractPermissions(permissions);
		}


		  List<PermissionsEntity> p_permissions = page_permissions.findPermissionsByRoleId(response.getId());
		  
		  Map<String, List<String>> pagesPermissions= extractPagePermissions(p_permissions);
		  
		LoginResponseWrapper loginResponseWrapper=new LoginResponseWrapper();
		loginResponseWrapper.setUser(wrappedData);
		loginResponseWrapper.setMenuPermissions(permissionsMenu);
		loginResponseWrapper.setPagePermissions(pagesPermissions);
		return ResponseEntity.status(HttpStatus.OK).body(loginResponseWrapper);
	}

	private List<String> extractPermissions(MenuPermissionsEntity p) {

	    List<String> permissions = new ArrayList<>();

	    if (p.getDashboard() == 1) permissions.add("DASHBOARD");
	    if (p.getAnalytics() == 1) permissions.add("ANALYTICS");
	    if (p.getDocuments() == 1) permissions.add("DOCUMENTS");
	    if (p.getSettings() == 1) permissions.add("SETTINGS");
	    if (p.getFollow_ups() == 1) permissions.add("FOLLOW_UPS");
	    if (p.getReports() == 1) permissions.add("REPORTS");
	    if (p.getInvoices() == 1) permissions.add("INVOICES");
	    if (p.getSales_clients() == 1) permissions.add("SALES_CLIENTS");
	    if (p.getSales_leads() == 1) permissions.add("SALES_LEADS");
	    if (p.getSales_estimation() == 1) permissions.add("SALES_ESTIMATION");
	    if (p.getProcurement_venders() == 1) permissions.add("PROCUREMENT_VENDERS");
	    if (p.getProcurement_quotations_recived() == 1) permissions.add("PROCUREMENT_QUOTATIONS");
	    if (p.getProcurement_purchase_orders() == 1) permissions.add("PROCUREMENT_PURCHASE_ORDERS");
	    if (p.getProcurement_bills_received() == 1) permissions.add("PROCUREMENT_BILLS");
	    if (p.getOffice_use() == 1) permissions.add("OFFICE_USE");
	    return permissions;
	}

	public ResponseEntity<?> UpdateUser(LoginEntity newData, Long id) throws CustomException {
		
		LoginEntity isUserExist=loginRepo.findById(id).orElseThrow(()-> new CustomException("Invalid User"));

		isUserExist.setName(newData.getName());
		isUserExist.setEmail(newData.getEmail());
		isUserExist.setPhone(newData.getPhone());
		isUserExist.setRole(newData.getRole());
		isUserExist.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
		isUserExist.setUpdated_type("PROFILE_UPDATED");
		
		LoginEntity response=loginRepo.save(isUserExist);
		if (response == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Update Failed");
	    }
		return ResponseEntity.status(HttpStatus.ACCEPTED).body("Profile Details Updated Successfully");
	}

	public ResponseEntity<String> UpdatePassword(Map<String, String> credentials, Long id) throws CustomException {
		
		LoginEntity isUserExist=loginRepo.findById(id).orElseThrow(()-> new CustomException("Invalid User"));
		
		String OldPassword=credentials.get("oldPassword");
		String NewPassword=credentials.get("newPassword");
		if(OldPassword.equals(isUserExist.getPassword())) {
			isUserExist.setPassword(NewPassword); 
			isUserExist.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
			isUserExist.setUpdated_type("PASSWORD_UPDATED");
			LoginEntity response=loginRepo.save(isUserExist);
			if (response == null) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Update Failed");
		    }
			return ResponseEntity.status(HttpStatus.ACCEPTED).body("Password Updated Successfully");
		}	
		else return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Password Not Matched with Old Password");
		
		
	}

//	public UsersResponseWrapper Users(Long userId) throws CustomException {
//
//	    LoginEntity requestingUser = loginRepo.findById(userId)
//	        .orElseThrow(() -> new CustomException("Invalid User"));
//
//	    List<LoginEntity> users;
//	    
//	    // If SuperAdmin (userId = 1), get all users including SuperAdmin
//	    if (userId == 1L) {
//	        users = loginRepo.findAll(); // Get all users in the system
//	    } else {
//	        // For other users, get only users created by them
//	        users = loginRepo.getAllUsers(userId);
//	    }
//
//	    List<UserWrapper> userWrappers = users.stream()
//	        .map(user -> {
//	            UserWrapper wrapper = new UserWrapper();
//	            wrapper.setId(user.getId());
//	            wrapper.setUser_id(user.getUser_id());
//	            wrapper.setEmail(user.getEmail());
//	            wrapper.setName(user.getName());
//	            wrapper.setPhone(user.getPhone());
//	            wrapper.setIs_active(user.getIs_active());
//	            wrapper.setCreated_at(user.getCreated_at());
//	            wrapper.setRole(user.getRole());
//
//	            List<PermissionsEntity> p_permissions = page_permissions.findPermissionsByRoleId(user.getId());
//	            if (p_permissions == null || p_permissions.isEmpty()) {
//	                wrapper.setPagePermissionsCount((long) 0);
//	            } else {
//	                wrapper.setPagePermissionsCount((long) p_permissions.size());
//	            }
//
//	            MenuPermissionsEntity permissions = menu_permissions.findByUsersId(user.getId());
//	            if (permissions == null) {
//	                wrapper.setMenuPermissionsCount((long) 0);
//	            } else {
//	                List<String> menu_list = extractPermissions(permissions);
//	                wrapper.setMenuPermissionsCount((long) menu_list.size());
//	            }
//
//	            return wrapper;
//	        })
//	        .toList();
//
//	    int totalUsers = users.size();
//	    int activeUsers = (int) users.stream().filter(u -> u.getIs_active() == 1).count();
//	    int inactiveUsers = (int) users.stream().filter(u -> u.getIs_active() == 0).count();
//
//	    List<String> roles = users.stream()
//	            .map(LoginEntity::getRole)
//	            .distinct()
//	            .toList();
//
//	    UsersResponseWrapper response = new UsersResponseWrapper();
//	    response.setUserWrapper(userWrappers);
//	    response.setTotalUsers(totalUsers);
//	    response.setActiveUsers(activeUsers);
//	    response.setInactiveUsers(inactiveUsers);
//	    response.setRoles(roles);
//
//	    return response;
//	}

	public UsersResponseWrapper Users(Long userId) throws CustomException {

	     loginRepo.findById(userId).orElseThrow(() -> new CustomException("Invalid User"));

	    List<LoginEntity> users;

	    // If SuperAdmin (userId = 1), get ALL users in the system
	    if (userId == 1L) {
	        users = loginRepo.findAll(); // Get absolutely all users
	    } else {
	        // For other users, get only users created by them
	        users = loginRepo.getAllUsers(userId);
	    }

	    List<UserWrapper> userWrappers = users.stream()
	        .map(user -> {
	            UserWrapper wrapper = new UserWrapper();
	            wrapper.setId(user.getId());
	            wrapper.setUser_id(user.getUser_id());
	            wrapper.setEmail(user.getEmail());
	            wrapper.setName(user.getName());
	            wrapper.setPhone(user.getPhone());
	            wrapper.setIs_active(user.getIs_active());
	            wrapper.setCreated_at(user.getCreated_at());
	            wrapper.setRole(user.getRole());

	            List<PermissionsEntity> p_permissions = page_permissions.findPermissionsByRoleId(user.getId());
	            if (p_permissions == null || p_permissions.isEmpty()) {
	                wrapper.setPagePermissionsCount((long) 0);
	            } else {
	                wrapper.setPagePermissionsCount((long) p_permissions.size());
	            }

	           
	            MenuPermissionsEntity permissions = menu_permissions.findByUsersId(user.getId());
	            if (permissions == null) {
	                wrapper.setMenuPermissionsCount((long) 0);
	            } else {
	                List<String> menu_list = extractPermissions(permissions);
	                wrapper.setMenuPermissionsCount((long) menu_list.size());
	            }

	            return wrapper;
	        })
	        .toList();

	    int totalUsers = users.size();
	    int activeUsers = (int) users.stream().filter(u -> u.getIs_active() == 1).count();
	    int inactiveUsers = (int) users.stream().filter(u -> u.getIs_active() == 0).count();

	    List<String> roles = users.stream()
	            .map(LoginEntity::getRole)
	            .distinct()
	            .toList();

	    UsersResponseWrapper response = new UsersResponseWrapper();
	    response.setUserWrapper(userWrappers);
	    response.setTotalUsers(totalUsers);
	    response.setActiveUsers(activeUsers);
	    response.setInactiveUsers(inactiveUsers);
	    response.setRoles(roles);

	    return response;
	}
	
	

	public List<String> GetMenuPermissions(Long id) throws CustomException {

	    // Validate user
	    loginRepo.findById(id)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    MenuPermissionsEntity menuPermissions =
	            menu_permissions.findByUsersId(id);

	    // If no menu permissions found
	    if (menuPermissions == null) {
	        return List.of("No Menu Permissions");
	    }

	    List<String> permissions = extractPermissions(menuPermissions);
	    
	    

	    // If extracted list is empty or null
	    if (permissions == null || permissions.isEmpty()) {
	        return List.of("No Menu Permissions");
	    }

	    return permissions;
	}

	
	public Object GetPagePermissions(Long id) throws CustomException {

	    // 1. Validate user
	    loginRepo.findById(id)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    // 2. Fetch permissions
	    List<PermissionsEntity> p_permissions =
	            page_permissions.findPermissionsByRoleId(id);

	    // 3. If no permissions → return message
	    if (p_permissions == null || p_permissions.isEmpty()) {
	        return "No Permissions";
	    }
	    System.err.println(extractPagePermissions(p_permissions));
	    // 4. Return permissions map
	    return extractPagePermissions(p_permissions);
	}

	
	
	private Map<String, List<String>> extractPagePermissions(List<PermissionsEntity> permissionsEntities) {

	    Map<String, List<String>> result = new HashMap<>();

	    for (PermissionsEntity p : permissionsEntities) {

	        if (p.getName() == null) {
	            continue;
	        }

	        String permissionName = p.getName();
	        String[] parts = permissionName.split("\\.");

	        String module;
	        String action;

	        if (parts.length >= 3) {
	            // Handle nested permissions: quotations.sales.view -> module="QUOTATIONS.SALES", action="VIEW"
	            // Join all parts except the last as module, last part is action
	            StringBuilder moduleBuilder = new StringBuilder();
	            for (int i = 0; i < parts.length - 1; i++) {
	                if (i > 0) moduleBuilder.append(".");
	                moduleBuilder.append(parts[i].toUpperCase());
	            }
	            module = moduleBuilder.toString();
	            action = parts[parts.length - 1].toUpperCase();
	            
	        } else if (parts.length == 2) {
	            // Simple permission: users.view -> module="USERS", action="VIEW"
	            module = parts[0].toUpperCase();
	            action = parts[1].toUpperCase();
	            
	        } else {
	            System.err.println("Invalid permission format: " + permissionName);
	            continue;
	        }

	        result.computeIfAbsent(module, k -> new ArrayList<>()).add(action);
	    }

	    return result;
	}
	
}
