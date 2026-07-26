package com.chatroom.service;

import com.chatroom.metrics.WsMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Manages per-room seq assignment, recent-message cache and persist-queue in Redis.
 * Key layout (P3 — tmp_doc/05 Track 1):
 *   room:{roomId}:msgseq     — Hash (messageId -> assigned seq; doubles as dedup + seq lookup)
 *   room:{roomId}:seqcounter — String (INCR'd to hand out the next seq)
 *   room:{roomId}:recent     — ZSet (score=seq, member=json-with-seq; replaces the old
 *                              LIST-based cache so reconnecting clients can resume by seq)
 *   room:{roomId}:to_persist — List (RPush, consumed by persist-worker)
 *   rooms:active             — Set (active room IDs for persist-worker discovery)
 *
 * {@code msgseq}/{@code recent} intentionally use new key names rather than reusing the old
 * {@code dedup:room:{id}} Set / {@code room:{id}:messages} List — a rolling deploy that mixed
 * old and new code hitting the same key with a different type would blow up with WRONGTYPE.
 */
@Slf4j
@Service
public class RedisMessageService {

    /**
     * Atomically assigns (or looks up) the per-room seq for a message id: HGET-or-INCR.
     * This must be a single Lua script, not a check-then-increment done from Java — a client
     * resend and Kafka's own at-least-once redelivery both replay the same message id, and a
     * non-atomic version would race the two, occasionally handing out two different seqs for
     * the same id (a permanent hole in the sequence that no client-side resync can repair).
     * Returns "{seq}:0" for an id already seen (duplicate), "{seq}:1" for a freshly assigned one.
     */
    private static final RedisScript<String> ASSIGN_SEQ_SCRIPT = RedisScript.of(
            "local existing = redis.call('HGET', KEYS[1], ARGV[1])\n" +
                    "if existing then\n" +
                    "  return existing .. ':0'\n" +
                    "end\n" +
                    "local seq = redis.call('INCR', KEYS[2])\n" +
                    "redis.call('HSET', KEYS[1], ARGV[1], seq)\n" +
                    "return tostring(seq) .. ':1'",
            String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final WsMetrics metrics;

    @Value("${redis.message.recent-count:50}")
    private long recentCount;

    @Value("${redis.message.ttl-seconds:86400}")
    private long ttlSeconds;

    public RedisMessageService(StringRedisTemplate redis, ObjectMapper objectMapper, WsMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Outcome of {@link #saveMessage}: whether this id was new, its assigned seq, and (only
     *  when new) the message JSON with {@code seq} embedded — this is the copy callers must
     *  broadcast/dispatch/persist instead of the original, unseq'd JSON. */
    public record SaveResult(boolean isNew, long seq, String json) {}

    /**
     * Assign a seq to (or look up the existing seq for) a message id, and — for a genuinely
     * new id — cache it in the recent-message ZSet and queue it for persistence.
     *
     * @param roomId    room the message belongs to
     * @param messageId unique per-message id (client-generated clientMsgId, or a server
     *                  fallback UUID) — must NOT be a timestamp, since distinct messages can
     *                  share the same millisecond
     * @param json      raw JSON message string, without a seq field yet
     * @return a {@link SaveResult}; {@code isNew=false} means messageId was already seen — a
     *         client-side resend after a slow/lost ack, or Kafka's own at-least-once
     *         redelivery — and callers must skip broadcast/dispatch/persist entirely, or every
     *         resend becomes a visible double-message for the rest of the room.
     */
    public SaveResult saveMessage(String roomId, String messageId, String json) {
        return metrics.recordRedisRtt(() -> doSaveMessage(roomId, messageId, json));
    }

    private SaveResult doSaveMessage(String roomId, String messageId, String json) {
        String seqHashKey    = "room:" + roomId + ":msgseq";
        String seqCounterKey = "room:" + roomId + ":seqcounter";

        String result = redis.execute(ASSIGN_SEQ_SCRIPT, List.of(seqHashKey, seqCounterKey), messageId);
        int sep = result.lastIndexOf(':');
        long seq = Long.parseLong(result.substring(0, sep));
        boolean isNew = "1".equals(result.substring(sep + 1));

        if (!isNew) {
            log.debug("Duplicate message for room [{}] id={} (seq={}), skipping Redis write", roomId, messageId, seq);
            return new SaveResult(false, seq, null);
        }

        String jsonWithSeq = embedSeq(json, seq);

        String recentKey  = "room:" + roomId + ":recent";
        String persistKey = "room:" + roomId + ":to_persist";
        String activeKey  = "rooms:active";

        redis.opsForZSet().add(recentKey, jsonWithSeq, seq);
        // Keep only the newest recentCount entries (ZSet is score-ascending, so rank 0 is oldest).
        redis.opsForZSet().removeRange(recentKey, 0, -(recentCount + 1));
        redis.expire(recentKey, Duration.ofSeconds(ttlSeconds));

        redis.opsForList().rightPush(persistKey, jsonWithSeq);
        redis.opsForSet().add(activeKey, roomId);

        log.debug("Saved message to Redis for room [{}], seq={}", roomId, seq);
        return new SaveResult(true, seq, jsonWithSeq);
    }

    private String embedSeq(String json, long seq) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(json);
            node.put("seq", seq);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to embed seq into message JSON: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Return the most recent {@code recentCount} messages from the room's cache, newest first
     * (the ws handler reverses before sending, so the oldest of the batch goes out first).
     */
    public List<String> getRecentMessages(String roomId) {
        String key = "room:" + roomId + ":recent";
        try {
            Set<String> msgs = redis.opsForZSet().reverseRange(key, 0, recentCount - 1);
            return msgs != null ? new ArrayList<>(msgs) : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch recent messages for room [{}]: {}", roomId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Outcome of {@link #getMessagesSince}: the messages the recent-cache can supply (oldest
     *  first, ready to replay in order), plus whether the cache's own retention window already
     *  cut off part of the gap — in which case the caller must additionally fall back to
     *  fetch_history (DynamoDB, time-paginated) to have any chance of filling the rest. */
    public record SyncResult(List<String> messages, boolean truncated) {}

    /**
     * Messages in the room's recent cache with seq strictly greater than {@code lastSeq} —
     * used to resume a client that reconnected (or detected a live gap) at a known seq.
     */
    public SyncResult getMessagesSince(String roomId, long lastSeq) {
        String key = "room:" + roomId + ":recent";
        try {
            Set<String> range = redis.opsForZSet().rangeByScore(key, lastSeq + 1, Double.POSITIVE_INFINITY);
            List<String> messages = range != null ? new ArrayList<>(range) : new ArrayList<>();

            Set<ZSetOperations.TypedTuple<String>> oldest = redis.opsForZSet().rangeWithScores(key, 0, 0);
            boolean truncated;
            if (oldest == null || oldest.isEmpty()) {
                // Empty cache: fine if the client had nothing to catch up on, otherwise we
                // can't tell whether it's a truly empty room or an evicted/reset cache — the
                // safe assumption is "yes, go fetch history" rather than silently dropping it.
                truncated = lastSeq > 0;
            } else {
                Double oldestSeq = oldest.iterator().next().getScore();
                truncated = oldestSeq != null && oldestSeq > lastSeq + 1;
            }
            return new SyncResult(messages, truncated);
        } catch (Exception e) {
            log.warn("Failed to fetch sync messages for room [{}] since seq={}: {}", roomId, lastSeq, e.getMessage());
            return new SyncResult(Collections.emptyList(), true);
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
