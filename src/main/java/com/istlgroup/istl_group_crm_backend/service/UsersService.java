package com.istlgroup.istl_group_crm_backend.service;


import java.time.LocalDateTime;
import java.time.ZoneId;

import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.istlgroup.istl_group_crm_backend.entity.UserProfileImageEntity;
import com.istlgroup.istl_group_crm_backend.repo.UserProfileImageRepo;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jakarta.servlet.http.HttpServletResponse;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.PermissionsEntity;
import com.istlgroup.istl_group_crm_backend.entity.UserMenuPermissionEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.repo.PermissionsRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserMenuPermissionRepo;
import com.istlgroup.istl_group_crm_backend.repo.UsersRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UserWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UsersResponseWrapper;
import com.istlgroup.istl_group_crm_backend.entity.UserPagePermissionEntity;
import com.istlgroup.istl_group_crm_backend.repo.UserPagePermissionRepo;

@Service
public class UsersService {

    @Autowired
    private UsersRepo usersRepo;

    @Autowired
    private UserMenuPermissionRepo userMenuRepo;

    @Autowired
	private PermissionsRepo permissionsRepo;


    
    @Autowired
    private RolesRepo rolesRepo;  // ← ADD THIS
    @Autowired
    private UserPagePermissionRepo userPagePermissionRepo;

    @Autowired
    private UserProfileImageRepo userProfileImageRepo;

    public ResponseEntity<?> UpdateUser(LoginEntity newData, Long id) throws CustomException {
        UsersEntity isUserExist = usersRepo.findById(id).orElseThrow(() -> new CustomException("Invalid User"));

        // Duplicate email → 409 CONFLICT so the frontend can show a WARNING
        // toast instead of a generic error (previously the DB unique
        // constraint blew up into a 500).
        if (newData.getEmail() != null
                && usersRepo.findByEmailExcludingId(newData.getEmail().trim(), id).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Email already exists. Please use a different email.");
        }

        // Server-side phone guard: exactly 10 digits
        String phone = newData.getPhone() == null ? "" : newData.getPhone().replaceAll("\\D", "");
        if (!phone.matches("\\d{10}")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Phone number must be exactly 10 digits.");
        }

        // Duplicate phone → 409 CONFLICT (warning on frontend), same as email
        if (usersRepo.findByPhoneExcludingId(phone, id).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Mobile number already exists. Please use a different mobile number.");
        }

        isUserExist.setName(newData.getName());
        isUserExist.setEmail(newData.getEmail());
        isUserExist.setPhone(phone);
        isUserExist.setRole(RoleNormalizer.normalize(newData.getRole())); // always UPPER_SNAKE_CASE
        isUserExist.setIs_active(newData.getIs_active());
        // FIX #1: persist manager, team, designation on update
        isUserExist.setManagerId(newData.getManagerId());
        isUserExist.setTeam(newData.getTeam());
        isUserExist.setDesignation(newData.getDesignation());
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

        // Delete user_menu_permissions (menu access)
        userMenuRepo.deleteAllByUserId(id);

        // Delete user_page_permissions (page/feature access)
        userPagePermissionRepo.deleteAllByUserId(id);

        // Delete user
        usersRepo.deleteById(id);
        return ResponseEntity.ok("User deleted successfully");
    }

