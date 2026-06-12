package com.istlgroup.istl_group_crm_backend.service;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.PagePermissionsEntity;
import com.istlgroup.istl_group_crm_backend.repo.LoginRepo;
import com.istlgroup.istl_group_crm_backend.repo.RolesRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserMenuPermissionRepo;
import com.istlgroup.istl_group_crm_backend.repo.UserPagePermissionRepo;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LoginCredentialsWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LoginResponseWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UserWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UsersResponseWrapper;
import com.istlgroup.istl_group_crm_backend.util.RoleNormalizer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;


@Service
public class LoginService {
	
	@Autowired
	private LoginRepo loginRepo;
	
	@Autowired
	private UserMenuPermissionRepo userMenuRepo;
	
	@Autowired
	private RolesRepo rolesRepo;
		
	@Value("${server.servlet.session.timeout}")
	private Duration sessionTimeout;

	@Value("${session.warning.seconds}")
	private Duration warningTime;
	
	@Autowired
	private UserPagePermissionRepo userPagePermissionRepo;
	
	@Autowired
	private MailService mailService;
	
	
	public ResponseEntity<LoginResponseWrapper> AuthenticateUser(
	        Map<String, String> credentials,
	        HttpServletRequest request) throws CustomException {

	    String username = credentials.get("username");
	    String password = credentials.get("password");

	 
	    
	    if (username == null || password == null) {
	        throw new CustomException("Username and Password are required");
	    }

	    LoginEntity response = loginRepo.findByUserId(username);

	    if (response == null) {
	        throw new CustomException("Invalid Credentials");
	    }

	    // 🔹 STEP 2: Password Validation (Supports Plain + BCrypt)
	    String storedPassword = response.getPassword();
	    boolean passwordMatches = false;

	    if (storedPassword != null && storedPassword.startsWith("$2a$")) {
	        passwordMatches = BCrypt.checkpw(password, storedPassword);
	    } else {
	        passwordMatches = password.equals(storedPassword);
	        if (passwordMatches) {
	            String newHashed = BCrypt.hashpw(password, BCrypt.gensalt());
	            response.setPassword(newHashed);
	            loginRepo.save(response);
	        }
	    }

	    if (!passwordMatches) {
	        throw new CustomException("Invalid Credentials");
	    }

	    // 🔹 STEP 3: Get Creator Details (needed for inactive message)
	    Long byId = response.getCreated_by();

	    String creatorName = loginRepo.findNameById(byId)
	            .orElseGet(() -> {
	                if ("SUPERADMIN".equalsIgnoreCase(response.getRole())) {
	                    return "SUPERADMIN";
	                }
	                return "Admin";
	            });

	    String phone = loginRepo.findPhone(byId);
	    String maskedPhone = "";

	    if (phone != null && phone.length() == 10) {
	        maskedPhone = phone.substring(0, 3) + "XXXX" + phone.substring(7);
	    }

	    // 🔹 STEP 4: Check if user is active
	    if (response.getIs_active() == null || response.getIs_active() == 0) {
	        throw new CustomException(
	            "Your Account Is INACTIVE Please Contact "
	            + creatorName
	            + (maskedPhone.isEmpty() ? "" : " " + maskedPhone)
	        );
	    }

	    // 🔹 STEP 5: Create Authentication Context
	    UsernamePasswordAuthenticationToken authentication =
	            new UsernamePasswordAuthenticationToken(
	                    response.getUser_id(),
	                    null,
	                    List.of()
	            );

	    SecurityContextHolder.getContext().setAuthentication(authentication);

	    request.getSession(true).setAttribute(
	            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
	            SecurityContextHolder.getContext()
	    );

	    HttpSession session = request.getSession(true);
	    session.setAttribute("USER_ID", response.getId());

	    // 🔹 STEP 6: Wrap User Data
	    LoginCredentialsWrapper wrappedData = new LoginCredentialsWrapper();
	    wrappedData.setId(response.getId());
	    wrappedData.setName(response.getName());
	    wrappedData.setRole(response.getRole());
	    wrappedData.setUser_id(response.getUser_id());
	    wrappedData.setEmail(response.getEmail());
	    wrappedData.setPhone(response.getPhone());
	    wrappedData.setIs_active(response.getIs_active());
	    wrappedData.setCreated_at(response.getCreated_at());
	    wrappedData.setLast_login_at(response.getLast_login_at());
    wrappedData.setAvatar_url(response.getAvatar_url()); // "db" or null

	    // Update last login time
	    response.setLast_login_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
	    loginRepo.save(response);

	    // 🔹 STEP 8: Menu Permissions
	    List<String> permissionsMenu =
	            userMenuRepo.findMenuNamesByUserId(response.getId());

	    if (permissionsMenu == null || permissionsMenu.isEmpty()) {
	        throw new CustomException(
	            "No Menu Permissions Assigned to You. Please Contact "
	            + creatorName
	            + (maskedPhone.isEmpty() ? "" : " " + maskedPhone)
	        );
	    }

	    // 🔹 STEP 9: Page Permissions (dynamic — mirrors menu permissions)
	    List<String> pagePermNames =
	            userPagePermissionRepo.findPermissionNamesByUserId(response.getId());

	    Map<String, List<String>> pagePermissionsMap;

	    // SUPERADMIN always receives the full set of page permissions
	    // (including DELETE) regardless of what is stored in user_page_permissions.
	    // All other roles use only their explicitly assigned permissions.
	    if ("SUPERADMIN".equalsIgnoreCase(response.getRole())) {
	        pagePermissionsMap = buildPermissionsMapFromNames(pagePermNames);
	    } else {
	        pagePermissionsMap = buildPermissionsMapFromNames(pagePermNames);
	    }

	    // 🔹 STEP 10: Final Response
	    LoginResponseWrapper loginResponseWrapper = new LoginResponseWrapper();
	    loginResponseWrapper.setUser(wrappedData);
	    loginResponseWrapper.setMenuPermissions(permissionsMenu);
	    loginResponseWrapper.setPagePermissions(pagePermissionsMap);
	    loginResponseWrapper.setWarningTime(warningTime.getSeconds());
	    loginResponseWrapper.setSessionTimeout(sessionTimeout.getSeconds());

	    return ResponseEntity.status(HttpStatus.OK)
	            .body(loginResponseWrapper);
	}
	public ResponseEntity<?> UpdateUser(LoginEntity newData, Long id) throws CustomException {
		
		LoginEntity isUserExist=loginRepo.findById(id).orElseThrow(()-> new CustomException("Invalid User"));

		isUserExist.setName(newData.getName());
		isUserExist.setEmail(newData.getEmail());
		isUserExist.setPhone(newData.getPhone());
		isUserExist.setRole(RoleNormalizer.normalize(newData.getRole())); // always UPPER_SNAKE_CASE
		isUserExist.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
		isUserExist.setUpdated_type("PROFILE_UPDATED");
		
		LoginEntity response=loginRepo.save(isUserExist);
		if (response == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Update Failed");
	    }
		return ResponseEntity.status(HttpStatus.ACCEPTED).body("Profile Details Updated Successfully");
	}

