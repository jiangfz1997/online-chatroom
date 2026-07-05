package com.chatroom.persist.service;

import com.chatroom.persist.model.RawMessage;
import com.chatroom.persist.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Drains the Redis persist queue into DynamoDB on a fixed schedule.
 *
 * Redis keys (written by ws-server, same layout as Go version):
 *   rooms:active                  — Set of room IDs that have received messages
 *   room:{roomId}:to_persist      — List of raw JSON messages (RPush by ws-server, LPop here)
 *
 * Mirrors Go persist/persist.go: StartRedisToDBSyncLoop → syncAllRooms → syncRoomMessages.
 */
@Slf4j
@Service
public class PersistService {

    private final StringRedisTemplate redis;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    // Java initializer provides the default when running without a Spring context (unit tests).
    // Spring @Value overrides this when the application context is present.
    @Value("${persist.batch-size:100}")
    private int batchSize = 100;

    public PersistService(StringRedisTemplate redis,
                          MessageRepository messageRepository,
                          ObjectMapper objectMapper) {
        this.redis = redis;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs every {@code persist.interval-ms} milliseconds (default 30 s).
     * fixedDelay ensures runs don't overlap: next tick starts after current one finishes.
     */
    @Scheduled(fixedDelayString = "${persist.interval-ms:30000}")
    public void syncAllRooms() {
        Set<String> roomIds = redis.opsForSet().members("rooms:active");
        if (roomIds == null || roomIds.isEmpty()) {
            log.debug("No active rooms to sync");
            return;
        }
        log.info("Syncing {} active room(s)", roomIds.size());
        roomIds.forEach(this::syncRoom);
    }

    /**
     * Pops up to {@code batchSize} messages from the room's persist queue and saves each to DynamoDB.
     * Uses LPop (FIFO — messages were RPush'd by the ws-server) to preserve chronological order.
     */
    void syncRoom(String roomId) {
        String key = "room:" + roomId + ":to_persist";
        int saved = 0;

        for (int i = 0; i < batchSize; i++) {
            String json = redis.opsForList().leftPop(key);
            if (json == null) break;

            try {
                RawMessage msg = objectMapper.readValue(json, RawMessage.class);
                if (isValid(msg)) {
                    messageRepository.save(msg);
                    saved++;
                } else {
                    log.warn("Skipping malformed message for room [{}]: {}", roomId, json);
                }
            } catch (Exception e) {
                log.error("Failed to parse/save message for room [{}]: {}", roomId, e.getMessage());
            }
        }

        if (saved > 0) {
            log.info("Persisted {} message(s) for room [{}]", saved, roomId);
        }
    }

    private boolean isValid(RawMessage msg) {
        return msg.getRoomId() != null && !msg.getRoomId().isBlank()
                && msg.getTimestamp() != null && !msg.getTimestamp().isBlank()
                && msg.getSender() != null
                && msg.getText() != null;
    }
}
