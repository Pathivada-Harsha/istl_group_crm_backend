package com.istlgroup.istl_group_crm_backend.controller;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SessionController {

    @GetMapping("/session/check")
    public ResponseEntity<?> checkSession(HttpSession session) {

        // ✅ Session active — return JSON
        if (session != null && session.getAttribute("USER_ID") != null) {
            return ResponseEntity.ok()
                .body(Map.of("status", "SESSION_ACTIVE"));
        }

        // ✅ Session expired — return JSON not plain text
        return ResponseEntity.status(401)
            .body(Map.of(
                "error", "SESSION_EXPIRED",
                "message", "Your session has expired. Please log in again."
            ));
    }

    /**
     * Identity of THIS browser's session in the session registry.
     * The frontend WebSocket guard calls this once after login so it can
     * match incoming SESSION_TERMINATED events against its own session row
     * and log out instantly when its row is the one terminated.
     */
    @GetMapping("/session/whoami")
    public ResponseEntity<?> whoAmI(HttpSession session) {
        if (session == null || session.getAttribute("USER_ID") == null) {
            return ResponseEntity.status(401)
                .body(Map.of("error", "SESSION_EXPIRED"));
        }
        Object rowId = session.getAttribute("SESSION_ROW_ID");
        return ResponseEntity.ok(Map.of(
            "userId", session.getAttribute("USER_ID"),
            "sessionRowId", rowId != null ? rowId : -1
        ));
    }
}