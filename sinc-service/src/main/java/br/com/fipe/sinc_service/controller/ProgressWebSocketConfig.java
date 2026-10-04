package br.com.fipe.sinc_service.controller;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ProgressWebSocketConfig implements WebSocketConfigurer {
    private final ProgressWebSocketHandler handler;
    private final String[] allowedOrigins;

    public ProgressWebSocketConfig(ProgressWebSocketHandler handler,
            @Value("${app.websocket.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/sync/progress")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
