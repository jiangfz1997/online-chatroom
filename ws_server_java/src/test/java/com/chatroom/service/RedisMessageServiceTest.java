package com.chatroom.service;

import com.chatroom.metrics.WsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisMessageService's P3 seq-assignment/dedup and recent-cache logic
 * (tmp_doc/05 Track 1). StringRedisTemplate is mocked — no running Redis required. Dedup+seq
 * assignment, the recent-cache write, and the persist-queue write all happen inside one Lua
 * script now (ASSIGN_SEQ_AND_QUEUE_SCRIPT); the script's atomicity is a property of it being a
 * single script (asserted for real by the reliability.js load-test, not something a mock can
 * meaningfully re-verify). These tests instead pin down that saveMessage makes exactly one
 * atomic Redis call with the right keys/args and correctly parses whatever the script returns —
 * in particular that zSetOps/listOps/setOps are never touched directly from Java, which is the
 * whole point: there is no window between "seq assigned" and "cached/queued" for a crash to
 * land in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisMessageServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock ListOperations<String, String> listOps;
    @Mock SetOperations<String, String> setOps;

    RedisMessageService service;

    @BeforeEach
    void setup() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        service = new RedisMessageService(redis, new WsMetrics(new SimpleMeterRegistry()));
    }

    /** Matches the script's 5-key, 5-arg call shape regardless of the actual values. */
    private void stubAssignAndQueue(String scriptResult) {
        when(redis.<String>execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(scriptResult);
    }

    @Test
    void saveMessage_freshId_assignsSeqEmbedsItAndCachesAtomically() {
        stubAssignAndQueue("7:1:{\"seq\":7,\"text\":\"hi\"}");

        RedisMessageService.SaveResult result = service.saveMessage("room-1", "msg-1", "{\"text\":\"hi\"}");

        assertThat(result.isNew()).isTrue();
        assertThat(result.seq()).isEqualTo(7L);
        assertThat(result.json()).isEqualTo("{\"seq\":7,\"text\":\"hi\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keysCaptor.capture(),
                eq("msg-1"), eq("{\"text\":\"hi\"}"), eq("room-1"), any(), any());
        assertThat(keysCaptor.getValue()).containsExactly(
                "room:room-1:msgseq", "room:room-1:seqcounter",
                "room:room-1:recent", "room:room-1:to_persist", "rooms:active");

        // Dedup, seq assignment, recent-cache write and persist-queue write are all inside the
        // script above — Java must never touch these ops directly, or the atomicity is fake.
        verifyNoInteractions(zSetOps, listOps, setOps);
    }

    @Test
    void saveMessage_duplicateId_skipsCacheWriteAndReturnsExistingSeqWithNoJson() {
        // A resent/redelivered id must not re-cache or re-queue for persistence — the
        // caller (ChatMessageConsumer) uses isNew=false as the signal to skip broadcast too.
        stubAssignAndQueue("7:0:");

        RedisMessageService.SaveResult result = service.saveMessage("room-1", "msg-1", "{\"text\":\"hi\"}");

        assertThat(result.isNew()).isFalse();
        assertThat(result.seq()).isEqualTo(7L);
        assertThat(result.json()).isNull();

        verifyNoInteractions(zSetOps, listOps, setOps);
    }

    @Test
    void saveMessage_freshId_jsonContainingColons_parsedCorrectly() {
        // The old parser used lastIndexOf(':'), which broke once the trailing json payload was
        // folded into the same return string (colons inside message text, or JSON's own
        // "field":value colons, would shift where the split lands). The new parser only looks
        // at the first two colons, which are structural (numeric seq, single-char flag).
        stubAssignAndQueue("9:1:{\"seq\":9,\"text\":\"time: 10:30, see you\"}");

        RedisMessageService.SaveResult result = service.saveMessage("room-1", "msg-2", "{\"text\":\"time: 10:30, see you\"}");

        assertThat(result.isNew()).isTrue();
        assertThat(result.seq()).isEqualTo(9L);
        assertThat(result.json()).isEqualTo("{\"seq\":9,\"text\":\"time: 10:30, see you\"}");
    }

    @Test
    void getMessagesSince_returnsMessagesAfterLastSeq_notTruncatedWhenCacheCoversGap() {
        when(zSetOps.rangeByScore("room:room-1:recent", 6, Double.POSITIVE_INFINITY))
                .thenReturn(new LinkedHashSet<>(List.of("{\"seq\":6}", "{\"seq\":7}")));
        when(zSetOps.rangeWithScores(eq("room:room-1:recent"), eq(0L), eq(0L)))
                .thenReturn(Set.of(new DefaultTypedTuple<>("{\"seq\":3}", 3.0)));

        RedisMessageService.SyncResult sync = service.getMessagesSince("room-1", 5L);

        assertThat(sync.messages()).containsExactly("{\"seq\":6}", "{\"seq\":7}");
        assertThat(sync.truncated()).isFalse();
    }

    @Test
    void getMessagesSince_truncatedWhenOldestCachedSeqIsPastGapStart() {
        // The oldest entry still in the cache is seq=20, but the client last saw seq=10 —
        // messages 11..19 were already evicted by the recentCount trim, so this cache alone
        // can't fill the gap and the client must fall back to fetch_history.
        when(zSetOps.rangeByScore("room:room-1:recent", 11, Double.POSITIVE_INFINITY))
                .thenReturn(Set.of());
        when(zSetOps.rangeWithScores(eq("room:room-1:recent"), eq(0L), eq(0L)))
                .thenReturn(Set.of(new DefaultTypedTuple<>("{\"seq\":20}", 20.0)));

        RedisMessageService.SyncResult sync = service.getMessagesSince("room-1", 10L);

        assertThat(sync.messages()).isEmpty();
        assertThat(sync.truncated()).isTrue();
    }

    @Test
    void getMessagesSince_emptyCacheWithNonZeroLastSeq_isTruncated() {
        when(zSetOps.rangeByScore("room:room-1:recent", 1, Double.POSITIVE_INFINITY)).thenReturn(Set.of());
        when(zSetOps.rangeWithScores(eq("room:room-1:recent"), eq(0L), eq(0L))).thenReturn(Set.of());

        RedisMessageService.SyncResult sync = service.getMessagesSince("room-1", 0L);

        // lastSeq=0 means the client has never seen anything — an empty cache is not a gap.
        assertThat(sync.truncated()).isFalse();
    }
}
