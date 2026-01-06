package com.istlgroup.istl_group_crm_backend.service;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.MenuPermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.PagePermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.PermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.MenuPermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.PagePermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.PermissionsRepo;
//import com.istlgroup.istl_group_crm_backend.repo.RolePermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UserWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UsersResponseWrapper;

@Service
public class UsersService {

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private MenuPermissionsRepo menuPermissionsRepo;

//    @Autowired
//    private RolePermissionsRepo rolePermissionsRepo;
    
    @Autowired
	private PermissionsRepo permissionsRepo;

    @Autowired
	private PagePermissionsRepo pagePermissions;
    
    @Autowired
    private RolesRepo rolesRepo;  // ← ADD THIS

    public ResponseEntity<?> UpdateUser(LoginEntity newData, Long id) throws CustomException {
        UsersEntity isUserExist = usersRepo.findById(id).orElseThrow(() -> new CustomException("Invalid User"));

        isUserExist.setName(newData.getName());
        isUserExist.setEmail(newData.getEmail());
        isUserExist.setPhone(newData.getPhone());
        isUserExist.setRole(newData.getRole());
        isUserExist.setIs_active(newData.getIs_active());
        isUserExist.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        isUserExist.setUpdated_type("PROFILE_UPDATED");

        UsersEntity response = usersRepo.save(isUserExist);
        if (response == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Update Failed");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("Profile Details Updated Successfully");
    }

    @Transactional
    public ResponseEntity<String> DeleteUser(Long id) throws CustomException {
        usersRepo.findById(id).orElseThrow(() -> new CustomException("Invalid User"));

        // Delete related menu permissions first
        MenuPermissionsEntity menuPerms = menuPermissionsRepo.findByUsersId(id);
        if (menuPerms != null) {
            menuPermissionsRepo.delete(menuPerms);
        }

        // Note: We DON'T delete role_permissions because they belong to the role, not the user
        // Multiple users can have the same role

        // Delete user
        usersRepo.deleteById(id);
        
        return ResponseEntity.ok("User deleted successfully");
    }

    public ResponseEntity<?> UpdateMenuPermissions(Long id, Map<String, Integer> permissions) throws CustomException {
        usersRepo.findById(id).orElseThrow(() -> new CustomException("Invalid User"));

        MenuPermissionsEntity menuPerms = menuPermissionsRepo.findByUsersId(id);
        if (menuPerms == null) {
            menuPerms = new MenuPermissionsEntity();
            menuPerms.setUsersId(id);
        }

        // Set all 15 menu permissions
        menuPerms.setDashboard(permissions.getOrDefault("dashboard", 0));
        menuPerms.setAnalytics(permissions.getOrDefault("analytics", 0));
        menuPerms.setDocuments(permissions.getOrDefault("documents", 0));
        menuPerms.setSettings(permissions.getOrDefault("settings", 0));
        menuPerms.setFollow_ups(permissions.getOrDefault("follow_ups", 0));
        menuPerms.setReports(permissions.getOrDefault("reports", 0));
        menuPerms.setInvoices(permissions.getOrDefault("invoices", 0));
        menuPerms.setSales_clients(permissions.getOrDefault("sales_clients", 0));
        menuPerms.setSales_leads(permissions.getOrDefault("sales_leads", 0));
        menuPerms.setSales_estimation(permissions.getOrDefault("sales_estimation", 0));
        menuPerms.setProcurement_venders(permissions.getOrDefault("procurement_venders", 0));
        menuPerms.setProcurement_quotations_recived(permissions.getOrDefault("procurement_quotations_recived", 0));
        menuPerms.setProcurement_purchase_orders(permissions.getOrDefault("procurement_purchase_orders", 0));
        menuPerms.setProcurement_bills_received(permissions.getOrDefault("procurement_bills_received", 0));
        menuPerms.setOffice_use(permissions.getOrDefault("office_use", 0));

        menuPermissionsRepo.save(menuPerms);

        return ResponseEntity.ok("Menu permissions updated successfully");
    }


    @Transactional
    public ResponseEntity<?> UpdatePagePermissions(Long id, Map<String, Object> requestData) throws CustomException {
        // Get the user
        UsersEntity user = usersRepo.findById(id)
                .orElseThrow(() -> new CustomException("Invalid User"));

        @SuppressWarnings("unchecked")
        List<Integer> permissionIds = (List<Integer>) requestData.get("permissionIds");

        if (permissionIds == null) {
            return ResponseEntity.badRequest().body("Permission IDs are required");
        }

        // Find or create PagePermissionsEntity for this user
        PagePermissionsEntity userPermissions = pagePermissions.findByUserId(id)
                .orElseGet(() -> {
                    PagePermissionsEntity newPermissions = new PagePermissionsEntity();
                    newPermissions.setUser_id(id);
                    newPermissions.setCreated_at(LocalDateTime.now());
                    return newPermissions;
                });
        
        // Reset all permissions to 0
        resetAllPermissions(userPermissions);
        
        // Set selected permissions to 1 based on permission IDs
        for (Integer permissionId : permissionIds) {
            setPermissionById(userPermissions, permissionId);
        }
        
        // Update timestamp
        userPermissions.setUpdated_at(LocalDateTime.now());
        
        // Save to database
        pagePermissions.save(userPermissions);

        return ResponseEntity.ok("Page permissions updated successfully for user: " + user.getName());
    }

    // Helper method to reset all permissions to 0
    private void resetAllPermissions(PagePermissionsEntity permissions) {
        // USERS
        permissions.setUsers_view(0);
        permissions.setUsers_create(0);
        permissions.setUsers_edit(0);
        permissions.setUsers_delete(0);

        // ROLES
        permissions.setRoles_manage(0);

        // CUSTOMERS
        permissions.setCustomers_view(0);
        permissions.setCustomers_create(0);
        permissions.setCustomers_edit(0);
        permissions.setCustomers_delete(0);

        // VENDORS
        permissions.setVendors_view(0);
        permissions.setVendors_create(0);
        permissions.setVendors_edit(0);
        permissions.setVendors_delete(0);

        // LEADS
        permissions.setLeads_view(0);
        permissions.setLeads_create(0);
        permissions.setLeads_edit(0);
        permissions.setLeads_delete(0);
        permissions.setLeads_assign(0);

        // PROPOSALS
        permissions.setProposals_view(0);
        permissions.setProposals_create(0);
        permissions.setProposals_edit(0);
        permissions.setProposals_delete(0);
        permissions.setProposals_approve(0);

        // QUOTATIONS SALES
        permissions.setQuotations_sales_view(0);
        permissions.setQuotations_sales_create(0);
        permissions.setQuotations_sales_edit(0);
        permissions.setQuotations_sales_delete(0);
        permissions.setQuotations_sales_approve(0);

        // SALES ORDERS
        permissions.setSales_orders_view(0);
        permissions.setSales_orders_create(0);
        permissions.setSales_orders_edit(0);
        permissions.setSales_orders_delete(0);
        permissions.setSales_orders_approve(0);

        // INVOICES
        permissions.setInvoices_view(0);
        permissions.setInvoices_create(0);
        permissions.setInvoices_edit(0);
        permissions.setInvoices_delete(0);
        permissions.setInvoices_send(0);

        // QUOTATIONS PROCUREMENT
        permissions.setQuotations_procurement_view(0);
        permissions.setQuotations_procurement_create(0);
        permissions.setQuotations_procurement_edit(0);
        permissions.setQuotations_procurement_delete(0);
        permissions.setQuotations_procurement_approve(0);

        // PURCHASE ORDERS
        permissions.setPurchase_orders_view(0);
        permissions.setPurchase_orders_create(0);
        permissions.setPurchase_orders_edit(0);
        permissions.setPurchase_orders_delete(0);
        permissions.setPurchase_orders_approve(0);

        // BILLS
        permissions.setBills_view(0);
        permissions.setBills_create(0);
        permissions.setBills_edit(0);
        permissions.setBills_delete(0);
        permissions.setBills_approve(0);

        // PAYMENTS
        permissions.setPayments_view(0);
        permissions.setPayments_record(0);
        permissions.setPayments_approve(0);

        // REPORTS
        permissions.setReports_sales(0);
        permissions.setReports_procurement(0);
        permissions.setReports_financial(0);
        permissions.setReports_analytics(0);

        // FOLLOWUPS
        permissions.setFollowups_view(0);
        permissions.setFollowups_create(0);
        permissions.setFollowups_edit(0);
        permissions.setFollowups_delete(0);

        // SETTINGS
        permissions.setSettings_view(0);
        permissions.setSettings_edit(0);

        // ACTIVITY LOGS
        permissions.setActivity_logs_view(0);

        // ATTACHMENTS
        permissions.setAttachments_upload(0);
        permissions.setAttachments_delete(0);
    }

    // Helper method to set permission based on ID
    private void setPermissionById(PagePermissionsEntity permissions, Integer permissionId) {
        switch (permissionId) {
            // USERS (1-4)
            case 1: permissions.setUsers_view(1); break;
            case 2: permissions.setUsers_create(1); break;
            case 3: permissions.setUsers_edit(1); break;
            case 4: permissions.setUsers_delete(1); break;

            // ROLES (5)
            case 5: permissions.setRoles_manage(1); break;

            // CUSTOMERS (6-9)
            case 6: permissions.setCustomers_view(1); break;
            case 7: permissions.setCustomers_create(1); break;
            case 8: permissions.setCustomers_edit(1); break;
            case 9: permissions.setCustomers_delete(1); break;

            // VENDORS (10-13)
            case 10: permissions.setVendors_view(1); break;
            case 11: permissions.setVendors_create(1); break;
            case 12: permissions.setVendors_edit(1); break;
            case 13: permissions.setVendors_delete(1); break;

            // LEADS (14-18)
            case 14: permissions.setLeads_view(1); break;
            case 15: permissions.setLeads_create(1); break;
            case 16: permissions.setLeads_edit(1); break;
            case 17: permissions.setLeads_delete(1); break;
            case 18: permissions.setLeads_assign(1); break;

            // PROPOSALS (19-23)
            case 19: permissions.setProposals_view(1); break;
            case 20: permissions.setProposals_create(1); break;
            case 21: permissions.setProposals_edit(1); break;
            case 22: permissions.setProposals_delete(1); break;
            case 23: permissions.setProposals_approve(1); break;

            // QUOTATIONS SALES (24-28)
            case 24: permissions.setQuotations_sales_view(1); break;
            case 25: permissions.setQuotations_sales_create(1); break;
            case 26: permissions.setQuotations_sales_edit(1); break;
            case 27: permissions.setQuotations_sales_delete(1); break;
            case 28: permissions.setQuotations_sales_approve(1); break;

            // SALES ORDERS (29-33)
            case 29: permissions.setSales_orders_view(1); break;
            case 30: permissions.setSales_orders_create(1); break;
            case 31: permissions.setSales_orders_edit(1); break;
            case 32: permissions.setSales_orders_delete(1); break;
            case 33: permissions.setSales_orders_approve(1); break;

            // INVOICES (34-38)
            case 34: permissions.setInvoices_view(1); break;
            case 35: permissions.setInvoices_create(1); break;
            case 36: permissions.setInvoices_edit(1); break;
            case 37: permissions.setInvoices_delete(1); break;
            case 38: permissions.setInvoices_send(1); break;

            // QUOTATIONS PROCUREMENT (39-43)
            case 39: permissions.setQuotations_procurement_view(1); break;
            case 40: permissions.setQuotations_procurement_create(1); break;
            case 41: permissions.setQuotations_procurement_edit(1); break;
            case 42: permissions.setQuotations_procurement_delete(1); break;
            case 43: permissions.setQuotations_procurement_approve(1); break;

            // PURCHASE ORDERS (44-48)
            case 44: permissions.setPurchase_orders_view(1); break;
            case 45: permissions.setPurchase_orders_create(1); break;
            case 46: permissions.setPurchase_orders_edit(1); break;
            case 47: permissions.setPurchase_orders_delete(1); break;
            case 48: permissions.setPurchase_orders_approve(1); break;

            // BILLS (49-53)
            case 49: permissions.setBills_view(1); break;
            case 50: permissions.setBills_create(1); break;
            case 51: permissions.setBills_edit(1); break;
            case 52: permissions.setBills_delete(1); break;
            case 53: permissions.setBills_approve(1); break;

            // PAYMENTS (54-56)
            case 54: permissions.setPayments_view(1); break;
            case 55: permissions.setPayments_record(1); break;
            case 56: permissions.setPayments_approve(1); break;

            // REPORTS (57-60)
            case 57: permissions.setReports_sales(1); break;
            case 58: permissions.setReports_procurement(1); break;
            case 59: permissions.setReports_financial(1); break;
            case 60: permissions.setReports_analytics(1); break;

            // FOLLOWUPS (61-64)
            case 61: permissions.setFollowups_view(1); break;
            case 62: permissions.setFollowups_create(1); break;
            case 63: permissions.setFollowups_edit(1); break;
            case 64: permissions.setFollowups_delete(1); break;

            // SETTINGS (65-66)
            case 65: permissions.setSettings_view(1); break;
            case 66: permissions.setSettings_edit(1); break;

            // ACTIVITY LOGS (67)
            case 67: permissions.setActivity_logs_view(1); break;

            // ATTACHMENTS (68-69)
            case 68: permissions.setAttachments_upload(1); break;
            case 69: permissions.setAttachments_delete(1); break;

            default:
                // Log warning for unknown permission ID
                System.out.println("Warning: Unknown permission ID: " + permissionId);
                break;
        }
    }

	public boolean IsUserIdExist(String userid) {
		UsersEntity re=usersRepo.isUserIdExist(userid);
		System.err.println(re);
		if(re==null) {
			return false;
		}
		return true;
	}

	@Transactional
	public ResponseEntity<String> AddNewUser(UsersEntity user) throws CustomException {

	    // ---------------- VALIDATION ----------------
	    List<String> errors = new ArrayList<>();

	    if (user.getCreated_by() == null) errors.add("Created by is required");
	    if (user.getUser_id() == null || user.getUser_id().trim().isEmpty()) errors.add("User ID is required");
	    if (user.getEmail() == null || user.getEmail().trim().isEmpty()) errors.add("Email is required");
	    if (user.getName() == null || user.getName().trim().isEmpty()) errors.add("Name is required");
	    if (user.getPassword() == null || user.getPassword().trim().isEmpty()) errors.add("Password is required");
	    if (user.getPhone() == null || user.getPhone().trim().isEmpty()) errors.add("Phone number is required");
	    if (user.getRole() == null || user.getRole().trim().isEmpty()) errors.add("Role is required");
	    if (user.getIs_active() == null) errors.add("User active status is required");

	    if (!errors.isEmpty()) {
	        throw new CustomException(String.join(", ", errors));
	    }

	    // ---------------- PASSWORD ----------------
	    user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));

