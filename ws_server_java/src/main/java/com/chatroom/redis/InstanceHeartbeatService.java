package com.chatroom.redis;

import com.chatroom.ws.Hub;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Periodically marks this instance as alive in Redis and re-asserts its current
 * room memberships in the routing table, so that:
 *  - crashed/killed instances (Deployment, not StatefulSet — pod name changes on
 *    restart) are lazily pruned from routing sets by RedisRoutingService#dispatch
 *    once their liveness key expires;
 *  - any routing entry accidentally dropped by a race in Hub's join/leave
 *    bookkeeping self-heals within one heartbeat interval.
 */
@Slf4j
@Service
public class InstanceHeartbeatService {

    private static final Duration ALIVE_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final Hub hub;

    @Value("${server.id}")
    private String serverId;

    public InstanceHeartbeatService(StringRedisTemplate redis, Hub hub) {
        this.redis = redis;
        this.hub = hub;
    }

    @Scheduled(fixedRate = 10_000)
    public void refresh() {
        redis.opsForValue().set("instance:" + serverId + ":alive", "1", ALIVE_TTL);
        for (String roomId : hub.localRoomIds()) {
            redis.opsForSet().add("room:" + roomId + ":instances", serverId);
        }
        log.debug("Heartbeat refreshed for instance [{}]", serverId);
    }
}
