package com.chatroom.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Redis-backed routing table: tracks which ws-server instances currently have
 * locally-connected clients for a given room, and dispatches cross-instance
 * message delivery precisely to those instances via Redis Pub/Sub, instead of
 * broadcasting to every instance.
 *
 * Key layout:
 *   room:{roomId}:instances  — Set of serverId, instances with local clients in the room
 *   instance:{serverId}:alive — String with TTL, liveness marker refreshed by InstanceHeartbeatService
 *   instance:{serverId}:messages — Pub/Sub channel, per-instance message delivery
 */
@Slf4j
@Service
public class RedisRoutingService implements RoomOccupancyListener {

    private final StringRedisTemplate redis;

    @Value("${server.id}")
    private String serverId;

    public RedisRoutingService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void onRoomOccupied(String roomId) {
        redis.opsForSet().add(routingKey(roomId), serverId);
        log.debug("Registered instance [{}] as occupant of room [{}]", serverId, roomId);
    }

    @Override
    public void onRoomVacated(String roomId) {
        redis.opsForSet().remove(routingKey(roomId), serverId);
        log.debug("Deregistered instance [{}] from room [{}]", serverId, roomId);
    }

    public boolean isAlive(String targetServerId) {
        return Boolean.TRUE.equals(redis.hasKey(aliveKey(targetServerId)));
    }

    /**
     * Forward a message to every OTHER instance that currently has local clients in the
     * room, via each instance's own Pub/Sub channel. This instance's own local delivery
     * (if it's in the routing set too) is the caller's job via Hub directly — publishing
     * to our own channel here would be a pointless self round-trip through Redis.
     */
    public void dispatch(String roomId, String json) {
        Set<String> targets = redis.opsForSet().members(routingKey(roomId));
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (String targetId : targets) {
            if (targetId.equals(serverId)) {
                continue;
            }
            if (!isAlive(targetId)) {
                log.debug("Pruning stale instance [{}] from room [{}] routing set", targetId, roomId);
                redis.opsForSet().remove(routingKey(roomId), targetId);
                continue;
            }
            redis.convertAndSend(messageChannel(targetId), json);
        }
    }

    private static String routingKey(String roomId) {
        return "room:" + roomId + ":instances";
    }

    private static String aliveKey(String targetServerId) {
        return "instance:" + targetServerId + ":alive";
    }

    static String messageChannel(String targetServerId) {
        return "instance:" + targetServerId + ":messages";
    }
}
