package com.chatroom.ws;

import com.chatroom.metrics.WsMetrics;
import com.chatroom.redis.RoomOccupancyListener;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active WebSocket clients grouped by room.
 * Thread-safe: uses ConcurrentHashMap for both rooms and per-room clients.
 * Mirrors the Go Hub struct (hub.go).
 */
@Slf4j
@Component
public class Hub {

    // roomId → (sessionId → ClientSession)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ClientSession>> rooms =
            new ConcurrentHashMap<>();

    private final RoomOccupancyListener occupancyListener;
    private final WsMetrics metrics;

    @Getter
    @Setter
    @Value("${server.id}")
    private String serverId;

    public Hub(RoomOccupancyListener occupancyListener, WsMetrics metrics) {
        this.occupancyListener = occupancyListener;
        this.metrics = metrics;
    }

    public void joinRoom(String roomId, ClientSession client) {
        // Detect the empty→occupied transition atomically with the membership write,
        // so a concurrent leaveRoom() can't observe a stale "empty" state and fire
        // onRoomVacated() for a room that actually just gained a member.
        boolean[] wasNewRoom = new boolean[1];
        rooms.compute(roomId, (k, r) -> {
            if (r == null) {
                wasNewRoom[0] = true;
                r = new ConcurrentHashMap<>();
            }
            r.put(client.getSessionId(), client);
            return r;
        });
        log.info("User [{}] entered room [{}]", client.getUsername(), roomId);
        metrics.sessionJoined();
        if (wasNewRoom[0]) {
            occupancyListener.onRoomOccupied(roomId);
        }
    }

    public void leaveRoom(String roomId, ClientSession client) {
        boolean[] becameEmpty = new boolean[1];
        boolean[] removed = new boolean[1];
        rooms.computeIfPresent(roomId, (k, r) -> {
            removed[0] = r.remove(client.getSessionId()) != null;
            if (r.isEmpty()) {
                becameEmpty[0] = true;
                return null; // remove the room entry to prevent unbounded map growth
            }
            return r;
        });
        if (removed[0]) {
            metrics.sessionLeft();
        }
        if (becameEmpty[0]) {
            log.info("User [{}] left room [{}]", client.getUsername(), roomId);
            occupancyListener.onRoomVacated(roomId);
        } else if (!rooms.containsKey(roomId)) {
            log.warn("Cannot remove user [{}] from non-existent room [{}]", client.getUsername(), roomId);
        } else {
            log.info("User [{}] left room [{}]", client.getUsername(), roomId);
        }
    }

    /** Snapshot of room IDs this instance currently has local clients in. */
    public Set<String> localRoomIds() {
        return Set.copyOf(rooms.keySet());
    }

    /**
     * Deliver a JSON message to every connected client in the room.
     * If a client's queue is full the message is silently dropped for that client
     * (ClientSession.send already logs the drop).
     */
    public void broadcast(String roomId, String message) {
        ConcurrentHashMap<String, ClientSession> room = rooms.get(roomId);
        if (room == null) {
            log.warn("Broadcast failed: room [{}] not found in hub", roomId);
            return;
        }
        int size = room.size();
        log.debug("Broadcasting to {} clients in room [{}]", size, roomId);
        metrics.recordBroadcast(size, () -> room.values().forEach(client -> client.send(message)));
    }

    /** Returns a snapshot of sessions in a room (for testing / monitoring). */
    public Collection<ClientSession> getClients(String roomId) {
        ConcurrentHashMap<String, ClientSession> room = rooms.get(roomId);
        return room == null ? java.util.Collections.emptyList() : room.values();
    }

    public int getRoomSize(String roomId) {
        ConcurrentHashMap<String, ClientSession> room = rooms.get(roomId);
        return room == null ? 0 : room.size();
    }

    public boolean hasRoom(String roomId) {
        return rooms.containsKey(roomId);
    }
}
