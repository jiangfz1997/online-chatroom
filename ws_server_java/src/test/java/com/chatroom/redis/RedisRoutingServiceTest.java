package com.chatroom.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisRoutingService's dispatch logic: given a room's routing set,
 * verify it excludes the sender, skips (and prunes) dead instances, and publishes
 * only to live, non-sender targets. StringRedisTemplate is mocked — this does not
 * require a running Redis.
 */
@ExtendWith(MockitoExtension.class)
class RedisRoutingServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock SetOperations<String, String> setOps;

    RedisRoutingService routingService;

    private static final String SELF = "ws-java-1";

    @BeforeEach
    void setup() throws Exception {
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        routingService = new RedisRoutingService(redis);
        // server.id is normally injected by Spring @Value; set it directly for the unit test.
        Field field = RedisRoutingService.class.getDeclaredField("serverId");
        field.setAccessible(true);
        field.set(routingService, SELF);
    }

    @Test
    void onRoomOccupied_addsSelfToRoutingSet() {
        routingService.onRoomOccupied("room-1");
        verify(setOps).add("room:room-1:instances", SELF);
    }

    @Test
    void onRoomVacated_removesSelfFromRoutingSet() {
        routingService.onRoomVacated("room-1");
        verify(setOps).remove("room:room-1:instances", SELF);
    }

    @Test
    void dispatch_publishesToLiveTargetsExcludingSender() {
        when(setOps.members("room:room-1:instances")).thenReturn(Set.of(SELF, "ws-java-2", "ws-java-3"));
        when(redis.hasKey("instance:ws-java-2:alive")).thenReturn(true);
        when(redis.hasKey("instance:ws-java-3:alive")).thenReturn(true);

        routingService.dispatch("room-1", "{\"roomID\":\"room-1\"}");

        verify(redis, never()).convertAndSend(eq("instance:" + SELF + ":messages"), anyString());
        verify(redis).convertAndSend("instance:ws-java-2:messages", "{\"roomID\":\"room-1\"}");
        verify(redis).convertAndSend("instance:ws-java-3:messages", "{\"roomID\":\"room-1\"}");
    }

    @Test
    void dispatch_prunesAndSkipsDeadInstances() {
        when(setOps.members("room:room-1:instances")).thenReturn(Set.of("ws-java-2"));
        when(redis.hasKey("instance:ws-java-2:alive")).thenReturn(false);

        routingService.dispatch("room-1", "{\"roomID\":\"room-1\"}");

        verify(setOps).remove("room:room-1:instances", "ws-java-2");
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void dispatch_emptyRoutingSet_doesNothing() {
        when(setOps.members("room:room-1:instances")).thenReturn(Set.of());

        routingService.dispatch("room-1", "{\"roomID\":\"room-1\"}");

        verify(redis, never()).convertAndSend(anyString(), anyString());
        verify(setOps, never()).remove(anyString(), anyString());
    }
}
