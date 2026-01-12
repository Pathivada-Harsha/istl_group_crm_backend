package com.istlgroup.istl_group_crm_backend.controller;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    @GetMapping("/session/check")
    public ResponseEntity<?> checkSession(HttpSession session) {

        // If session exists, backend considers user logged in
        if (session != null && session.getAttribute("USER_ID") != null) {
            return ResponseEntity.ok().body("SESSION_ACTIVE");
        }

        // Session expired / invalid
        return ResponseEntity.status(401).body("SESSION_EXPIRED");
    }
}