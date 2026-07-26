package com.chatroom.ws;

import com.chatroom.kafka.ChatMessageProducer;
import com.chatroom.metrics.WsMetrics;
import com.chatroom.model.HistoryMessage;
import com.chatroom.repository.MessageRepository;
import com.chatroom.service.RedisMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.UUID;
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
                    case "sync"          -> handleSync(client, base);
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
     * Builds the outgoing message envelope and publishes it to Kafka. Mirrors Go
     * handleBroadcastMessage, minus the local broadcast (see below).
     */
    private void handleBroadcastMessage(ClientSession client, JsonNode incoming) {
        metrics.messageReceived();
        String text = incoming.path("text").asText("");

        // Client-generated id: lets the client resend the exact same envelope if it never
        // gets an ack (see the ack/send_error callback below) without the resend showing up
        // as a second message — ChatMessageConsumer dedupes on this id before broadcasting.
        // Falls back to a server-generated UUID for older clients that don't send one yet.
        String clientMsgId = incoming.path("id").asText("");
        String id = clientMsgId.isBlank() ? UUID.randomUUID().toString() : clientMsgId;

        Map<String, String> out = new HashMap<>();
        out.put("type",   "message");
        out.put("id",     id);
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

        log.info("Received message from user [{}] in room [{}], publishing to Kafka",
                client.getUsername(), client.getRoomId());

        // No local broadcast anymore — every client, including this sender, receives the
        // message the same way: via Kafka consume -> dispatch (ChatMessageConsumer). That
        // adds one bus hop of latency to the sender's own copy in exchange for a single
        // delivery path with no special-cased "already saw it locally" client.
        //
        // The ack/send_error sent back here is a fast "did this reach Kafka" signal for the
        // client's pending-bubble UI — it fires on the producer callback, before the message
        // has necessarily been consumed/broadcast yet.
        producer.send(client.getRoomId(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        client.send(buildControlEnvelope("send_error", id));
                    } else {
                        client.send(buildControlEnvelope("ack", id));
                    }
                });
    }

    private String buildControlEnvelope(String type, String id) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", type, "id", id));
        } catch (Exception e) {
            log.error("Failed to serialize {} envelope: {}", type, e.getMessage());
            return "{\"type\":\"" + type + "\"}";
        }
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

    /**
     * Handles a client's post-reconnect (or live-gap) resume request: {@code {type:"sync",
     * roomID, lastSeq}}. Replays whatever the recent-message ZSet cache can supply above
     * lastSeq; if the cache's own retention window already evicted part of the gap
     * ({@code truncated:true}), the client falls back to its existing fetch_history
     * time-pagination path to make a best-effort attempt at the rest (see tmp_doc/05 P3 —
     * exact seq-indexed DynamoDB backfill is explicitly out of scope).
     */
    private void handleSync(ClientSession client, JsonNode req) {
        String roomId = req.path("roomID").asText(client.getRoomId());
        long lastSeq = req.path("lastSeq").asLong(0);

        RedisMessageService.SyncResult sync = redisService.getMessagesSince(roomId, lastSeq);

        ArrayNode messages = objectMapper.createArrayNode();
        for (String json : sync.messages()) {
            try {
                messages.add(objectMapper.readTree(json));
            } catch (Exception e) {
                log.warn("Skipping malformed cached message during sync for room [{}]: {}", roomId, e.getMessage());
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("type",      "sync_result");
        resp.put("roomID",    roomId);
        resp.put("messages",  messages);
        resp.put("truncated", sync.truncated());

        try {
            client.send(objectMapper.writeValueAsString(resp));
        } catch (Exception e) {
            log.error("Failed to serialize sync response: {}", e.getMessage());
        }
    }
}
