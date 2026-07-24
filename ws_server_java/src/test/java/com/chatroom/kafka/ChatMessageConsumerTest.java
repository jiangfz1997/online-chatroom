package com.chatroom.kafka;

import com.chatroom.metrics.WsMetrics;
import com.chatroom.redis.RedisRoutingService;
import com.chatroom.service.RedisMessageService;
import com.chatroom.ws.Hub;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatMessageConsumer.
 * Under the shared-consumer-group design, this instance may receive a message that
 * was originally produced by any ws-server instance (not just itself), because Kafka's
 * partition assignment — not the producing server's identity — decides who processes it.
 * The consumer's job: save to Redis (skip everything else on a duplicate), broadcast
 * locally if this instance has clients in the room, and hand off routing to
 * RedisRoutingService for every other instance that does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatMessageConsumerTest {

    @Mock RedisMessageService redisService;
    @Mock RedisRoutingService routingService;
    @Mock Hub hub;

    ChatMessageConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String OTHER_SERVER = "ws-java-2";

    @BeforeEach
    void setup() {
        consumer = new ChatMessageConsumer(redisService, routingService, hub, objectMapper,
                new WsMetrics(new SimpleMeterRegistry()));
        // Default: a fresh (non-duplicate) message, no local room — most tests only care
        // about dispatch(); the dedup-gating and local-broadcast behavior get their own tests.
        when(redisService.saveMessage(anyString(), anyString(), anyString())).thenReturn(true);
        when(hub.hasRoom(anyString())).thenReturn(false);
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
    void consume_savesToRedisAndDispatches() {
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", OTHER_SERVER);
        consumer.consume(record);

        // Dedup key is the message id, not the timestamp — two distinct messages can share
        // the same sentAt millisecond, so the id is what must be passed through.
        verify(redisService).saveMessage(eq("room-1"), eq("msg-id-1"), anyString());
        verify(routingService).dispatch(eq("room-1"), anyString());
    }

    @Test
    void consume_missingServerIdHeader_stillDispatches() {
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", null);
        consumer.consume(record);

        verify(routingService).dispatch(eq("room-1"), anyString());
    }

    @Test
    void consume_hasLocalRoom_broadcastsLocallyAndDispatches() {
        when(hub.hasRoom("room-1")).thenReturn(true);
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", OTHER_SERVER);

        consumer.consume(record);

        verify(hub).broadcast(eq("room-1"), anyString());
        verify(routingService).dispatch(eq("room-1"), anyString());
    }

    @Test
    void consume_noLocalRoom_skipsLocalBroadcastButStillDispatches() {
        when(hub.hasRoom("room-1")).thenReturn(false);
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", OTHER_SERVER);

        consumer.consume(record);

        verify(hub, never()).broadcast(anyString(), anyString());
        verify(routingService).dispatch(eq("room-1"), anyString());
    }

    @Test
    void consume_duplicateMessageId_skipsLocalBroadcastAndDispatch() {
        // A duplicate (client resend after a slow ack, or Kafka redelivery) must not be
        // broadcast/routed again — every room member would see the message twice.
        when(redisService.saveMessage(anyString(), anyString(), anyString())).thenReturn(false);
        when(hub.hasRoom("room-1")).thenReturn(true);
        var record = makeRecord("room-1", "msg-id-1", "2024-01-01T10:00:00Z", OTHER_SERVER);

        consumer.consume(record);

        verify(hub, never()).broadcast(anyString(), anyString());
        verify(routingService, never()).dispatch(anyString(), anyString());
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

        verifyNoInteractions(routingService, redisService, hub);
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

        verifyNoInteractions(routingService, redisService, hub);
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

        verifyNoInteractions(routingService, redisService, hub);
    }
}
