package br.com.fipe.sinc_service.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final byte[] websocketProtocolToken;

    public ApiTokenFilter(@Value("${app.security.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("app.security.token must be configured");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.websocketProtocolToken = ("token." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expected)).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-API-Token");
        boolean authorized = supplied == null ? browserWebSocketToken(request)
                : MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean browserWebSocketToken(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())
                || !request.getRequestURI().endsWith("/api/v1/sync/progress")
                || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) return false;
        String protocols = request.getHeader("Sec-WebSocket-Protocol");
        if (protocols == null) return false;
        for (String candidate : protocols.split(",")) {
            if (MessageDigest.isEqual(websocketProtocolToken,
                    candidate.trim().getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }
}
