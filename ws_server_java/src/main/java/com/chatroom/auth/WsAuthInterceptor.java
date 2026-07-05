package com.chatroom.auth;

import com.chatroom.repository.MessageRepository;
import com.chatroom.service.RedisMessageService;
import com.chatroom.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Validates every WebSocket handshake before the connection is upgraded.
 * Returns false (HTTP 401/403) to reject; sets session attributes on success.
 *
 * Auth flow (mirrors Go auth.go + handler.go):
 *  1. Parse JWT from ?token=<jwt> query param
 *  2. Check Redis: "token:{jwt}" must exist and match the JWT subject
 *  3. Check DynamoDB: user must be a member of the requested room
 *  4. Slide the Redis token TTL forward
 *  5. Store username and roomId in WebSocket session attributes for the handler
 */
@Slf4j
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final RedisMessageService redisService;
    private final MessageRepository messageRepository;

    public WsAuthInterceptor(JwtUtil jwtUtil,
                             RedisMessageService redisService,
                             MessageRepository messageRepository) {
        this.jwtUtil = jwtUtil;
        this.redisService = redisService;
        this.messageRepository = messageRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        String query = request.getURI().getQuery(); // e.g. "token=xxx" or "username=alice&token=xxx"
        String token = extractParam(query, "token");

        if (token == null || token.isBlank()) {
            log.warn("WebSocket handshake rejected: missing token");
            setStatus(response, 401);
            return false;
        }

        // Step 1: Parse JWT
        String username;
        try {
            username = jwtUtil.parseToken(token);
        } catch (Exception e) {
            log.warn("WebSocket handshake rejected: invalid JWT — {}", e.getMessage());
            setStatus(response, 401);
            return false;
        }

        // Step 2: Verify token is live in Redis (ensures logout invalidation works)
        String redisUsername = redisService.validateToken(token);
        if (redisUsername == null || !redisUsername.equals(username)) {
            log.warn("WebSocket handshake rejected: token not in Redis for user [{}]", username);
            setStatus(response, 401);
            return false;
        }

        // Step 3: Extract roomId from URL path  (/ws/<roomId>)
        String path = request.getURI().getPath();
        String roomId = path.substring(path.lastIndexOf('/') + 1);
        if (roomId.isBlank()) {
            log.warn("WebSocket handshake rejected: missing roomId in path");
            setStatus(response, 400);
            return false;
        }

        // Step 4: Verify room membership in DynamoDB
        if (!messageRepository.isUserInRoom(roomId, username)) {
            log.warn("WebSocket handshake rejected: user [{}] is not a member of room [{}]", username, roomId);
            setStatus(response, 403);
            return false;
        }

        // Step 5: Refresh token TTL and store attributes for the handler
        redisService.refreshToken(token);
        attributes.put("username", username);
        attributes.put("roomId", roomId);
        log.info("WebSocket handshake accepted: user=[{}] room=[{}]", username, roomId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String extractParam(String query, String paramName) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(paramName + "=")) {
                return part.substring(paramName.length() + 1);
            }
        }
        return null;
    }

    private void setStatus(ServerHttpResponse response, int status) {
        try {
            if (response instanceof ServletServerHttpResponse r) {
                r.getServletResponse().setStatus(status);
            }
        } catch (Exception ignored) {}
    }
}
