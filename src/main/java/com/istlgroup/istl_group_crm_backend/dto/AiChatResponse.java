package com.istlgroup.istl_group_crm_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for POST /ai-assistant/chat
 *
 * {
 *   "reply": "There are currently 12 pending leads.",
 *   "source": "predefined" | "dynamic_sql" | "groq_direct"
 * }
 *
 * "source" is informational only — helps with debugging / UI badges.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    private String reply;
    private String source;

    public static AiChatResponse of(String reply, String source) {
        return new AiChatResponse(reply, source);
    }
}
