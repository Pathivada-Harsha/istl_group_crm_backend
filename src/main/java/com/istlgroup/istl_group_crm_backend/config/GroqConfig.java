package com.istlgroup.istl_group_crm_backend.config;

import com.istlgroup.istl_group_crm_backend.repo.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GroqConfig {

    /** Text model — SQL generation, phrasing, guidance */
    public static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    /** Vision model — reads and understands images */
    public static final String GROQ_VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";

    @Autowired
    private AppConfigRepository appConfigRepository;

    private String groqApiKey;

    // Reads the key from DB when app starts
    @PostConstruct
    public void init() {
        this.groqApiKey = appConfigRepository.findById("GROQ_API_KEY")
                .orElseThrow(() -> new RuntimeException(
                        "GROQ_API_KEY not found in app_config table!"))
                .getConfigValue();
    }

    @Bean
    public WebClient groqWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.groq.com")
                .defaultHeader("content-type", "application/json")
                .defaultHeader("Authorization", "Bearer " + groqApiKey)
                .build();
    }
}