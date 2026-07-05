package com.chatroom.kafka;

import com.chatroom.service.RedisMessageService;
import com.chatroom.ws.Hub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes chat messages from Kafka and dispatches them to local WebSocket clients.
 *
 * Each ws-server instance uses its own Kafka consumer group (groupId = server.id),
 * so ALL instances receive ALL messages independently — same approach as the Go version.
 *
 * Logic (mirrors Go Hub.BroadcastFromKafka):
 *  1. Extract serverID header from the Kafka record
 *  2. Save to Redis (dedup prevents double-write across servers)
 *  3. If senderServerID == this server's ID → skip broadcast (already done locally in handleBroadcastMessage)
 *  4. Otherwise → broadcast to local clients in that room
 */
@Slf4j
@Component
public class ChatMessageConsumer {

    private final Hub hub;
    private final RedisMessageService redisService;
    private final String serverId;
    private final ObjectMapper objectMapper;

    public ChatMessageConsumer(Hub hub,
                               RedisMessageService redisService,
                               @Value("${server.id}") String serverId,
                               ObjectMapper objectMapper) {
        this.hub = hub;
        this.redisService = redisService;
        this.serverId = serverId;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${kafka.topic}", groupId = "${server.id}")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug("Kafka message received: topic={} partition={} offset={}",
                record.topic(), record.partition(), record.offset());

        String json = record.value();

        // Extract sender's server ID from Kafka header
        String senderServerId = "";
        Header header = record.headers().lastHeader("serverID");
        if (header != null) {
            senderServerId = new String(header.value(), StandardCharsets.UTF_8);
        }

        // Parse roomId and timestamp from the JSON payload
        String roomId = null;
        String timestamp = null;
        try {
            JsonNode node = objectMapper.readTree(json);
            roomId    = node.path("roomID").asText(null);
            timestamp = node.path("sentAt").asText(null);
        } catch (Exception e) {
            log.error("Failed to parse Kafka message JSON: {}", e.getMessage());
            return;
        }

        if (roomId == null || timestamp == null) {
            log.warn("Kafka message missing roomID or sentAt, skipping");
            return;
        }

        log.info("Kafka message from server [{}] for room [{}]", senderServerId, roomId);

        // Save to Redis (dedup Set ensures only one server stores per message)
        redisService.saveMessage(roomId, timestamp, json);

        // Skip local broadcast if we produced this message — already broadcast in handleBroadcastMessage
        if (serverId.equals(senderServerId)) {
            log.debug("Message originated from this server [{}], skipping re-broadcast", serverId);
            return;
        }

        hub.broadcast(roomId, json);
    }
}
