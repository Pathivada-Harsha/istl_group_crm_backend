package com.istlgroup.istl_group_crm_backend.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.service.UsersService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/users")
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

    @PutMapping("/deactivateUser/{id}")
    public ResponseEntity<String> DeactivateUser(@PathVariable Long id) throws CustomException {
        return usersService.DeactivateUser(id);
    }

    @PutMapping("/updateMenuPermissions/{id}")
    public ResponseEntity<?> UpdateMenuPermissions(@PathVariable Long id,
            @RequestBody Map<String, Integer> permissions) throws CustomException {
        return usersService.UpdateMenuPermissions(id, permissions);
    }

    @PutMapping("/updatePagePermissions/{id}")
    public ResponseEntity<?> UpdatePagePermissions(@PathVariable Long id,
            @RequestBody Map<String, Object> requestData) throws CustomException {
        return usersService.UpdatePagePermissions(id, requestData);
    }

    @GetMapping("/isUserIdExist/{userid}")
    public boolean IsUserIdExist(@PathVariable String userid) {
        return usersService.IsUserIdExist(userid);
    }

    @PostMapping("/addNewUser")
    public ResponseEntity<String> AddNewUser(@RequestBody UsersEntity user) throws CustomException {
        return usersService.AddNewUser(user);
    }

    @GetMapping("/search/{userId}")
    public ResponseEntity<?> SearchUsers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "") String searchTerm,
            @RequestParam(defaultValue = "all") String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size) throws CustomException {
        return ResponseEntity.ok(usersService.SearchUsers(userId, searchTerm, role, page, size));
    }

    // ── Profile Image Endpoints ──────────────────────────────────────────────

    /**
     * POST /users/uploadAvatar/{id}
     * Accepts multipart image, validates type + size (max 10 MB),
     * stores raw bytes in user_profile_images table,
     * sets users.avatar_url = "db".
     * Response: { "success": true, "avatarUrl": "/api/users/avatar/{id}" }
     */
    @PostMapping("/uploadAvatar/{id}")
    public ResponseEntity<?> UploadAvatar(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws CustomException {
        return usersService.UploadAvatar(id, file);
    }

    /**
     * GET /users/avatar/{id}
     * Streams the raw image bytes with the correct Content-Type header.
     * Browser caches this like any normal image URL.
     * Returns 404 if the user has no photo.
     */
    @GetMapping("/avatar/{id}")
    public void GetAvatar(@PathVariable Long id, HttpServletResponse response) throws CustomException {
        usersService.StreamAvatar(id, response);
    }

    /**
     * DELETE /users/removeAvatar/{id}
     * Deletes the row from user_profile_images and sets users.avatar_url = null.
     */
    @DeleteMapping("/removeAvatar/{id}")
    public ResponseEntity<?> RemoveAvatar(@PathVariable Long id) throws CustomException {
        return usersService.RemoveAvatar(id);
    }
}