package com.istlgroup.istl_group_crm_backend.controller;

import com.istlgroup.istl_group_crm_backend.dto.AiChatRequest;
import com.istlgroup.istl_group_crm_backend.dto.AiChatResponse;
import com.istlgroup.istl_group_crm_backend.service.AiService;
import jakarta.servlet.http.HttpSession;
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
 *   "messages":        [ { "role": "user", "content": "what is my name?" } ],
 *   "menuPermissions": ["SALES_LEADS", "FOLLOW_UPS", ...],
 *   "pagePermissions": { "LEADS": ["VIEW","CREATE"], "INVOICES": ["VIEW"] },
 *   "userContext": {
 *     "name": "Super Admin", "email": "...", "phone": "...",
 *     "role": "SUPERADMIN", "designation": "...", "team": "..."
 *   },
 *   "imageBase64": "...",
 *   "imageMimeType": "image/png"
 * }
 */
@RestController
@RequestMapping("/ai-assistant")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Autowired
    private AiService aiService;

    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody AiChatRequest request,
            @RequestHeader("User-Id")   Long   userId,
            @RequestHeader("User-Role") String userRole,
            HttpSession session) {

        // ── Session guard ──────────────────────────────────────────────────
        if (session == null || session.getAttribute("USER_ID") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "SESSION_EXPIRED"));
        }

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
                    request.getMenuPermissions(),
                    request.getPagePermissions(),
                    request.getVisibleRoles(),     // ← hierarchy team scope from role_hierarchy
                    request.getUserContext(),
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