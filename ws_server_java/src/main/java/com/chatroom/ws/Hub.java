package com.chatroom.ws;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
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

    @Getter
    @Setter
    @Value("${server.id}")
    private String serverId;

    public void joinRoom(String roomId, ClientSession client) {
        rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
             .put(client.getSessionId(), client);
        log.info("User [{}] entered room [{}]", client.getUsername(), roomId);
    }

    public void leaveRoom(String roomId, ClientSession client) {
        ConcurrentHashMap<String, ClientSession> room = rooms.get(roomId);
        if (room == null) {
            log.warn("Cannot remove user [{}] from non-existent room [{}]", client.getUsername(), roomId);
            return;
        }
        room.remove(client.getSessionId());
        // Clean up empty rooms to prevent unbounded map growth
        rooms.computeIfPresent(roomId, (k, r) -> r.isEmpty() ? null : r);
        log.info("User [{}] left room [{}]", client.getUsername(), roomId);
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
        log.debug("Broadcasting to {} clients in room [{}]", room.size(), roomId);
        room.values().forEach(client -> client.send(message));
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
