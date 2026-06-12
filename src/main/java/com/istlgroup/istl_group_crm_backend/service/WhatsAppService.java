package com.istlgroup.istl_group_crm_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    @Value("${whatsapp.access-token}")
    private String accessToken;

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.api-url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─────────────────────────────────────────────
    // Send a plain TEXT message
    // ─────────────────────────────────────────────
    @Async
    public void sendTextMessage(String toMobileNumber, String message) {
        try {
            String sanitizedNumber = sanitizePhoneNumber(toMobileNumber);
            log.info("WhatsAppService: Sending to {} (raw: {})", sanitizedNumber, toMobileNumber);

            String url = apiUrl + "/" + phoneNumberId + "/messages";

            String payload = String.format(
                "{ \"messaging_product\": \"whatsapp\", \"to\": \"%s\", \"type\": \"text\", \"text\": { \"body\": \"%s\" } }",
                sanitizedNumber, escapeJson(message)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("WhatsAppService: Message sent successfully to {}", sanitizedNumber);
            } else {
                log.error("WhatsAppService: API returned non-2xx status {} for number {}. Response: {}",
                        response.getStatusCode(), sanitizedNumber, response.getBody());
            }

        } catch (Exception e) {
            log.error("WhatsAppService: Failed to send message to {}. Error: {}", toMobileNumber, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Internal helper: sanitize phone number
    // Removes spaces/dashes, strips leading +/0,
    // and prepends country code 91 if missing.
    // Examples:
    //   9876543210    -> 919876543210
    //   09876543210   -> 919876543210
    //   +919876543210 -> 919876543210
    //   919876543210  -> 919876543210 (unchanged)
    // ─────────────────────────────────────────────
    private String sanitizePhoneNumber(String phone) {
        if (phone == null) return "";

        // Remove spaces, dashes, brackets
        String cleaned = phone.replaceAll("[\\s\\-().]+", "");

        // Remove leading +
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }

        // Remove leading 0 (e.g. 09876543210)
        if (cleaned.startsWith("0")) {
            cleaned = cleaned.substring(1);
        }

        // If 10-digit number, prepend default country code 91 (India)
        if (cleaned.length() == 10) {
            cleaned = "91" + cleaned;
            log.info("WhatsAppService: Country code missing, defaulted to 91. Final: {}", cleaned);
        }

        return cleaned;
    }

    // ─────────────────────────────────────────────
    // Internal helper: escape special JSON chars
    // ─────────────────────────────────────────────
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "");
    }
}