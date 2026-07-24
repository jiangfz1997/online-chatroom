package com.chatroom.service;

import com.chatroom.metrics.WsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Manages recent-message cache and persist-queue in Redis.
 * Key layout (matches Go redis.go):
 *   room:{roomId}:messages  — List (newest first via LPush, capped at recentCount)
 *   room:{roomId}:to_persist — List (RPush, consumed by persist-worker)
 *   dedup:room:{roomId}     — Set  (message-id dedup, prevents double-write on multi-server)
 *   rooms:active            — Set  (active room IDs for persist-worker discovery)
 */
@Slf4j
@Service
public class RedisMessageService {

    private final StringRedisTemplate redis;
    private final WsMetrics metrics;

    @Value("${redis.message.recent-count:50}")
    private long recentCount;

    @Value("${redis.message.ttl-seconds:86400}")
    private long ttlSeconds;

    public RedisMessageService(StringRedisTemplate redis, WsMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /**
     * Save a message to Redis.
     * Uses dedup set so that if multiple ws-servers receive the same Kafka message,
     * only the first writer stores it (matching Go version's SADD dedup).
     *
     * @param roomId    room the message belongs to
     * @param messageId unique per-message id (used as dedup key — must NOT be a timestamp,
     *                  since distinct messages can share the same millisecond)
     * @param json      raw JSON message string
     * @return true if this call actually stored the message (first time seen), false if
     *         messageId was already present (a duplicate — e.g. a client-side resend after
     *         a slow/lost ack, or Kafka's own at-least-once redelivery). Callers that also
     *         broadcast/route the message must skip that step on a duplicate, or every
     *         resend becomes a visible double-message for the rest of the room.
     */
    public boolean saveMessage(String roomId, String messageId, String json) {
        return metrics.recordRedisRtt(() -> doSaveMessage(roomId, messageId, json));
    }

    private boolean doSaveMessage(String roomId, String messageId, String json) {
        String dedupKey   = "dedup:room:" + roomId;
        String msgKey     = "room:" + roomId + ":messages";
        String persistKey = "room:" + roomId + ":to_persist";
        String activeKey  = "rooms:active";

        // SADD returns 0 if messageId already exists → duplicate, skip
        Long added = redis.opsForSet().add(dedupKey, messageId);
        if (added == null || added == 0) {
            log.debug("Duplicate message for room [{}] id={}, skipping Redis write", roomId, messageId);
            return false;
        }

        // Push to recent-message list (newest first), cap at recentCount
        redis.opsForList().leftPush(msgKey, json);
        redis.opsForList().trim(msgKey, 0, recentCount - 1);
        redis.expire(msgKey, Duration.ofSeconds(ttlSeconds));

        // Push to persist queue for the persist-worker to consume
        redis.opsForList().rightPush(persistKey, json);

        // Mark room as active so persist-worker can discover it
        redis.opsForSet().add(activeKey, roomId);

        log.debug("Saved message to Redis for room [{}]", roomId);
        return true;
    }

    /**
     * Return the most recent {@code recentCount} messages from the room's message list.
     * The list is newest-first (LPush order), so we return as-is for the ws handler
     * which reverses before sending (matching Go: iterate len-1 down to 0).
     */
    public List<String> getRecentMessages(String roomId) {
        String key = "room:" + roomId + ":messages";
        try {
            List<String> msgs = redis.opsForList().range(key, 0, recentCount - 1);
            return msgs != null ? msgs : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch recent messages for room [{}]: {}", roomId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Check Redis for active token and return username, or null if not found/mismatched. */
    public String validateToken(String token) {
        return redis.opsForValue().get("token:" + token);
    }

    /** Slide the token TTL forward (keep active sessions alive). */
    public void refreshToken(String token) {
        redis.expire("token:" + token, Duration.ofHours(24));
    }
}