	    // ---------------- SAVE USER ----------------
	    UsersEntity newUser = usersRepo.save(user);
	    if (newUser == null) {
	        throw new CustomException("Insertion Failed");
	    }

	    // ---------------- ROLE PERMISSIONS ----------------
	    long roleId = rolesRepo.findRoleIdByName(user.getRole());
	    List<PermissionsEntity> rolePermissions =
	    		permissionsRepo.findPermissionsByRoleId(roleId);

	    // ---------------- CREATOR PAGE PERMISSIONS ----------------
	    PagePermissionsEntity creatorPagePermissions =
	            pagePermissions.findByUserId(user.getCreated_by())
	                    .orElseThrow(() ->
	                            new CustomException("Creator has no page permissions"));

	    // ---------------- ASSIGN PAGE PERMISSIONS ----------------
	    PagePermissionsEntity newUserPagePermissions =
	            buildNewUserPagePermissions(
	                    rolePermissions,
	                    creatorPagePermissions,
	                    newUser.getId()
	            );

	    pagePermissions.save(newUserPagePermissions);

	    // ---------------- MENU PERMISSIONS ----------------
	    MenuPermissionsEntity creatorMenuPermissions =
	            menuPermissionsRepo.findByUsersId(user.getCreated_by());

	    if (creatorMenuPermissions == null) {
	        throw new CustomException("Creator menu permissions not found");
	    }

