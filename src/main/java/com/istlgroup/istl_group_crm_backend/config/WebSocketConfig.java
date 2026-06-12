package com.istlgroup.istl_group_crm_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.Principal;
import java.util.Map;

/**
 * Real-time notification transport.
 *
 *  Client connects to:        /ws-notifications        (SockJS)
 *  Client subscribes to:      /user/queue/notifications
 *  Server sends per-user via: convertAndSendToUser(userId, "/queue/notifications", ...)
 *
 * AUTHENTICATION
 * --------------
 * This CRM uses HTTP session auth (JSESSIONID), not JWT. The SockJS handshake
 * carries the session cookie, so we read the {@code USER_ID} attribute that
 * LoginService put on the session at login and bind it as the STOMP Principal.
 * Because the Principal name equals the numeric users.id, Spring routes
 * /user/** messages to exactly that user — never broadcast to everyone.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Reuse the same CORS origins property the REST layer uses. */
    @Value("${cros.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker. /queue is used for per-user destinations.
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);

        registry.addEndpoint("/ws-notifications")
                .setAllowedOriginPatterns(origins)
                .setHandshakeHandler(new SessionPrincipalHandshakeHandler())
                .addInterceptors(new SessionUserHandshakeInterceptor())
                .withSockJS();
    }

    /**
     * Copies USER_ID from the HTTP session into the handshake attributes so the
     * handshake handler below can turn it into a Principal.
     */
    static class SessionUserHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                HttpServletRequest req = servletRequest.getServletRequest();
                HttpSession session = req.getSession(false);
                if (session != null) {
                    Object userId = session.getAttribute("USER_ID");
                    if (userId != null) {
                        attributes.put("USER_ID", String.valueOf(userId));
                        return true;
                    }
                }
            }
            // No valid session → reject the handshake. Unauthenticated clients
            // cannot open a notification socket.
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) { }
    }

    /** Builds the STOMP Principal from the USER_ID handshake attribute. */
    static class SessionPrincipalHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            Object userId = attributes.get("USER_ID");
            String name = userId != null ? String.valueOf(userId) : null;
            return (name == null) ? null : () -> name;   // Principal::getName == userId
        }
    }
}
