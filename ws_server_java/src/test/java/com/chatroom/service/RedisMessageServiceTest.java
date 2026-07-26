package com.chatroom.service;

import com.chatroom.metrics.WsMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * (tmp_doc/05 Track 1). StringRedisTemplate is mocked — no running Redis required. The Lua
 * HGET-or-INCR script's atomicity is a property of it being a single script (asserted for
 * real by the reliability.js load-test, not something a mock can meaningfully re-verify);
 * these tests instead pin down how RedisMessageService uses whatever the script returns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisMessageServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock ListOperations<String, String> listOps;
    @Mock SetOperations<String, String> setOps;

    RedisMessageService service;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redis.opsForList()).thenReturn(listOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        service = new RedisMessageService(redis, objectMapper, new WsMetrics(new SimpleMeterRegistry()));
    }

    private void stubAssignSeq(String messageId, long seq, boolean isNew) {
        when(redis.<String>execute(any(RedisScript.class), anyList(), eq(messageId)))
                .thenReturn(seq + ":" + (isNew ? "1" : "0"));
    }

    @Test
    void saveMessage_freshId_assignsSeqEmbedsItAndCaches() {
        stubAssignSeq("msg-1", 7L, true);

        RedisMessageService.SaveResult result = service.saveMessage("room-1", "msg-1", "{\"text\":\"hi\"}");

        assertThat(result.isNew()).isTrue();
        assertThat(result.seq()).isEqualTo(7L);
        assertThat(result.json()).contains("\"seq\":7").contains("\"text\":\"hi\"");

        verify(zSetOps).add(eq("room:room-1:recent"), eq(result.json()), eq(7.0));
        verify(listOps).rightPush(eq("room:room-1:to_persist"), eq(result.json()));
        verify(setOps).add("rooms:active", "room-1");
    }

    @Test
    void saveMessage_duplicateId_skipsCacheWriteAndReturnsExistingSeqWithNoJson() {
        // A resent/redelivered id must not re-cache or re-queue for persistence — the
        // caller (ChatMessageConsumer) uses isNew=false as the signal to skip broadcast too.
        stubAssignSeq("msg-1", 7L, false);

        RedisMessageService.SaveResult result = service.saveMessage("room-1", "msg-1", "{\"text\":\"hi\"}");

        assertThat(result.isNew()).isFalse();
        assertThat(result.seq()).isEqualTo(7L);
        assertThat(result.json()).isNull();

        verifyNoInteractions(zSetOps, listOps, setOps);
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