	    MenuPermissionsEntity newUserMenuPermissions = new MenuPermissionsEntity();
	    newUserMenuPermissions.setUsersId(newUser.getId());

	    newUserMenuPermissions.setDashboard(creatorMenuPermissions.getDashboard());
	    newUserMenuPermissions.setAnalytics(creatorMenuPermissions.getAnalytics());
	    newUserMenuPermissions.setDocuments(creatorMenuPermissions.getDocuments());
	    newUserMenuPermissions.setSettings(creatorMenuPermissions.getSettings());
	    newUserMenuPermissions.setFollow_ups(creatorMenuPermissions.getFollow_ups());
	    newUserMenuPermissions.setReports(creatorMenuPermissions.getReports());
	    newUserMenuPermissions.setInvoices(creatorMenuPermissions.getInvoices());
	    newUserMenuPermissions.setSales_clients(creatorMenuPermissions.getSales_clients());
	    newUserMenuPermissions.setSales_leads(creatorMenuPermissions.getSales_leads());
	    newUserMenuPermissions.setSales_estimation(creatorMenuPermissions.getSales_estimation());
	    newUserMenuPermissions.setProcurement_venders(
	            creatorMenuPermissions.getProcurement_venders());
	    newUserMenuPermissions.setProcurement_quotations_recived(
	            creatorMenuPermissions.getProcurement_quotations_recived());
	    newUserMenuPermissions.setProcurement_purchase_orders(
	            creatorMenuPermissions.getProcurement_purchase_orders());
	    newUserMenuPermissions.setProcurement_bills_received(
	            creatorMenuPermissions.getProcurement_bills_received());

