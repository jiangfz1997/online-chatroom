package com.chatroom.ws;

import com.chatroom.kafka.ChatMessageProducer;
import com.chatroom.metrics.WsMetrics;
import com.chatroom.model.HistoryMessage;
import com.chatroom.repository.MessageRepository;
import com.chatroom.service.RedisMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main WebSocket handler. One instance shared across all sessions (Spring default).
 * Maintains a session→ClientSession map for lifecycle management.
 *
 * Mirrors Go: handler.go (ServeWs) + client.go (ReadPump / WritePump / HandleMessage).
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final Hub hub;
    private final RedisMessageService redisService;
    private final MessageRepository messageRepository;
    private final ChatMessageProducer producer;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;

    // Session registry (WebSocketSession.getId() → ClientSession)
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(Hub hub,
                                RedisMessageService redisService,
                                MessageRepository messageRepository,
                                ChatMessageProducer producer,
                                ObjectMapper objectMapper,
                                WsMetrics metrics) {
        this.hub = hub;
        this.redisService = redisService;
        this.messageRepository = messageRepository;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Called after WebSocket upgrade succeeds (interceptor already validated auth).
     * Mirrors Go ServeWs: join hub → push recent history → start pumps.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        MDC.put("connId", session.getId());
        try {
            String username = (String) session.getAttributes().get("username");
            String roomId   = (String) session.getAttributes().get("roomId");

            ClientSession client = new ClientSession(session, username, roomId);
            client.start();
            sessions.put(session.getId(), client);
            hub.joinRoom(roomId, client);

            log.info("User [{}] connected to room [{}]", username, roomId);

            // Push recent messages from Redis (newest-first list, send oldest first like Go)
            List<String> recent = redisService.getRecentMessages(roomId);
            for (int i = recent.size() - 1; i >= 0; i--) {
                client.send(recent.get(i));
            }
            log.info("Sent {} recent messages to user [{}]", recent.size(), username);
        } finally {
            MDC.remove("connId");
        }
    }

    /**
     * Called for every incoming text frame. Dispatches by "type" field.
     * Mirrors Go HandleMessage → switch case.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        MDC.put("connId", session.getId());
        try {
            ClientSession client = sessions.get(session.getId());
            if (client == null) return;

            String json = message.getPayload();
            log.debug("Received from user [{}]: {}", client.getUsername(), json);

            try {
                JsonNode base = objectMapper.readTree(json);
                String type = base.path("type").asText("");

                switch (type) {
                    case "message"       -> handleBroadcastMessage(client, base);
                    case "fetch_history" -> handleFetchHistory(client, base);
                    default              -> log.warn("Unknown message type [{}] from user [{}]", type, client.getUsername());
                }
            } catch (Exception e) {
                log.error("Failed to parse message from user [{}]: {}", client.getUsername(), e.getMessage());
            }
        } finally {
            MDC.remove("connId");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        MDC.put("connId", session.getId());
        try {
            ClientSession client = sessions.remove(session.getId());
            if (client == null) return;

            hub.leaveRoom(client.getRoomId(), client);
            client.shutdown();
            log.info("User [{}] disconnected from room [{}], status={}", client.getUsername(), client.getRoomId(), status);
        } finally {
            MDC.remove("connId");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        MDC.put("connId", session.getId());
        try {
            log.warn("Transport error for session [{}]: {}", session.getId(), exception.getMessage());
        } finally {
            MDC.remove("connId");
        }
    }

    // Allows concurrent sessions (default is false in some Spring versions)
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ── private dispatch methods ──────────────────────────────────────────────

    /**
     * Builds the outgoing message envelope, broadcasts locally, then publishes to Kafka.
     * Mirrors Go handleBroadcastMessage.
     */
    private void handleBroadcastMessage(ClientSession client, JsonNode incoming) {
        metrics.messageReceived();
        String text = incoming.path("text").asText("");

        Map<String, String> out = new HashMap<>();
        out.put("type",   "message");
        out.put("sender", client.getUsername());
        out.put("text",   text);
        out.put("roomID", client.getRoomId());
        out.put("sentAt", Instant.now().toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("JSON serialization failed: {}", e.getMessage());
            return;
        }

        log.info("Received message from user [{}] in room [{}], broadcasting + pushing to Kafka",
                client.getUsername(), client.getRoomId());

        // Broadcast to all local clients in the room (including sender for echo)
        hub.broadcast(client.getRoomId(), json);

        // Publish to Kafka so other ws-server instances forward to their local clients
        producer.send(client.getRoomId(), json);
    }

    /**
     * Fetches historical messages from DynamoDB and sends them back to the requesting client only.
     * Mirrors Go handleFetchHistory.
     */
    private void handleFetchHistory(ClientSession client, JsonNode req) {
        String roomId = req.path("roomID").asText(client.getRoomId());
        String before = req.path("before").asText("");
        int    limit  = req.path("limit").asInt(20);

        if (before.isBlank()) {
            before = Instant.now().toString();
        }
        if (limit <= 0) limit = 20;

        List<HistoryMessage> messages = messageRepository.getMessagesBefore(roomId, before, limit);

        String lastTime = messages.isEmpty() ? "" : messages.get(messages.size() - 1).getTimestamp();

        Map<String, Object> resp = new HashMap<>();
        resp.put("type",            "history_result");
        resp.put("roomID",          roomId);
        resp.put("messages",        messages);
        resp.put("hasMore",         messages.size() == limit);
        resp.put("lastMessageTime", lastTime);

        try {
            client.send(objectMapper.writeValueAsString(resp));
        } catch (Exception e) {
            log.error("Failed to serialize history response: {}", e.getMessage());
        }
    }
}
