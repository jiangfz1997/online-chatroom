package com.chatroom.service;

import com.chatroom.metrics.WsMetrics;
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
     * Atomically assigns (or looks up) the per-room seq for a message id AND — only for a
     * freshly assigned seq — caches the seq-embedded message and queues it for persistence,
     * all in the same script.
     *
     * This used to be two steps: a Lua HGET-or-INCR followed by separate ZADD/RPUSH calls from
     * Java. That split left a real crash window — if the consumer died between the script
     * returning and those follow-up calls finishing, Kafka would redeliver (offset never
     * committed under AckMode.RECORD), but the redelivered attempt would see the seq already
     * assigned, treat it as a duplicate per the javadoc below, and skip caching/queuing/
     * broadcasting entirely. The message would keep its seq (a permanent hole) but never reach
     * recent cache, the persist queue, or any client — a silent, permanent loss that no
     * client-side resync could detect, since nothing was ever stored to resync from. Folding
     * the follow-up writes into the same script closes that window: either all of it commits
     * atomically, or none of it does and Kafka's redelivery will genuinely retry the whole
     * thing (isNew still true), not just the crashed half.
     *
     * ARGV[2] (json) is assumed to be a compact JSON object with no leading whitespace, i.e.
     * starting with '{' — guaranteed here since it's always produced by our own serializer,
     * never taken from client input as-is. The seq is embedded via a plain string splice
     * rather than JSON parsing, which Lua has no built-in support for.
     *
     * Returns "{seq}:0:" for an id already seen (duplicate; no json), or
     * "{seq}:1:{jsonWithSeq}" for a freshly assigned one.
     */
    private static final RedisScript<String> ASSIGN_SEQ_AND_QUEUE_SCRIPT = RedisScript.of(
            "local existing = redis.call('HGET', KEYS[1], ARGV[1])\n" +
                    "if existing then\n" +
                    "  return existing .. ':0:'\n" +
                    "end\n" +
                    "local seq = redis.call('INCR', KEYS[2])\n" +
                    "redis.call('HSET', KEYS[1], ARGV[1], seq)\n" +
                    "local jsonWithSeq = '{\"seq\":' .. seq .. ',' .. string.sub(ARGV[2], 2)\n" +
                    "redis.call('ZADD', KEYS[3], seq, jsonWithSeq)\n" +
                    "redis.call('ZREMRANGEBYRANK', KEYS[3], 0, -(tonumber(ARGV[4]) + 1))\n" +
                    "redis.call('EXPIRE', KEYS[3], tonumber(ARGV[5]))\n" +
                    "redis.call('RPUSH', KEYS[4], jsonWithSeq)\n" +
                    "redis.call('SADD', KEYS[5], ARGV[3])\n" +
                    "return tostring(seq) .. ':1:' .. jsonWithSeq",
            String.class);

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
        String recentKey     = "room:" + roomId + ":recent";
        String persistKey    = "room:" + roomId + ":to_persist";
        String activeKey     = "rooms:active";

        String result = redis.execute(
                ASSIGN_SEQ_AND_QUEUE_SCRIPT,
                List.of(seqHashKey, seqCounterKey, recentKey, persistKey, activeKey),
                messageId, json, roomId, String.valueOf(recentCount), String.valueOf(ttlSeconds));

        // Parsed from the front (not lastIndexOf, as before) — the trailing json payload for a
        // fresh id can itself contain colons, so only the first two are structural.
        int firstSep  = result.indexOf(':');
        int secondSep = result.indexOf(':', firstSep + 1);
        long seq = Long.parseLong(result.substring(0, firstSep));
        boolean isNew = "1".equals(result.substring(firstSep + 1, secondSep));

        if (!isNew) {
            log.debug("Duplicate message for room [{}] id={} (seq={}), skipping Redis write", roomId, messageId, seq);
            return new SaveResult(false, seq, null);
        }

        String jsonWithSeq = result.substring(secondSep + 1);
        log.debug("Saved message to Redis for room [{}], seq={}", roomId, seq);
        return new SaveResult(true, seq, jsonWithSeq);
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