	    menuPermissionsRepo.save(newUserMenuPermissions);

	    // ---------------- DONE ----------------
	    return ResponseEntity.ok("New User Added Successfully");
	}

	
	private PagePermissionsEntity buildNewUserPagePermissions(
	        List<PermissionsEntity> rolePermissions,
	        PagePermissionsEntity creatorPermissions,
	        Long newUserId) {

	    PagePermissionsEntity newPermissions = new PagePermissionsEntity();
	    newPermissions.setUser_id(newUserId);

	    for (PermissionsEntity rolePerm : rolePermissions) {
	        String permName = rolePerm.getName(); // users.view

	        if (hasPagePermission(creatorPermissions, permName)) {
	            try {
	                String fieldName = permName.replace(".", "_");
	                Field field = PagePermissionsEntity.class.getDeclaredField(fieldName);
	                field.setAccessible(true);
	                field.set(newPermissions, 1);
	            } catch (Exception ignored) {
	            }
	        }
	    }
	    return newPermissions;
	}

	
	private boolean hasPagePermission(PagePermissionsEntity entity, String permissionName) {
	    try {
	        String fieldName = permissionName.replace(".", "_"); // users.view → users_view
	        Field field = PagePermissionsEntity.class.getDeclaredField(fieldName);
	        field.setAccessible(true);

	        Object value = field.get(entity);
	        return value instanceof Integer && ((Integer) value) == 1;

	    } catch (NoSuchFieldException | IllegalAccessException e) {
	        return false;
	    }
	}

	
