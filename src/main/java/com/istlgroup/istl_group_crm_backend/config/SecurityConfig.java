package com.istlgroup.istl_group_crm_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cros.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // ── Fix: allow the download-po endpoint to be embedded in an iframe ──
            // Spring Security sets X-Frame-Options: DENY by default which blocks
            // the PDF iframe in our file viewer modal.
            // SAMEORIGIN allows framing only from our own frontend origin (safe).
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            )

            // ✅ Return proper JSON for unauthenticated requests
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                        "{\"error\":\"SESSION_EXPIRED\",\"message\":\"Your session has expired. Please log in again.\"}"
                    );
                })
            )

            .authorizeHttpRequests(auth -> auth
                // ✅ Allow ALL preflight OPTIONS requests — MUST be first
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // ✅ Public endpoints — no auth needed
                .requestMatchers(
                    "/login/userLogin",
                    "/login/logout",
                    "/login/forgot-password/verify",
                    "/login/forgot-password/resend",
                    "/login/forgot-password/reset",
                    "/error"
                ).permitAll()
                // Avatar images served as a normal URL (browser <img> tag needs no auth)
                .requestMatchers(HttpMethod.GET, "/users/avatar/**").authenticated()
                // ✅ These need valid session
                .requestMatchers(
                    "/login/ping",
                    "/login/validate-session",
                    "/login/session-config"
                ).authenticated()
                .anyRequest().authenticated()
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
   
        
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}