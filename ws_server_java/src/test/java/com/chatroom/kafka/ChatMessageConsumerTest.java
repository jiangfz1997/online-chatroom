package com.chatroom.kafka;

import com.chatroom.metrics.WsMetrics;
import com.chatroom.redis.RedisRoutingService;
import com.chatroom.service.RedisMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatMessageConsumer.
 * Under the shared-consumer-group design, this instance may receive a message that
 * was originally produced by any ws-server instance (not just itself), because Kafka's
 * partition assignment — not the producing server's identity — decides who processes it.
 * The consumer's only job is: save to Redis, then hand off routing to RedisRoutingService.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageConsumerTest {

    @Mock RedisMessageService redisService;
    @Mock RedisRoutingService routingService;

    ChatMessageConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String OTHER_SERVER = "ws-java-2";

    @BeforeEach
    void setup() {
        consumer = new ChatMessageConsumer(redisService, routingService, objectMapper,
                new WsMetrics(new SimpleMeterRegistry()));
    }

    private ConsumerRecord<String, String> makeRecord(String roomId, String id, String sentAt,
                                                       String senderServerId) {
        String json = String.format(
                "{\"type\":\"message\",\"id\":\"%s\",\"sender\":\"alice\",\"text\":\"hi\",\"roomID\":\"%s\",\"sentAt\":\"%s\"}",
                id, roomId, sentAt);
        RecordHeaders headers = new RecordHeaders();
        if (senderServerId != null) {
            headers.add(new RecordHeader("serverID", senderServerId.getBytes(StandardCharsets.UTF_8)));
        }
        return new ConsumerRecord<>("chat_messages", 0, 0L,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, roomId, json, headers, Optional.empty());
    }

    @Test
    void consume_savesToRedisAndDispatchesWithSenderId() {
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", OTHER_SERVER);
        consumer.consume(record);

        // Dedup key is the message id, not the timestamp — two distinct messages can share
        // the same sentAt millisecond, so the id is what must be passed through.
        verify(redisService).saveMessage(eq("room-1"), eq("msg-id-1"), anyString());
        verify(routingService).dispatch(eq("room-1"), anyString(), eq(OTHER_SERVER));
    }

    @Test
    void consume_missingServerIdHeader_dispatchesWithEmptySenderId() {
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", null);
        consumer.consume(record);

        verify(routingService).dispatch(eq("room-1"), anyString(), eq(""));
    }

    @Test
    void consume_missingId_doesNotThrow() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("serverID", OTHER_SERVER.getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "chat_messages", 0, 0L,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, "room-1",
                "{\"type\":\"message\",\"sender\":\"alice\",\"text\":\"hi\",\"roomID\":\"room-1\",\"sentAt\":\"2024-01-01T10:00:00Z\"}",
                headers, Optional.empty());

        consumer.consume(record);

        verifyNoInteractions(routingService, redisService);
    }

    @Test
    void consume_malformedJson_doesNotThrow() {
        RecordHeaders headers = new RecordHeaders();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "chat_messages", 0, 0L,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, "room-1", "not-json", headers, Optional.empty());

        // Should log error and return without throwing
        consumer.consume(record);

        verifyNoInteractions(routingService, redisService);
    }

    @Test
    void consume_missingRoomId_doesNotThrow() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("serverID", OTHER_SERVER.getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "chat_messages", 0, 0L,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, "room-1", "{\"type\":\"message\",\"sentAt\":\"2024-01-01T10:00:00Z\"}", headers, Optional.empty());

        consumer.consume(record);

        verifyNoInteractions(routingService, redisService);
    }
}