//	private List<String> extractPermissions(MenuPermissionsEntity p) {
//
//	    List<String> permissions = new ArrayList<>();
//
//	    if (p.getDashboard() == 1) permissions.add("DASHBOARD");
//	    if (p.getAnalytics() == 1) permissions.add("ANALYTICS");
//	    if (p.getDocuments() == 1) permissions.add("DOCUMENTS");
//	    if (p.getSettings() == 1) permissions.add("SETTINGS");
//	    if (p.getFollow_ups() == 1) permissions.add("FOLLOW_UPS");
//	    if (p.getReports() == 1) permissions.add("REPORTS");
//	    if (p.getInvoices() == 1) permissions.add("INVOICES");
//	    if (p.getSales_clients() == 1) permissions.add("SALES_CLIENTS");
//	    if (p.getSales_leads() == 1) permissions.add("SALES_LEADS");
//	    if (p.getSales_estimation() == 1) permissions.add("SALES_ESTIMATION");
//	    if (p.getProcurement_venders() == 1) permissions.add("PROCUREMENT_VENDERS");
//	    if (p.getProcurement_quotations_recived() == 1) permissions.add("PROCUREMENT_QUOTATIONS");
//	    if (p.getProcurement_purchase_orders() == 1) permissions.add("PROCUREMENT_PURCHASE_ORDERS");
//	    if (p.getProcurement_bills_received() == 1) permissions.add("PROCUREMENT_BILLS");
//
//	    return permissions;
//	}
	
	
	public UsersResponseWrapper SearchUsers(Long userId, String searchTerm, String role, int page, int size) throws CustomException {
	    
	    // Validate logged-in user
	    UsersEntity loggedInUser = usersRepo.findById(userId)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    int offset = (page - 1) * size;

	    List<UsersEntity> users;
	    long totalUsers;

	    // Clean up search term
	    String cleanSearchTerm = (searchTerm == null || searchTerm.trim().isEmpty()) ? null : searchTerm.trim();
	    
	    // SUPERADMIN - can see ALL users
	    if ("SUPERADMIN".equalsIgnoreCase(loggedInUser.getRole())) {
	        
	        // Determine which query to use based on filters
	        if (cleanSearchTerm == null) {
	            // No search term
	            if (role.equals("all")) {
	                // No filters at all
	                users = usersRepo.findAllWithPagination(size, offset);
	                totalUsers = usersRepo.count();
	            } else {
	                // Only role filter
	                users = usersRepo.findByRole(role, size, offset);
	                totalUsers = usersRepo.countByRole(role);
	            }
	        } else {
	            // Has search term
	            if (role.equals("all")) {
	                // Only search term filter
	                users = usersRepo.searchByNameOrEmailOrUserId(cleanSearchTerm, size, offset);
	                totalUsers = usersRepo.countSearchResults(cleanSearchTerm);
	            } else {
	                // Both search term and role filter
	                users = usersRepo.searchByNameOrEmailOrUserIdAndRole(cleanSearchTerm, role, size, offset);
	                totalUsers = usersRepo.countSearchResultsWithRole(cleanSearchTerm, role);
	            }
	        }
	    } 
	    // NORMAL USER - can only see users they created
	    else {
	        
	        // Determine which query to use based on filters
	        if (cleanSearchTerm == null) {
	            // No search term
	            if (role.equals("all")) {
	                // No filters - just created_by
	                users = usersRepo.findByCreatedBy(userId, size, offset);
	                totalUsers = usersRepo.countByCreatedBy(userId);
	            } else {
	                // created_by + role filter
	                users = usersRepo.findByCreatedByAndRole(userId, role, size, offset);
	                totalUsers = usersRepo.countByCreatedByAndRole(userId, role);
	            }
	        } else {
	            // Has search term
	            if (role.equals("all")) {
	                // created_by + search term
	                users = usersRepo.searchByCreatedBy(userId, cleanSearchTerm, size, offset);
	                totalUsers = usersRepo.countSearchByCreatedBy(userId, cleanSearchTerm);
	            } else {
	                // created_by + search term + role filter
	                users = usersRepo.searchByCreatedByAndRole(userId, cleanSearchTerm, role, size, offset);
	                totalUsers = usersRepo.countSearchByCreatedByAndRole(userId, cleanSearchTerm, role);
	            }
	        }
	    }

	    // Transform users with permission counts
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

	            // Page permissions count
	            Optional<PagePermissionsEntity> p_permissions = 
	                    pagePermissions.findByUserId(user.getId());

	            long totalPermissionCount = countEnabledPagePermissions(p_permissions);
	            wrapper.setPagePermissionsCount(totalPermissionCount);

	            // Menu permissions count
	            MenuPermissionsEntity permissions = 
	                    menuPermissionsRepo.findByUsersId(user.getId());

	            if (permissions == null) {
	                wrapper.setMenuPermissionsCount(0L);
	            } else {
	                int menuCount = countMenuPermissions(permissions);
	                wrapper.setMenuPermissionsCount((long) menuCount);
	            }

	            return wrapper;
	        })
	        .toList();

	    // Count active/inactive from TOTAL results (not just current page)
	    int activeUsers = (int) (cleanSearchTerm == null && role.equals("all") 
	        ? usersRepo.countByIsActive(1L) 
	        : users.stream().filter(u -> u.getIs_active() == 1).count());

	    int inactiveUsers = (int) (cleanSearchTerm == null && role.equals("all")
	        ? usersRepo.countByIsActive(0L)
	        : users.stream().filter(u -> u.getIs_active() == 0).count());

	    // Get all unique roles for filter dropdown
	    List<String> allRoles = usersRepo.findDistinctRoles();

	    // Build response
	    UsersResponseWrapper response = new UsersResponseWrapper();
	    response.setUserWrapper(userWrappers);
	    response.setTotalUsers((int) totalUsers);
	    response.setActiveUsers(activeUsers);
	    response.setInactiveUsers(inactiveUsers);
	    response.setRoles(allRoles);

	    // Pagination metadata
	    response.setCurrentPage(page);
	    response.setPageSize(size);
	    response.setTotalPages((int) Math.ceil((double) totalUsers / size));

	    return response;
	}
	
	
	
	// Helper method to count page permissions
	private long countEnabledPagePermissions(Optional<PagePermissionsEntity> res) {
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

	// Helper method to count menu permissions
	private int countMenuPermissions(MenuPermissionsEntity perms) {
	    int count = 0;
	    if (perms.getDashboard() == 1) count++;
	    if (perms.getAnalytics() == 1) count++;
	    if (perms.getDocuments() == 1) count++;
	    if (perms.getSettings() == 1) count++;
	    if (perms.getFollow_ups() == 1) count++;
	    if (perms.getReports() == 1) count++;
	    if (perms.getInvoices() == 1) count++;
	    if (perms.getSales_clients() == 1) count++;
	    if (perms.getSales_leads() == 1) count++;
	    if (perms.getSales_estimation() == 1) count++;
	    if (perms.getProcurement_venders() == 1) count++;
	    if (perms.getProcurement_quotations_recived() == 1) count++;
	    if (perms.getProcurement_purchase_orders() == 1) count++;
	    if (perms.getProcurement_bills_received() == 1) count++;
	    if (perms.getOffice_use() == 1) count++;
	    return count;
	}
	
}