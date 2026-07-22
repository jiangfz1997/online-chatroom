package com.chatroom.kafka;

import com.chatroom.metrics.WsMetrics;
import com.chatroom.redis.RedisRoutingService;
import com.chatroom.service.RedisMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes chat messages from Kafka and routes them to the ws-server instances that
 * actually have local clients in the target room.
 *
 * All ws-server instances share a single Kafka consumer group (groupId = kafka.group.id),
 * so Kafka's normal partition assignment spreads message processing across instances
 * instead of fanning every message out to every instance. Whichever instance ends up
 * owning the partition for a message queries the Redis routing table and forwards it
 * via Redis Pub/Sub only to the instances that need it.
 */
@Slf4j
@Component
public class ChatMessageConsumer {

    private final RedisMessageService redisService;
    private final RedisRoutingService routingService;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;

    public ChatMessageConsumer(RedisMessageService redisService,
                               RedisRoutingService routingService,
                               ObjectMapper objectMapper,
                               WsMetrics metrics) {
        this.redisService = redisService;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${kafka.topic}", groupId = "${kafka.group.id}")
    public void consume(ConsumerRecord<String, String> record) {
        Timer.Sample sample = metrics.startKafkaConsume();
        try {
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

            // Route to only the instances that have local clients in this room.
            // The sender is excluded inside dispatch() — it already broadcast locally
            // in handleBroadcastMessage before this message reached Kafka.
            routingService.dispatch(roomId, json, senderServerId);
        } finally {
            metrics.stopKafkaConsume(sample);
        }
    }
}
