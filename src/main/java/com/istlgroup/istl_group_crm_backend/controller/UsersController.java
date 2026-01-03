package com.istlgroup.istl_group_crm_backend.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.service.UsersService;

@RestController
@RequestMapping("/users")
@CrossOrigin(origins = "${cros.allowed-origins}")
public class UsersController {

	@Autowired
	private UsersService usersService;

	@PostMapping("/updateUser/{id}")
	public ResponseEntity<?> UpdateUser(@RequestBody LoginEntity newData, @PathVariable Long id) throws CustomException {
		return usersService.UpdateUser(newData, id);
	}

	@DeleteMapping("/deleteUser/{id}")
	public ResponseEntity<String> DeleteUser(@PathVariable Long id) throws CustomException {
		return usersService.DeleteUser(id);
	}

	// Update Menu Permissions
	@PutMapping("/updateMenuPermissions/{id}")
	public ResponseEntity<?> UpdateMenuPermissions(@PathVariable Long id, @RequestBody Map<String, Integer> permissions) throws CustomException {
		return usersService.UpdateMenuPermissions(id, permissions);
	}

	// Update Page Permissions
	@PutMapping("/updatePagePermissions/{id}")
	public ResponseEntity<?> UpdatePagePermissions(@PathVariable Long id, @RequestBody Map<String, Object> requestData) throws CustomException {
		return usersService.UpdatePagePermissions(id, requestData);
	}

	//User Id Availability Check
	@GetMapping("/isUserIdExist/{userid}")
	public boolean IsUserIdExist(@PathVariable String userid) {
		return usersService.IsUserIdExist(userid);
	}

	@PostMapping("/addNewUser")
	public ResponseEntity<String> AddNewUser(@RequestBody UsersEntity user) throws CustomException{
		return usersService.AddNewUser(user);
	}

	// NEW: Search Users API
	@GetMapping("/search/{userId}")
	public ResponseEntity<?> SearchUsers(
			@PathVariable Long userId,
			@RequestParam(defaultValue = "") String searchTerm,
			@RequestParam(defaultValue = "all") String role,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "5") int size
	) throws CustomException {
		return ResponseEntity.ok(usersService.SearchUsers(userId, searchTerm, role, page, size));
	}
}