package com.chatroom.kafka;

import com.chatroom.metrics.WsMetrics;
import com.chatroom.redis.RedisRoutingService;
import com.chatroom.service.RedisMessageService;
import com.chatroom.ws.Hub;
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
    private final Hub hub;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;

    public ChatMessageConsumer(RedisMessageService redisService,
                               RedisRoutingService routingService,
                               Hub hub,
                               ObjectMapper objectMapper,
                               WsMetrics metrics) {
        this.redisService = redisService;
        this.routingService = routingService;
        this.hub = hub;
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

            // Parse roomId and message id from the JSON payload
            String roomId = null;
            String messageId = null;
            try {
                JsonNode node = objectMapper.readTree(json);
                roomId    = node.path("roomID").asText(null);
                messageId = node.path("id").asText(null);
            } catch (Exception e) {
                log.error("Failed to parse Kafka message JSON: {}", e.getMessage());
                return;
            }

            if (roomId == null || messageId == null) {
                log.warn("Kafka message missing roomID or id, skipping");
                return;
            }

            log.info("Kafka message from server [{}] for room [{}]", senderServerId, roomId);

            // Assign (or look up) this room's per-message seq — the Lua HGET-or-INCR script
            // doubles as dedup on message id, deduping on the id rather than sentAt to avoid
            // false-positive collisions when two distinct messages land in the same millisecond.
            // isNew=false means this id was already processed — a client-side resend (the sender
            // didn't get an ack in time and retried the same clientMsgId) or Kafka redelivery —
            // so broadcasting/routing/persisting again would just double-deliver an
            // already-delivered message; skip all three.
            RedisMessageService.SaveResult saved = redisService.saveMessage(roomId, messageId, json);
            if (!saved.isNew()) {
                log.debug("Duplicate message id={} for room [{}] (seq={}), skipping broadcast/dispatch",
                        messageId, roomId, saved.seq());
                return;
            }
            String jsonWithSeq = saved.json();

            // No local broadcast happens at send time anymore (see ChatWebSocketHandler) — this
            // consume() call is the ONE path every client, including the original sender, gets
            // its message through. Delivering straight to this instance's own local room here
            // avoids an unnecessary self-Pub/Sub round trip through Redis.
            if (hub.hasRoom(roomId)) {
                hub.broadcast(roomId, jsonWithSeq);
            }

            // Route to whichever OTHER instances have local clients in this room.
            routingService.dispatch(roomId, jsonWithSeq);
        } finally {
            metrics.stopKafkaConsume(sample);
        }
    }
}
