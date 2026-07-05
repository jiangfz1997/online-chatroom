package com.chatroom.service;

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
 *   dedup:room:{roomId}     — Set  (timestamp dedup, prevents double-write on multi-server)
 *   rooms:active            — Set  (active room IDs for persist-worker discovery)
 */
@Slf4j
@Service
public class RedisMessageService {

    private final StringRedisTemplate redis;

    @Value("${redis.message.recent-count:50}")
    private long recentCount;

    @Value("${redis.message.ttl-seconds:86400}")
    private long ttlSeconds;

    public RedisMessageService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Save a message to Redis.
     * Uses dedup set so that if multiple ws-servers receive the same Kafka message,
     * only the first writer stores it (matching Go version's SADD dedup).
     *
     * @param roomId    room the message belongs to
     * @param timestamp ISO-8601 timestamp (used as dedup key)
     * @param json      raw JSON message string
     */
    public void saveMessage(String roomId, String timestamp, String json) {
        String dedupKey   = "dedup:room:" + roomId;
        String msgKey     = "room:" + roomId + ":messages";
        String persistKey = "room:" + roomId + ":to_persist";
        String activeKey  = "rooms:active";

        // SADD returns 0 if timestamp already exists → duplicate, skip
        Long added = redis.opsForSet().add(dedupKey, timestamp);
        if (added == null || added == 0) {
            log.debug("Duplicate message for room [{}] ts={}, skipping Redis write", roomId, timestamp);
            return;
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
