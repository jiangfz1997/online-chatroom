package com.chatroom.kafka;

import com.chatroom.service.RedisMessageService;
import com.chatroom.ws.Hub;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Verifies the skip-self logic and Redis/Hub dispatch.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageConsumerTest {

    @Mock Hub hub;
    @Mock RedisMessageService redisService;

    ChatMessageConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String SERVER_ID = "ws-java-1";
    private static final String OTHER_SERVER = "ws-java-2";

    @BeforeEach
    void setup() {
        consumer = new ChatMessageConsumer(hub, redisService, SERVER_ID, objectMapper);
    }

    private ConsumerRecord<String, String> makeRecord(String roomId, String sentAt,
                                                       String senderServerId) {
        String json = String.format(
                "{\"type\":\"message\",\"sender\":\"alice\",\"text\":\"hi\",\"roomID\":\"%s\",\"sentAt\":\"%s\"}",
                roomId, sentAt);
        RecordHeaders headers = new RecordHeaders();
        if (senderServerId != null) {
            headers.add(new RecordHeader("serverID", senderServerId.getBytes(StandardCharsets.UTF_8)));
        }
        return new ConsumerRecord<>("chat_messages", 0, 0L,
                System.currentTimeMillis(), TimestampType.CREATE_TIME,
                0, 0, roomId, json, headers, Optional.empty());
    }

    @Test
    void consume_fromOtherServer_savesToRedisAndBroadcasts() {
        var record = makeRecord("room-1", "2024-01-01T10:00:00Z", OTHER_SERVER);
        consumer.consume(record);

        verify(redisService).saveMessage(eq("room-1"), eq("2024-01-01T10:00:00Z"), anyString());
        verify(hub).broadcast(eq("room-1"), anyString());
    }

    @Test
    void consume_fromSameServer_savesToRedisButSkipsBroadcast() {
        var record = makeRecord("room-1", "2024-01-01T10:00:00Z", SERVER_ID);
        consumer.consume(record);

        // Redis write still happens (dedup handles double-write protection)
        verify(redisService).saveMessage(eq("room-1"), eq("2024-01-01T10:00:00Z"), anyString());
        // But hub broadcast should be skipped — already done locally when the message was sent
        verify(hub, never()).broadcast(anyString(), anyString());
    }

    @Test
    void consume_missingServerIdHeader_broadcastsAnyway() {
        var record = makeRecord("room-1", "2024-01-01T10:00:00Z", null);
        consumer.consume(record);

        verify(hub).broadcast(eq("room-1"), anyString());
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

        verifyNoInteractions(hub, redisService);
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

        verifyNoInteractions(hub, redisService);
    }
}
