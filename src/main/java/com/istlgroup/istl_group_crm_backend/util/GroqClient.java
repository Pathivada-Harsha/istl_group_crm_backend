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

    // Enough attempts for several tender section reads, launched together, to
    // take turns through a free-tier tokens-per-minute window.
    private static final int  MAX_RETRIES    = 6;
    private static final long BASE_DELAY_MS  = 2000L; // 2 seconds
    private static final long MAX_WAIT_MS    = 65_000L;

    private static final java.util.regex.Pattern TRY_AGAIN = java.util.regex.Pattern.compile(
            "try again in (?:(\\d+)m)?(\\d+(?:\\.\\d+)?)(ms|s)");

    /** "Please try again in 1m2.5s" / "in 7.66s" / "in 450ms" → milliseconds (+ a small margin), or 0. */
    static long retryAfterMs(String msg) {
        java.util.regex.Matcher m = TRY_AGAIN.matcher(msg);
        if (!m.find()) return 0;
        double n = Double.parseDouble(m.group(2));
        long ms = "ms".equals(m.group(3)) ? (long) n : (long) (n * 1000);
        if (m.group(1) != null) ms += Long.parseLong(m.group(1)) * 60_000L;
        return Math.min(MAX_WAIT_MS, ms + 500);
    }

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

                // A single request larger than the per-minute token limit (Groq
                // answers 413 "Request too large") can never succeed; waiting
                // only delays the same refusal.
                if (msg.contains("Request too large")) throw e;

                // Retry on over-capacity or server errors, not on auth/bad-request errors
                boolean shouldRetry = msg.contains("over capacity")
                        || msg.contains("rate_limit")
                        || msg.contains("529")
                        || msg.contains("503")
                        || msg.contains("500");

                if (!shouldRetry || attempt == MAX_RETRIES) {
                    throw e;
                }

                // Groq says how long the per-minute window needs ("Please try again
                // in 7.66s"); a fixed 2-4-8s backoff gives up long before a
                // tokens-per-minute limit resets. Fall back to the backoff otherwise.
                long delayMs = retryAfterMs(msg);
                if (delayMs <= 0) delayMs = BASE_DELAY_MS * (1L << (attempt - 1)); // 2s, 4s, 8s…
                log.warn("Groq rate-limited / over capacity (attempt {}/{}), retrying in {}ms",
                        attempt, MAX_RETRIES, delayMs);
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
        requestBody.put("temperature", temperature);
        if (isReasoningModel(model)) {
            // Callers size maxTokens for the ANSWER (the assistant asks for 60–300).
            // A reasoning model spends tokens thinking first and, left at the
            // caller's figure, returns an empty reply. Keep the thinking short and
            // give it its own headroom on top of the answer budget.
            requestBody.put("reasoning_effort", "low");
            requestBody.put("max_tokens", maxTokens + REASONING_HEADROOM);
        } else {
            requestBody.put("max_tokens", maxTokens);
        }

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

    /** Tokens allowed for a reasoning model's thinking, beyond the caller's answer budget. */
    private static final int REASONING_HEADROOM = 1024;

    private static boolean isReasoningModel(String model) {
        return model != null && model.startsWith("openai/gpt-oss");
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