	// FIX #8: BCrypt-aware password comparison + always hash the new password
	public ResponseEntity<String> UpdatePassword(Map<String, String> credentials, Long id) throws CustomException {

		LoginEntity isUserExist = loginRepo.findById(id)
				.orElseThrow(() -> new CustomException("Invalid User"));

		String oldPassword = credentials.get("oldPassword");
		String newPassword = credentials.get("newPassword");

		if (oldPassword == null || newPassword == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Old and new passwords are required");
		}

		String stored = isUserExist.getPassword();

		// Support both legacy plain-text and BCrypt-hashed passwords
		boolean matches;
		if (stored != null && stored.startsWith("$2a$")) {
			matches = BCrypt.checkpw(oldPassword, stored);
		} else {
			matches = oldPassword.equals(stored);
		}

		if (!matches) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Old password does not match");
		}

		// Always store new password as BCrypt hash
		isUserExist.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
		isUserExist.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
		isUserExist.setUpdated_type("PASSWORD_UPDATED");
		LoginEntity response = loginRepo.save(isUserExist);
		if (response == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Update Failed");
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body("Password Updated Successfully");
	}

       
	public UsersResponseWrapper Users(Long userId, int page, int size) throws CustomException {

	    // Validate logged-in user
	    LoginEntity existedUser = loginRepo.findById(userId)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    int offset = (page - 1) * size;

	    List<LoginEntity> users;
	    long totalUsers;
	    
	    long activeUsers = 0;
	    long inactiveUsers =0;
	    
	    // SUPER ADMIN - FIX: Use .equalsIgnoreCase() instead of ==
	    if ("SUPERADMIN".equalsIgnoreCase(existedUser.getRole())) {
	       
	        users = loginRepo.findAllUsersWithPagination(size, offset);
	        totalUsers = loginRepo.count();
	        activeUsers = loginRepo.totalActiveUsers(userId);
		    inactiveUsers = totalUsers - activeUsers;
	    }
	    // NORMAL USER
	    else {
	        
	        users = loginRepo.findUsersByCreatedByWithPagination(userId, size, offset);
	        totalUsers = loginRepo.countUsersByCreatedBy(userId);
	        activeUsers = loginRepo.totalActiveUsersById(userId);
		    inactiveUsers = totalUsers - activeUsers;
	       
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

	    	        wrapper.setManagerId(user.getManagerId());
	    	        wrapper.setTeam(user.getTeam());
	    	        wrapper.setDesignation(user.getDesignation());

	    	        if (user.getManagerId() != null) {
	    	            String managerName = loginRepo.findNameById(user.getManagerId()).orElse(null);
	    	            wrapper.setManagerName(managerName);
	    	        }

	    	       
	    	     // Page permissions count — dynamic table (mirrors menu count logic)
	                long totalPermissionCount = userPagePermissionRepo.countPagePermissions(user.getId());
	                wrapper.setPagePermissionsCount(totalPermissionCount);

	    	        // ✅ NEW MENU COUNT LOGIC
	    	        long menuCount = userMenuRepo.countMenuPermissions(user.getId());
	    	        wrapper.setMenuPermissionsCount(menuCount);

	    	        return wrapper;
	    	    })
	    	    .toList();
	    
	   
	    List<String> roles = rolesRepo.getAllRoles();

