package com.istlgroup.istl_group_crm_backend.config;

import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GroqConfig {

    private static final Logger log = LoggerFactory.getLogger(GroqConfig.class);

    /**
     * Text model — SQL generation, phrasing, guidance. llama-3.3-70b-versatile
     * was retired by Groq (model_not_found, Sept 2026). gpt-oss is a reasoning
     * model: its thinking counts against max_tokens, so callers need headroom.
     */
    public static final String GROQ_MODEL = "openai/gpt-oss-120b";

    /** Vision model — reads and understands images */
    public static final String GROQ_VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";

    /** How long a key read from app_config is reused before it is read again. */
    private static final long KEY_TTL_MS = 60_000L;

    @Autowired
    private AppConfigRepository appConfigRepository;

    private volatile String cachedKey;
    private volatile long cachedAt;

    /**
     * The key is resolved from app_config on each request (cached for a minute),
     * not copied into the client once at startup. Copying it once meant a key
     * rotated in the database kept failing with "Invalid API Key" until a
     * restart — and, if the client bean happened to be built before the key was
     * loaded, every call sent "Bearer null".
     */
    @Bean
    public WebClient groqWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.groq.com")
                .defaultHeader("content-type", "application/json")
                .filter((request, next) -> next.exchange(ClientRequest.from(request)
                        .headers(h -> h.setBearerAuth(apiKey()))
                        .build()))
                .build();
    }

    private String apiKey() {
        long now = System.currentTimeMillis();
        String key = cachedKey;
        if (key == null || now - cachedAt > KEY_TTL_MS) {
            key = appConfigRepository.findById("GROQ_API_KEY")
                    .map(c -> c.getConfigValue() == null ? null : c.getConfigValue().strip())
                    .filter(v -> !v.isEmpty())
                    .orElseThrow(() -> new IllegalStateException("GROQ_API_KEY not found in app_config table!"));
            if (!key.equals(cachedKey)) {
                // Enough to tell two keys apart in a log, never enough to use one.
                log.info("Groq API key loaded from app_config: {}…{} ({} chars)",
                        key.substring(0, Math.min(4, key.length())),
                        key.substring(Math.max(0, key.length() - 3)), key.length());
            }
            cachedKey = key;
            cachedAt = now;
        }
        return key;
    }
}
