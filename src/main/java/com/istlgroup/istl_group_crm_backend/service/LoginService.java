package com.istlgroup.istl_group_crm_backend.service;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.MenuPermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.PagePermissionsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.repo.MenuPermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.PagePermissionsRepo;

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
	
//	@Autowired
//	private PermissionsRepo page_permissions;

	@Autowired
	private PagePermissionsRepo pagePermissions;
	
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


//		  List<PermissionsEntity> p_permissions = page_permissions.findPermissionsByRoleId(response.getId());
//		  System.err.println(pagePermissions.findByUserId(response.getId()));
		  Optional<PagePermissionsEntity> res=pagePermissions.findByUserId(response.getId());
		  Map<String, List<String>> PagePermissions =extractPagePermissionsData(res);
//		  System.err.println(result);
//		  Map<String, List<String>> pagesPermissions= extractPagePermissions(p_permissions);
		  
		LoginResponseWrapper loginResponseWrapper=new LoginResponseWrapper();
		loginResponseWrapper.setUser(wrappedData);
		loginResponseWrapper.setMenuPermissions(permissionsMenu);
		loginResponseWrapper.setPagePermissions(PagePermissions);
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

//	            List<PermissionsEntity> p_permissions = page_permissions.findPermissionsByRoleId(user.getId());
	            Optional<PagePermissionsEntity> p_permissions =
	                    pagePermissions.findByUserId(user.getId());

	            long totalPermissionCount =countEnabledPagePermissions(p_permissions);

	            wrapper.setPagePermissionsCount(totalPermissionCount);

