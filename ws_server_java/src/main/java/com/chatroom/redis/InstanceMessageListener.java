package com.chatroom.redis;

import com.chatroom.ws.Hub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Subscribed (via RedisPubSubConfig) to this instance's own "instance:{serverId}:messages"
 * channel. Messages arriving here were routed by RedisRoutingService#dispatch from another
 * ws-server instance and should be broadcast to this instance's local clients only.
 */
@Slf4j
@Component
public class InstanceMessageListener implements MessageListener {

    private final Hub hub;
    private final ObjectMapper objectMapper;

    public InstanceMessageListener(Hub hub, ObjectMapper objectMapper) {
        this.hub = hub;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        String roomId;
        try {
            JsonNode node = objectMapper.readTree(json);
            roomId = node.path("roomID").asText(null);
        } catch (Exception e) {
            log.error("Failed to parse routed Pub/Sub message JSON: {}", e.getMessage());
            return;
        }
        if (roomId == null) {
            log.warn("Routed Pub/Sub message missing roomID, skipping");
            return;
        }
        hub.broadcast(roomId, json);
    }
}
