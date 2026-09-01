package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.dto.AiChatRequest;
import com.istlgroup.istl_group_crm_backend.dto.AiChatResponse;
import com.istlgroup.istl_group_crm_backend.service.AiService;
import com.istlgroup.istl_group_crm_backend.entity.UsersEntity;
import com.istlgroup.istl_group_crm_backend.security.ActingUserPermissionsService;
import com.istlgroup.istl_group_crm_backend.security.ActingUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AiController (v4)
 * ──────────────────
 * POST /ai-assistant/chat
 *
 * Request body:
 * {
 *   "messages":      [ { "role": "user", "content": "what is my name?" } ],
 *   "imageBase64":   "...",
 *   "imageMimeType": "image/png"
 * }
 *
 * The body may also carry "menuPermissions", "pagePermissions", "visibleRoles" and
 * "userContext" — the frontend still sends them and the server IGNORES all four. They
 * used to be taken at face value, which meant a caller could post themselves
 * SUPERADMIN's permission set and have the assistant answer over data they cannot see.
 * Permissions are now rebuilt from the database for the session user; see
 * ActingUserPermissionsService.
 */
@RestController
@RequestMapping("/ai-assistant")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Autowired
    private AiService aiService;

    @Autowired
    private ActingUserPermissionsService actingUserPermissions;

    @Autowired
    private ActingUserService actingUserService;

    /**
     * The permission fields on the request body ({@code menuPermissions},
     * {@code pagePermissions}, {@code visibleRoles}, {@code userContext}) are IGNORED.
     * They are still accepted because the frontend sends them, but the assistant is
     * authorized against what the database says about the session user — posting
     * yourself SUPERADMIN's permission set no longer widens what it will answer.
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody AiChatRequest request,
            HttpServletRequest httpRequest) {

        // Identity from the session. requireUser 401s if it cannot be established, so
        // there is no unauthenticated path into the assistant.
        UsersEntity actingUser = actingUserService.requireUser(httpRequest);
        Long   userId   = actingUser.getId();
        String userRole = actingUserService.requireRole(httpRequest);

        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "messages array is required"));
        }

        // ── Extract latest user message ────────────────────────────────────
        String question = lastUserMessage(request);

        // Allow image-only messages (no text)
        if (question == null && request.getImageBase64() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user message or image found"));
        }

        try {
            AiChatResponse response = aiService.handleQuestion(
                    userId,
                    userRole,
                    question,
                    actingUserPermissions.menuPermissions(userId),
                    actingUserPermissions.pagePermissions(userId),
                    actingUserPermissions.visibleRoles(userRole),
                    actingUserPermissions.userContext(actingUser),
                    request.getImageBase64(),
                    request.getImageMimeType()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("AI: unexpected error for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "AI service error: " + e.getMessage()));
        }
    }

    /**
     * Extracts the content of the last message with role="user".
     * Only the latest message is processed to minimize token usage —
     * full history is accepted from frontend for future multi-turn support.
     */
    private String lastUserMessage(AiChatRequest request) {
        List<AiChatRequest.AiChatMessage> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatRequest.AiChatMessage m = messages.get(i);
            if ("user".equalsIgnoreCase(m.getRole())
                    && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }
}