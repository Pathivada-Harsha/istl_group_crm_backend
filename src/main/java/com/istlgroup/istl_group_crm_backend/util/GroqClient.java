package com.istlgroup.istl_group_crm_backend.util;

import com.istlgroup.istl_group_crm_backend.config.GroqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GroqClient (v3 — with retry + exponential backoff)
 * ────────────────────────────────────────────────────
 * When Groq returns 429 (over capacity) or 5xx, retries up to 3 times
 * with exponential backoff: 2s → 4s → 8s.
 */
@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private static final int  MAX_RETRIES    = 3;
    private static final long BASE_DELAY_MS  = 2000L; // 2 seconds

    @Autowired
    private WebClient groqWebClient;

    // ─── Text completion (single-turn) ────────────────────────────────────────
    public String complete(String systemPrompt, String userPrompt,
                           int maxTokens, double temperature) {
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", userPrompt)
        );
        return callWithRetry(GroqConfig.GROQ_MODEL, messages, maxTokens, temperature);
    }

    // ─── Text completion (multi-turn) ─────────────────────────────────────────
    public String completeConversation(List<Map<String, Object>> messages,
                                       int maxTokens, double temperature) {
        return callWithRetry(GroqConfig.GROQ_MODEL, messages, maxTokens, temperature);
    }

    // ─── Vision completion ────────────────────────────────────────────────────
    public String completeWithVision(String systemPrompt, String userText,
                                     String imageBase64, String mimeType,
                                     int maxTokens, double temperature) {

        Map<String, Object> imageUrlObj = new LinkedHashMap<>();
        imageUrlObj.put("url", "data:" + (mimeType != null ? mimeType : "image/png")
                + ";base64," + imageBase64);
        imageUrlObj.put("detail", "high");

        Map<String, Object> imageContent = new LinkedHashMap<>();
        imageContent.put("type", "image_url");
        imageContent.put("image_url", imageUrlObj);

        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", userText != null && !userText.isBlank()
                ? userText
                : "Please read and describe everything you see in this image.");

        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(imageContent, textContent));

        return callWithRetry(GroqConfig.GROQ_VISION_MODEL,
                List.of(sysMsg, userMsg), maxTokens, temperature);
    }

    // ─── Retry wrapper ────────────────────────────────────────────────────────
    private String callWithRetry(String model, List<Map<String, Object>> messages,
                                  int maxTokens, double temperature) {
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call(model, messages, maxTokens, temperature);

            } catch (RuntimeException e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";

                // Retry on over-capacity or server errors, not on auth/bad-request errors
                boolean shouldRetry = msg.contains("over capacity")
                        || msg.contains("rate_limit")
                        || msg.contains("529")
                        || msg.contains("503")
                        || msg.contains("500");

                if (!shouldRetry || attempt == MAX_RETRIES) {
                    throw e;
                }

                long delayMs = BASE_DELAY_MS * (1L << (attempt - 1)); // 2s, 4s, 8s
                log.warn("Groq over capacity (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_RETRIES, delayMs, msg);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during Groq retry", ie);
                }
            }
        }

        throw lastException;
    }

    // ─── Shared HTTP call ─────────────────────────────────────────────────────
    private String call(String model, List<Map<String, Object>> messages,
                        int maxTokens, double temperature) {

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model",       model);
        requestBody.put("messages",    messages);
        requestBody.put("max_tokens",  maxTokens);
        requestBody.put("temperature", temperature);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = groqWebClient.post()
                .uri("/openai/v1/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                    status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> clientResponse.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Groq API error RAW: " + body)))
                .bodyToMono(Map.class)
                .block();

        return extractText(response);
    }

    // ─── Extract text from response ───────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null)
            throw new RuntimeException("Empty response from Groq API");
        if (response.containsKey("error")) {
            Map<String, Object> err = (Map<String, Object>) response.get("error");
            throw new RuntimeException("Groq API error: " + err.get("message"));
        }
        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty())
            throw new RuntimeException("No choices in Groq API response");
        Map<String, Object> message =
                (Map<String, Object>) choices.get(0).get("message");
        if (message == null || message.get("content") == null)
            throw new RuntimeException("No content in Groq API response");
        return (String) message.get("content");
    }
}