//	            if (p_permissions == null || p_permissions.isEmpty()) {
//	                wrapper.setPagePermissionsCount((long) 0);
//	            } else {
//	                wrapper.setPagePermissionsCount((long) p_permissions.size());
//	            }

	           
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
	
	
	public long countEnabledPagePermissions(Optional<PagePermissionsEntity> res) {

	    if (res.isEmpty()) {
	        return 0L;
	    }

	    PagePermissionsEntity entity = res.get();
	    long count = 0;

	    for (Field field : PagePermissionsEntity.class.getDeclaredFields()) {
	        field.setAccessible(true);

	        try {
	            Object value = field.get(entity);

	            // Skip non-permission fields
	            if (field.getName().equals("id") ||
	                field.getName().equals("user_id") ||
	                field.getName().equals("created_at") ||
	                field.getName().equals("updated_at")) {
	                continue;
	            }

	            // Count only enabled permissions
	            if (value instanceof Integer && ((Integer) value) == 1) {
	                count++;
	            }

	        } catch (IllegalAccessException e) {
	            throw new RuntimeException("Failed to count page permissions", e);
	        }
	    }

	    return count;
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

	
//	public Object GetPagePermissions(Long id) throws CustomException {
//
//	    // 1. Validate user
//	    loginRepo.findById(id)
//	            .orElseThrow(() -> new CustomException("Invalid User"));
//
//	    // 2. Fetch permissions
//	    List<PermissionsEntity> p_permissions =
//	            page_permissions.findPermissionsByRoleId(id);
//
//	    // 3. If no permissions → return message
//	    if (p_permissions == null || p_permissions.isEmpty()) {
//	        return "No Permissions";
//	    }
//	  
//	    // 4. Return permissions map
//	    return extractPagePermissions(p_permissions);
//	}

	public Object GetPagePermissions(Long id) throws CustomException {

	    // 1. Validate user
	    loginRepo.findById(id)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    // 2. Fetch page permissions (single row per user)
	    Optional<PagePermissionsEntity> pPermissions =pagePermissions.findByUserId(id);

	    // 3. If no permissions row
	    if (pPermissions.isEmpty()) {
	        return "No Permissions";
	    }

	    // 4. Convert entity → UI format
	    Map<String, List<String>> permissionsMap = extractPagePermissions(pPermissions.get());
	    System.err.println(permissionsMap);
	    // 5. If user has no enabled permissions
	    if (permissionsMap.isEmpty()) {
	        return "No Permissions";
	    }

	    return permissionsMap;
	}

	private Map<String, List<String>> extractPagePermissions(PagePermissionsEntity entity) {

	    Map<String, List<String>> result = new LinkedHashMap<>();

	    for (Field field : PagePermissionsEntity.class.getDeclaredFields()) {
	        field.setAccessible(true);

	        try {
	            Object value = field.get(entity);

	            // Skip non-enabled permissions
	            if (!(value instanceof Integer) || ((Integer) value) != 1) {
	                continue;
	            }

	            String fieldName = field.getName();

	            // Skip non-permission fields
	            if (fieldName.equals("id") ||
	                fieldName.equals("user_id") ||
	                fieldName.equals("created_at") ||
	                fieldName.equals("updated_at")) {
	                continue;
	            }

	            // users_view → USERS : VIEW
	            // quotations_sales_approve → QUOTATIONS.SALES : APPROVE
	            String[] parts = fieldName.split("_");

	            String action = parts[parts.length - 1].toUpperCase();
	            String module;

	            if (parts.length > 2) {
	                module = String.join(".",
	                        Arrays.stream(parts, 0, parts.length - 1)
	                              .map(String::toUpperCase)
	                              .toArray(String[]::new));
	            } else {
	                module = parts[0].toUpperCase();
	            }

	            result.computeIfAbsent(module, k -> new ArrayList<>()).add(action);

	        } catch (IllegalAccessException e) {
	            throw new RuntimeException("Failed to extract page permissions", e);
	        }
	    }

	    return result;
	}

	
//	private Map<String, List<String>> extractPagePermissions(List<PermissionsEntity> permissionsEntities) {
//
//	    Map<String, List<String>> result = new HashMap<>();
//
//	    for (PermissionsEntity p : permissionsEntities) {
//
//	        if (p.getName() == null) {
//	            continue;
//	        }
//
//	        String permissionName = p.getName();
//	        String[] parts = permissionName.split("\\.");
//
//	        String module;
//	        String action;
//
//	        if (parts.length >= 3) {
//	            // Handle nested permissions: quotations.sales.view -> module="QUOTATIONS.SALES", action="VIEW"
//	            // Join all parts except the last as module, last part is action
//	            StringBuilder moduleBuilder = new StringBuilder();
//	            for (int i = 0; i < parts.length - 1; i++) {
//	                if (i > 0) moduleBuilder.append(".");
//	                moduleBuilder.append(parts[i].toUpperCase());
//	            }
//	            module = moduleBuilder.toString();
//	            action = parts[parts.length - 1].toUpperCase();
//	            
//	        } else if (parts.length == 2) {
//	            // Simple permission: users.view -> module="USERS", action="VIEW"
//	            module = parts[0].toUpperCase();
//	            action = parts[1].toUpperCase();
//	            
//	        } else {
//	            
//	            continue;
//	        }
//
//	        result.computeIfAbsent(module, k -> new ArrayList<>()).add(action);
//	    }
//
//	    return result;
//	}
//	
	
	
	public Map<String, List<String>> extractPagePermissionsData(
	        Optional<PagePermissionsEntity> res) {

	    if (res.isEmpty()) {
	        return Collections.emptyMap();
	    }

	    PagePermissionsEntity entity = res.get();
	    Map<String, List<String>> result = new LinkedHashMap<>();

	    for (Field field : PagePermissionsEntity.class.getDeclaredFields()) {
	        field.setAccessible(true);

	        try {
	            Object value = field.get(entity);

	            // Skip non-integer or non-enabled permissions
	            if (!(value instanceof Integer) || ((Integer) value) != 1) {
	                continue;
	            }

	            String fieldName = field.getName();

	            // Skip non-permission fields
	            if (fieldName.equals("id") ||
	                fieldName.equals("user_id") ||
	                fieldName.equals("created_at") ||
	                fieldName.equals("updated_at")) {
	                continue;
	            }

	            // Split column name
	            String[] parts = fieldName.split("_");

	            String action = parts[parts.length - 1].toUpperCase();
	            String module;

	            // Handle nested modules: quotations_sales_view → QUOTATIONS.SALES
	            if (parts.length > 2) {
	                module = String.join(".",
	                        Arrays.stream(parts, 0, parts.length - 1)
	                              .map(String::toUpperCase)
	                              .toArray(String[]::new));
	            } else {
	                module = parts[0].toUpperCase();
	            }

	            result.computeIfAbsent(module, k -> new ArrayList<>()).add(action);

	        } catch (IllegalAccessException e) {
	            throw new RuntimeException("Failed to extract permissions", e);
	        }
	    }

	    return result;
	}
}
