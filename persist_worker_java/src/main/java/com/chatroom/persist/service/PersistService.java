package com.chatroom.persist.service;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import com.chatroom.persist.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Drains the Redis persist queue into DynamoDB on a fixed schedule.
 *
 * Redis keys (written by ws-server, same layout as Go version):
 *   rooms:active                    — Set of room IDs that have received messages
 *   room:{roomId}:to_persist        — List of raw JSON messages (RPush by ws-server)
 *   room:{roomId}:persist_processing — List: messages currently being written to DynamoDB
 *                                      (P4 — tmp_doc/05 Track 1). A message sits here only
 *                                      between being claimed off to_persist and its DynamoDB
 *                                      write being confirmed; a crash in that window leaves
 *                                      it here instead of dropping it, and the next tick's
 *                                      recoverOrphans() puts it back at the front of
 *                                      to_persist for a retry. A retry can only ever produce
 *                                      a duplicate DynamoDB row (idempotent SK absorbs it),
 *                                      never data loss.
 *
 * Mirrors Go persist/persist.go: StartRedisToDBSyncLoop → syncAllRooms → syncRoomMessages.
 */
@Slf4j
@Service
public class PersistService {

    private final StringRedisTemplate redis;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final PersistMetrics metrics;

    // Java initializer provides the default when running without a Spring context (unit tests).
    // Spring @Value overrides this when the application context is present.
    @Value("${persist.batch-size:100}")
    private int batchSize = 100;

    public PersistService(StringRedisTemplate redis,
                          MessageRepository messageRepository,
                          ObjectMapper objectMapper,
                          PersistMetrics metrics) {
        this.redis = redis;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Runs every {@code persist.interval-ms} milliseconds (default 30 s).
     * fixedDelay ensures runs don't overlap: next tick starts after current one finishes.
     */
    @Scheduled(fixedDelayString = "${persist.interval-ms:30000}")
    public void syncAllRooms() {
        MDC.put("batchId", UUID.randomUUID().toString().substring(0, 8));
        try {
            Set<String> roomIds = redis.opsForSet().members("rooms:active");
            if (roomIds == null || roomIds.isEmpty()) {
                log.debug("No active rooms to sync");
                return;
            }
            log.info("Syncing {} active room(s)", roomIds.size());
            metrics.setBacklog(totalBacklog(roomIds));
            roomIds.forEach(this::syncRoom);
        } finally {
            MDC.remove("batchId");
        }
    }

    /**
     * Claims up to {@code batchSize} messages from the room's persist queue (LMOVE into
     * persist_processing, not a destructive pop) and saves each to DynamoDB, removing it
     * from persist_processing only once the write is confirmed. Also recovers any orphans
     * a previous crashed run left behind before claiming anything new.
     */
    void syncRoom(String roomId) {
        String sourceKey = "room:" + roomId + ":to_persist";
        String processingKey = "room:" + roomId + ":persist_processing";

        recoverOrphans(roomId, sourceKey, processingKey);

        int saved = 0;
        for (int i = 0; i < batchSize; i++) {
            String json = redis.opsForList().move(sourceKey, Direction.LEFT, processingKey, Direction.RIGHT);
            if (json == null) break;

            try {
                RawMessage msg = objectMapper.readValue(json, RawMessage.class);
                if (isValid(msg)) {
                    messageRepository.save(msg);
                    metrics.messagePersisted();
                    saved++;
                } else {
                    log.warn("Skipping malformed message for room [{}]: {}", roomId, json);
                    metrics.messageMalformed();
                }
                // Parsed and handled (saved or deliberately skipped as malformed) with no
                // exception — safe to drop from processing either way.
                redis.opsForList().remove(processingKey, 1, json);
            } catch (Exception e) {
                // Left in processingKey on purpose: a transient DynamoDB failure (or a crash
                // right here) must not silently drop the message — recoverOrphans() on the
                // next tick retries it before anything newer.
                log.error("Failed to parse/save message for room [{}]: {}", roomId, e.getMessage());
            }
        }

        if (saved > 0) {
            log.info("Persisted {} message(s) for room [{}]", saved, roomId);
        }
    }

    /**
     * Moves anything left in persist_processing back to the front of to_persist, in its
     * original order, so it's retried before anything newer. Under normal operation
     * persist_processing is always empty by the time a tick finishes (every claimed message
     * is removed on success or logged failure), so this only ever does real work after a
     * crash left messages claimed-but-unconfirmed.
     */
    void recoverOrphans(String roomId, String sourceKey, String processingKey) {
        Long size = redis.opsForList().size(processingKey);
        if (size == null || size == 0) return;

        log.warn("Recovering {} orphaned message(s) for room [{}] from a previous run", size, roomId);
        for (long i = 0; i < size; i++) {
            String moved = redis.opsForList().move(processingKey, Direction.RIGHT, sourceKey, Direction.LEFT);
            if (moved == null) break;
        }
    }

    /** Sum of the to_persist queue length across all active rooms — a snapshot taken once per tick. */
    private long totalBacklog(Set<String> roomIds) {
        long total = 0;
        for (String roomId : roomIds) {
            Long len = redis.opsForList().size("room:" + roomId + ":to_persist");
            if (len != null) {
                total += len;
            }
        }
        return total;
    }

    private boolean isValid(RawMessage msg) {
        return msg.getRoomId() != null && !msg.getRoomId().isBlank()
                && msg.getTimestamp() != null && !msg.getTimestamp().isBlank()
                && msg.getId() != null && !msg.getId().isBlank()
                && msg.getSender() != null
                && msg.getText() != null;
    }
}