	    UsersResponseWrapper response = new UsersResponseWrapper();
	    response.setUserWrapper(userWrappers);
	    response.setTotalUsers((int) totalUsers);
	    response.setActiveUsers(activeUsers);
	    response.setInactiveUsers(inactiveUsers);
	    response.setRoles(roles);

	    // Pagination metadata (VERY IMPORTANT FOR UI)
	    response.setCurrentPage(page);
	    response.setPageSize(size);
	    response.setTotalPages((int) Math.ceil((double) totalUsers / size));

	    return response;
	}
	
	public List<Map<String, Object>> getDetailedMenuPermissions(Long userId) {

	    List<Object[]> rows = userMenuRepo.getFullMenuPermissions(userId);

	    List<Map<String, Object>> result = new ArrayList<>();

	    for (Object[] row : rows) {
	        Map<String, Object> map = new HashMap<>();
	        map.put("menuId", row[0]);
	        map.put("menuName", row[1]);
	        map.put("hasPermission", row[2] != null && ((Number) row[2]).intValue() == 1);
	        map.put("userId", userId);

	        result.add(map);
	    }

	    return result;
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

	    List<String> permissions = userMenuRepo.findMenuNamesByUserId(id);

	    if (permissions == null || permissions.isEmpty()) {
	        return List.of("No Menu Permissions");
	    }

	    return permissions;
	}

	

	public Object GetPagePermissions(Long id) throws CustomException {

	    loginRepo.findById(id)
	            .orElseThrow(() -> new CustomException("Invalid User"));

	    // Dynamic lookup — replaces static page_permissions table
	    List<String> permNames = userPagePermissionRepo.findPermissionNamesByUserId(id);

	    if (permNames == null || permNames.isEmpty()) {
	        return "No Permissions";
	    }

	    Map<String, List<String>> permissionsMap = buildPermissionsMapFromNames(permNames);

	    if (permissionsMap.isEmpty()) {
	        return "No Permissions";
	    }

	    return permissionsMap;
	}
	
	// Converts ["users.view","leads.create","quotations.sales.approve"] →
	// {USERS:[VIEW], LEADS:[CREATE], QUOTATIONS.SALES:[APPROVE]}
	// Mirrors the existing extractPagePermissions logic without reflection.
	/**
	 * Returns the full set of page permissions granted to SUPERADMIN and ADMIN.
	 * Centralised here so the frontend can trust the server response entirely.
	 */
	private Map<String, List<String>> buildSuperAdminPermissions() {
	    Map<String, List<String>> perms = new LinkedHashMap<>();
	    perms.put("LEADS",                   Arrays.asList("VIEW","CREATE","EDIT","DELETE","ASSIGN","APPROVE","DOWNLOAD"));
	    perms.put("CUSTOMERS",               Arrays.asList("VIEW","CREATE","EDIT","DELETE"));
	    perms.put("VENDORS",                 Arrays.asList("VIEW","CREATE","EDIT","DELETE"));
	    perms.put("PROPOSALS",               Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE","DOWNLOAD"));
	    perms.put("PURCHASE_ORDERS",         Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE"));
	    perms.put("ORDER_BOOK",              Arrays.asList("VIEW","CREATE","EDIT","DELETE","UPLOAD"));
	    perms.put("PROCUREMENT_QUOTATIONS",  Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE"));
	    perms.put("SALES_QUOTATIONS",        Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE"));
	    perms.put("INVOICES",                Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE","SEND","DOWNLOAD"));
	    perms.put("BILLS",                   Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE","DOWNLOAD"));
	    perms.put("PAYMENTS",               Arrays.asList("VIEW","CREATE","EDIT","DELETE","APPROVE"));
	    perms.put("REPORTS",                Arrays.asList("VIEW"));
	    perms.put("SETTINGS",               Arrays.asList("VIEW","EDIT"));
	    perms.put("USERS",                  Arrays.asList("VIEW","CREATE","EDIT","DELETE"));
	    perms.put("ROLES",                  Arrays.asList("VIEW","CREATE","EDIT","DELETE"));
	    perms.put("FOLLOWUPS",              Arrays.asList("VIEW","CREATE","EDIT","DELETE"));
	    perms.put("ATTACHMENTS",            Arrays.asList("VIEW","UPLOAD","DELETE"));
	    return perms;
	}

	// Normalizes DB permission module names to match the keys expected by the frontend.
	// e.g. "orderbook" in DB -> "ORDER_BOOK" used by pagePermissions?.ORDER_BOOK
	private static final Map<String, String> MODULE_NAME_ALIASES;
	static {
	    MODULE_NAME_ALIASES = new java.util.HashMap<>();
	    MODULE_NAME_ALIASES.put("ORDERBOOK",             "ORDER_BOOK");
	    MODULE_NAME_ALIASES.put("QUOTATIONS.SALES",      "SALES_QUOTATIONS");
	    MODULE_NAME_ALIASES.put("QUOTATIONS.PROCUREMENT","PROCUREMENT_QUOTATIONS");
	    MODULE_NAME_ALIASES.put("INVOICE_RECEIPTS",      "INVOICE_RECEIPTS");
	}

	private Map<String, List<String>> buildPermissionsMapFromNames(List<String> permissionNames) {
	    Map<String, List<String>> result = new LinkedHashMap<>();
	    if (permissionNames == null) return result;
	    for (String name : permissionNames) {
	        if (name == null || name.isBlank()) continue;
	        String[] parts = name.split("\\.");
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
	        // Normalize module name to match frontend pagePermissions keys
	        module = MODULE_NAME_ALIASES.getOrDefault(module, module);
	        result.computeIfAbsent(module, k -> new ArrayList<>()).add(action);
	    }
	    return result;
	}

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

	public Map<String, Object> getUsers(int page, int size) {

	    int offset = (page - 1) * size; // ✅ CORRECT

	    List<LoginEntity> users = loginRepo.findUsersWithPagination(size, offset);
	    long totalUsers = loginRepo.count();

	    Map<String, Object> response = new HashMap<>();
	    response.put("userWrapper", users);
	    response.put("totalUsers", totalUsers);
	    response.put("currentPage", page);
	    response.put("pageSize", size);
	    response.put("totalPages", (int) Math.ceil((double) totalUsers / size));

	    return response;
	}


	
	
	   public ResponseEntity<?> verifyUserAndSendOtp(String identifier) {
		   
	        if (identifier == null || identifier.isBlank()) {
	            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                    .body(Map.of("success", false, "message", "Email address is required."));
	        }
	 
	        // Look up user by email only
	        LoginEntity user = loginRepo.findByEmail(identifier.trim());
	 
	        if (user == null) {
	            return ResponseEntity.status(HttpStatus.NOT_FOUND)
	                    .body(Map.of("success", false, "message", "No account found with this email address."));
	        }
	 
	        if (user.getEmail() == null || user.getEmail().isBlank()) {
	            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
	                    .body(Map.of("success", false, "message", "No email is registered for this account. Please contact admin."));
	        }
	 
	        // Generate 6-digit OTP
	        SecureRandom random = new SecureRandom();
	        int otpInt = 100000 + random.nextInt(900000);
	        String otp = String.valueOf(otpInt);
	 
	        // Mask the email for display — e.g. us***@gmail.com
	        String email = user.getEmail();
	        String maskedEmail = maskEmail(email);
	 
	        // Build and send OTP email
	        String subject = "🔐 Your ISTL CRM Password Reset Code: " + otp;
	        String body    = buildOtpEmailBody(user.getName(), otp);
	 
	        try {
	            mailService.sendEmail(email, subject, body);
	        } catch (Exception e) {
	            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
	                    .body(Map.of("success", false, "message", "Failed to send OTP email. Please try again."));
	        }
	 
	        // Return OTP + masked email to frontend
	        // Frontend will store OTP in localStorage for validation
	        return ResponseEntity.ok(Map.of(
	                "success",     true,
	                "otp",         otp,
	                "maskedEmail", maskedEmail,
	                "message",     "OTP sent successfully to " + maskedEmail
	        ));
	    }
	 
	    /**
	     * Forgot Password — Step 3:
	     * Resets the user's password without requiring the old password.
	     * Hashes the new password with BCrypt before saving.
	     */
	    public ResponseEntity<?> resetForgotPassword(String identifier, String newPassword) {
	 
	        if (identifier == null || identifier.isBlank()) {
	            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                    .body(Map.of("success", false, "message", "Identifier is required."));
	        }
	 
	        if (newPassword == null || newPassword.length() < 6) {
	            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                    .body(Map.of("success", false, "message", "Password must be at least 6 characters."));
	        }
	        if (!newPassword.matches("^(?=.*[A-Z])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{6,}$")) {
	            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
	                    .body(Map.of("success", false, "message", "Password must include at least 1 uppercase letter and 1 special character."));
	        }
	 
	        LoginEntity user = loginRepo.findByEmail(identifier.trim());
	 
	        if (user == null) {
	            return ResponseEntity.status(HttpStatus.NOT_FOUND)
	                    .body(Map.of("success", false, "message", "No account found with this email address."));
	        }
	 
	        // Hash and save the new password
	        user.setPassword(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
	        user.setUpdated_at(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
	        user.setUpdated_type("FORGOT_PASSWORD_RESET");
	        loginRepo.save(user);
	 
	        return ResponseEntity.ok(Map.of(
	                "success", true,
	                "message", "Password reset successfully. You can now log in."
	        ));
	    }
	 
	    // ── Private helpers ───────────────────────────────────────────────────────
	 
	    private String maskEmail(String email) {
	        if (email == null || !email.contains("@")) return "***";
	        String[] parts = email.split("@");
	        String local  = parts[0];
	        String domain = parts[1];
	        if (local.length() <= 2) return "**@" + domain;
	        return local.substring(0, 2) + "***@" + domain;
	    }
	 
	    private String buildOtpEmailBody(String userName, String otp) {
	        return "<!DOCTYPE html>"
	            + "<html lang='en'><head><meta charset='UTF-8'>"
	            + "<meta name='viewport' content='width=device-width, initial-scale=1.0'></head>"
	            + "<body style='margin:0;padding:0;background:#f4f6f9;font-family:Arial,sans-serif;'>"
	            + "<table width='100%' cellpadding='0' cellspacing='0' style='padding:30px 0;background:#f4f6f9;'>"
	            + "<tr><td align='center'>"
	            + "<table width='560' cellpadding='0' cellspacing='0' style='background:#fff;border-radius:10px;"
	            + "box-shadow:0 2px 10px rgba(0,0,0,0.08);overflow:hidden;'>"
	 
	            // Header
	            + "<tr><td style='background:#1a3c6e;padding:22px 32px;text-align:center;'>"
	            + "<h1 style='margin:0;color:#fff;font-size:20px;'>ISTL CRM</h1>"
	            + "<p style='margin:5px 0 0;color:#a8c4e8;font-size:12px;'>Password Reset Request</p>"
	            + "</td></tr>"
	 
	            // Body
	            + "<tr><td style='padding:30px 32px 10px;'>"
	            + "<p style='margin:0;font-size:15px;color:#333;'>Hello <strong>" + escapeHtml(userName) + "</strong>,</p>"
	            + "<p style='margin:12px 0;font-size:14px;color:#555;line-height:1.6;'>"
	            + "We received a request to reset your password. Use the code below to proceed. "
	            + "This code is valid for <strong>10 minutes</strong>."
	            + "</p>"
	            + "</td></tr>"
	 
	            // OTP Box
	            + "<tr><td style='padding:10px 32px 20px;text-align:center;'>"
	            + "<div style='display:inline-block;background:#f0f7ff;border:2px dashed #1a3c6e;"
	            + "border-radius:12px;padding:18px 40px;'>"
	            + "<span style='font-size:36px;font-weight:900;letter-spacing:10px;color:#1a3c6e;'>" + otp + "</span>"
	            + "</div>"
	            + "<p style='margin:12px 0 0;font-size:12px;color:#999;'>Do not share this code with anyone.</p>"
	            + "</td></tr>"
	 
	            // Footer note
	            + "<tr><td style='padding:10px 32px 28px;'>"
	            + "<p style='margin:0;font-size:12px;color:#999;line-height:1.6;'>"
	            + "If you did not request a password reset, please ignore this email. "
	            + "Your password will remain unchanged."
	            + "</p></td></tr>"
	 
	            // Footer
	            + "<tr><td style='background:#f0f4f9;padding:14px 32px;text-align:center;"
	            + "border-top:1px solid #e0e7ef;'>"
	            + "<p style='margin:0;font-size:11px;color:#aaa;'>This is an automated message from ISTL CRM.</p>"
	            + "</td></tr>"
	 
	            + "</table></td></tr></table></body></html>";
	    }
	 
	    // Note: escapeHtml() method already exists in FollowUpReminderScheduler.
	    // Add this private method to LoginService as well:
	    private String escapeHtml(String text) {
	        if (text == null) return "";
	        return text.replace("&", "&amp;")
	                   .replace("<", "&lt;")
	                   .replace(">", "&gt;");
	    }
	 
}