    public ResponseEntity<String> DeactivateUser(Long id) throws CustomException {
        UsersEntity user = usersRepo.findById(id)
                .orElseThrow(() -> new CustomException("Invalid User"));
        user.setIs_active(0L);
        user.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        user.setUpdated_type("DEACTIVATED");
        usersRepo.save(user);
        return ResponseEntity.ok("User deactivated successfully");
    }
    @Transactional
    public ResponseEntity<?> UpdateMenuPermissions(Long id, Map<String, Integer> permissions) throws CustomException {

        usersRepo.findById(id).orElseThrow(() -> new CustomException("Invalid User"));

        List<Object[]> allMenuItems = userMenuRepo.findAllMenuItems();

        for (Object[] menuItem : allMenuItems) {

            Long menuId = ((Number) menuItem[0]).longValue();

            String menuName = ((String) menuItem[1])
                    .toLowerCase()
                    .replace(" ", "_");

            int hasPermission = permissions.getOrDefault(menuName, 0);

            // 🔥 CHECK IF EXISTS
            UserMenuPermissionEntity existing =
                    userMenuRepo.findByUserIdAndMenuId(id, menuId);

            if (existing != null) {
                // ✅ UPDATE
                existing.setHasPermission(hasPermission == 1);
                userMenuRepo.save(existing);
                System.err.println(existing);
            } else {
                // ✅ INSERT
                UserMenuPermissionEntity entity = new UserMenuPermissionEntity();
                entity.setUserId(id);
                entity.setMenuId(menuId);
                entity.setHasPermission(hasPermission == 1);

                userMenuRepo.save(entity);
            }
        }

        return ResponseEntity.ok("Menu permissions updated successfully");
    }
    public ResponseEntity<?> UpdatePagePermissions(Long id, Map<String, Object> requestData) throws CustomException {

        UsersEntity user = usersRepo.findById(id)
                .orElseThrow(() -> new CustomException("Invalid User"));

        @SuppressWarnings("unchecked")
        List<Integer> permissionIds = (List<Integer>) requestData.get("permissionIds");

        if (permissionIds == null) {
            return ResponseEntity.badRequest().body("Permission IDs are required");
        }

        Set<Integer> selectedIds = new HashSet<>(permissionIds);

        // Update each row: has_permission = whether ID is in the sent list
        // Mirrors UpdateMenuPermissions pattern exactly
        List<PermissionsEntity> allPermissions = permissionsRepo.findAll();

        for (PermissionsEntity perm : allPermissions) {
            boolean hasPerm = selectedIds.contains(perm.getId());

            UserPagePermissionEntity existing =
                    userPagePermissionRepo.findByUserIdAndPermissionId(id, perm.getId());

            if (existing != null) {
                existing.setHasPermission(hasPerm);
                userPagePermissionRepo.save(existing);
            } else {
                // Row missing (e.g. permission added after user creation) — create it
                UserPagePermissionEntity entry = new UserPagePermissionEntity();
                entry.setUserId(id);
                entry.setPermissionId(perm.getId());
                entry.setHasPermission(hasPerm);
                userPagePermissionRepo.save(entry);
            }
        }

        return ResponseEntity.ok("Page permissions updated successfully for user: " + user.getName());
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

	    // ---------------- DUPLICATE EMAIL → 409 (warning on frontend) ----------------
	    // Previously the DB unique constraint threw a raw SQL exception
	    // ("Duplicate entry ... for key 'users.email'") that leaked to the UI.
	    if (usersRepo.findByEmailExcludingId(user.getEmail().trim(), -1L).isPresent()) {
	        return ResponseEntity.status(HttpStatus.CONFLICT)
	                .body("Email already exists. Please use a different email.");
	    }

	    // ---------------- PHONE: exactly 10 digits ----------------
	    String cleanPhone = user.getPhone().replaceAll("\\D", "");
	    if (!cleanPhone.matches("\\d{10}")) {
	        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                .body("Phone number must be exactly 10 digits.");
	    }
	    user.setPhone(cleanPhone);

	    // ---------------- DUPLICATE PHONE → 409 (warning on frontend) ----------------
	    if (usersRepo.findByPhoneExcludingId(cleanPhone, -1L).isPresent()) {
	        return ResponseEntity.status(HttpStatus.CONFLICT)
	                .body("Mobile number already exists. Please use a different mobile number.");
	    }

	    // ---------------- PASSWORD ----------------
	    user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));

	    // ---------------- NORMALISE ROLE ----------------
	    user.setRole(RoleNormalizer.normalizeRequired(user.getRole())); // always UPPER_SNAKE_CASE

	    // ---------------- SAVE USER ----------------
	    UsersEntity newUser = usersRepo.save(user);
	    if (newUser == null) {
	        throw new CustomException("Insertion Failed");
	    }

	    // ---------------- ROLE PERMISSIONS ----------------
	    long roleId = rolesRepo.findRoleIdByName(user.getRole());
	    List<PermissionsEntity> rolePermissions =
	    		permissionsRepo.findPermissionsByRoleId(roleId);
	    
	    
	 // ---------------- ASSIGN PAGE PERMISSIONS (dynamic — mirrors menu permissions) ----------------
	    // Get creator's enabled permission IDs
	    Set<Integer> creatorPermIds = new HashSet<>(
	            userPagePermissionRepo.findEnabledPermissionIdsByUserId(user.getCreated_by()));

	    // Role's permission IDs
	    Set<Integer> rolePermIds = rolePermissions.stream()
	            .map(PermissionsEntity::getId)
	            .collect(Collectors.toSet());

	    // Insert a row for every permission: has_permission = role has it AND creator has it
	    List<PermissionsEntity> allPermissions = permissionsRepo.findAll();
	    List<UserPagePermissionEntity> pageEntries = new ArrayList<>();
	    for (PermissionsEntity perm : allPermissions) {
	        boolean hasPerm = rolePermIds.contains(perm.getId())
	                       && creatorPermIds.contains(perm.getId());
	        UserPagePermissionEntity entry = new UserPagePermissionEntity();
	        entry.setUserId(newUser.getId());
	        entry.setPermissionId(perm.getId());
	        entry.setHasPermission(hasPerm);
	        pageEntries.add(entry);
	    }
	    userPagePermissionRepo.saveAll(pageEntries);

	 // ---------------- MENU PERMISSIONS (user_menu_permissions table) ----------------
	
	    Set<String> roleMenuSet = new HashSet<>(usersRepo.getPermittedMenuNames(roleId));

	    // Creator's permitted menu names (returned as UPPERCASE → convert to lowercase)
	    Set<String> creatorMenuSet = userMenuRepo.findMenuNamesByUserId(user.getCreated_by())
	            .stream()
	            .map(String::toLowerCase)
	            .collect(Collectors.toSet());

	    List<Object[]> allMenuItems = userMenuRepo.findAllMenuItems();
	    List<UserMenuPermissionEntity> menuEntities = new ArrayList<>();

	    for (Object[] menuItem : allMenuItems) {
	        Long menuId = ((Number) menuItem[0]).longValue();
	        String menuName = (String) menuItem[1]; // lowercase from menu_items table

	        // Assign only if BOTH role has it AND creator has it
	        boolean hasPerm = roleMenuSet.contains(menuName) && creatorMenuSet.contains(menuName);

	        UserMenuPermissionEntity entity = new UserMenuPermissionEntity();
	        entity.setUserId(newUser.getId());
	        entity.setMenuId(menuId);
	        entity.setHasPermission(hasPerm);
	        menuEntities.add(entity);
	    }

	    userMenuRepo.saveAll(menuEntities);

	    // ---------------- DONE ----------------
	    return ResponseEntity.ok("New User Added Successfully");
	}

	
	
	
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

	            // FIX #1: populate hierarchy & designation fields
	            wrapper.setManagerId(user.getManagerId());
	            wrapper.setTeam(user.getTeam());
	            wrapper.setDesignation(user.getDesignation());
	            if (user.getManagerId() != null) {
	                usersRepo.findById(user.getManagerId())
	                         .ifPresent(mgr -> wrapper.setManagerName(mgr.getName()));
	            }

	            // Page permissions count
	            long totalPermissionCount = userPagePermissionRepo.countPagePermissions(user.getId());
	            wrapper.setPagePermissionsCount(totalPermissionCount);

	            // Menu permissions count
	         // Menu permissions count — using new user_menu_permissions table
	            long menuCount = userMenuRepo.countMenuPermissions(user.getId());
	            wrapper.setMenuPermissionsCount(menuCount);

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

	    // Get all roles for the filter dropdown from the roles table — NOT
	    // findDistinctRoles() (distinct roles on users), which drops newly
	    // created roles that have no users assigned yet and caused the role
	    // filter to lose new roles whenever a search/filter was applied.
	    List<String> allRoles = rolesRepo.getAllRoles();

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


    // Profile Image

    public ResponseEntity<?> UploadAvatar(Long id, MultipartFile file) throws CustomException {

        if (file == null || file.isEmpty())

            throw new CustomException("No file provided");



        String contentType = file.getContentType();

        if (contentType == null ||

                !Arrays.asList("image/jpeg","image/png","image/gif","image/webp").contains(contentType))

            throw new CustomException("Only JPEG, PNG, GIF and WEBP images are allowed.");



        if (file.getSize() > 10L * 1024 * 1024)

            throw new CustomException("File size exceeds the 10 MB limit.");



        UsersEntity user = usersRepo.findById(id)

                .orElseThrow(() -> new CustomException("User not found: " + id));

        try {

            UserProfileImageEntity img = userProfileImageRepo.findByUserId(id)

                    .orElse(new UserProfileImageEntity());

            img.setUserId(id);

            img.setMimeType(contentType);

            img.setFileSize(file.getSize());

            img.setImageData(file.getBytes());

            userProfileImageRepo.save(img);



            user.setAvatar_url("db");

            usersRepo.save(user);



            Map<String, Object> resp = new LinkedHashMap<>();

            resp.put("success", true);

            resp.put("message", "Profile photo uploaded successfully.");

            return ResponseEntity.ok(resp);

        } catch (Exception e) {

            throw new CustomException("Failed to save image: " + e.getMessage());

        }

    }



    public void StreamAvatar(Long id, HttpServletResponse response) throws CustomException {

        UserProfileImageEntity img = userProfileImageRepo.findByUserId(id)

                .orElseThrow(() -> new CustomException("No profile photo found for user: " + id));

        try {

            response.setContentType(img.getMimeType());

            response.setContentLength(img.getImageData().length);

            response.setHeader("Cache-Control", "private, max-age=3600");

            response.getOutputStream().write(img.getImageData());

            response.getOutputStream().flush();

        } catch (Exception e) {

            throw new CustomException("Failed to stream image: " + e.getMessage());

        }

    }



    public ResponseEntity<?> RemoveAvatar(Long id) throws CustomException {

        UsersEntity user = usersRepo.findById(id)

                .orElseThrow(() -> new CustomException("User not found: " + id));

        userProfileImageRepo.deleteByUserId(id);

        user.setAvatar_url(null);

        usersRepo.save(user);

        Map<String, Object> resp = new LinkedHashMap<>();

        resp.put("success", true);

        resp.put("message", "Profile photo removed.");

        return ResponseEntity.ok(resp);

    }

}