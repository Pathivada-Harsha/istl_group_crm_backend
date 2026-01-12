package com.istlgroup.istl_group_crm_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.istlgroup.istl_group_crm_backend.customException.CustomException;
import com.istlgroup.istl_group_crm_backend.entity.LoginEntity;
import com.istlgroup.istl_group_crm_backend.service.LoginService;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.LoginResponseWrapper;
import com.istlgroup.istl_group_crm_backend.wrapperClasses.UsersResponseWrapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/login")
public class LoginController {

    @Autowired
    private LoginService logingService;

    // ✅ LOGIN
    @PostMapping("/userLogin")
    public ResponseEntity<LoginResponseWrapper> login(
            @RequestBody Map<String, String> credentials,
            HttpServletRequest request
    ) throws CustomException {
        return logingService.AuthenticateUser(credentials, request);
    }

    // ✅ LOGOUT
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok("Logged out");
    }
    
    @GetMapping("/ping")
    public ResponseEntity<String> keepAlive(HttpSession session) {
        // Just touching session is enough
        return ResponseEntity.ok("ALIVE");
    }


    // ------------------ OTHER APIs ------------------

    @PutMapping("/updateUser/{id}")
    public ResponseEntity<?> updateUser(
            @RequestBody LoginEntity newData,
            @PathVariable Long id
    ) throws CustomException {
        return logingService.UpdateUser(newData, id);
    }

    @PutMapping("/updatePassword/{id}")
    public ResponseEntity<String> updatePassword(
            @RequestBody Map<String, String> credentials,
            @PathVariable Long id
    ) throws CustomException {
        return logingService.UpdatePassword(credentials, id);
    }

    @GetMapping("/users/{userId}")
    public UsersResponseWrapper users(
            @PathVariable Long userId,
            @RequestParam int page,
            @RequestParam int size
    ) throws CustomException {
        return logingService.Users(userId, page, size);
    }

    @GetMapping("/menuPermissions/{id}")
    public List<String> getMenuPermissions(@PathVariable Long id)
            throws CustomException {
        return logingService.GetMenuPermissions(id);
    }

    @GetMapping("/pagePermissions/{id}")
    public ResponseEntity<?> getPagePermissions(@PathVariable Long id)
            throws CustomException {
        return ResponseEntity.ok(logingService.GetPagePermissions(id));
    }
}
