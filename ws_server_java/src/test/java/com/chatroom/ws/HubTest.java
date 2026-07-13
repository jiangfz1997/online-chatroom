package com.chatroom.ws;

import com.chatroom.redis.RoomOccupancyListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for Hub — no Spring context, fast.
 * ClientSession is mocked so no virtual threads or WebSocket connections are needed.
 */
class HubTest {

    Hub hub;
    RoomOccupancyListener occupancyListener;

    @BeforeEach
    void setup() {
        occupancyListener = mock(RoomOccupancyListener.class);
        hub = new Hub(occupancyListener);
        hub.setServerId("test-server");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClientSession mockClient(String sessionId, String username, String roomId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(sessionId);
        when(ws.isOpen()).thenReturn(true);
        ClientSession client = mock(ClientSession.class);
        when(client.getSessionId()).thenReturn(sessionId);
        when(client.getUsername()).thenReturn(username);
        when(client.getRoomId()).thenReturn(roomId);
        return client;
    }

    // ── join / leave ──────────────────────────────────────────────────────────

    @Test
    void joinRoom_addsClientToRoom() {
        ClientSession c = mockClient("s1", "alice", "room1");
        hub.joinRoom("room1", c);
        assertThat(hub.getRoomSize("room1")).isEqualTo(1);
        assertThat(hub.hasRoom("room1")).isTrue();
    }

    @Test
    void joinRoom_multipleClients_allTracked() {
        hub.joinRoom("room1", mockClient("s1", "alice", "room1"));
        hub.joinRoom("room1", mockClient("s2", "bob",   "room1"));
        hub.joinRoom("room1", mockClient("s3", "carol", "room1"));
        assertThat(hub.getRoomSize("room1")).isEqualTo(3);
    }

    @Test
    void leaveRoom_removesClient() {
        ClientSession c = mockClient("s1", "alice", "room1");
        hub.joinRoom("room1", c);
        hub.leaveRoom("room1", c);
        assertThat(hub.getRoomSize("room1")).isEqualTo(0);
    }

    @Test
    void leaveRoom_lastClient_roomIsRemoved() {
        ClientSession c = mockClient("s1", "alice", "room1");
        hub.joinRoom("room1", c);
        hub.leaveRoom("room1", c);
        // Empty room should be cleaned up to avoid unbounded map growth
        assertThat(hub.hasRoom("room1")).isFalse();
    }

    @Test
    void leaveRoom_nonExistentRoom_doesNotThrow() {
        ClientSession c = mockClient("s1", "alice", "ghost-room");
        // Should log a warning but not throw
        hub.leaveRoom("ghost-room", c);
    }

    // ── occupancy transitions ────────────────────────────────────────────────

    @Test
    void joinRoom_firstClient_firesOnRoomOccupied() {
        hub.joinRoom("room1", mockClient("s1", "alice", "room1"));
        verify(occupancyListener).onRoomOccupied("room1");
    }

    @Test
    void joinRoom_secondClientInSameRoom_doesNotRefireOnRoomOccupied() {
        hub.joinRoom("room1", mockClient("s1", "alice", "room1"));
        hub.joinRoom("room1", mockClient("s2", "bob", "room1"));
        verify(occupancyListener, times(1)).onRoomOccupied("room1");
    }

    @Test
    void leaveRoom_lastClient_firesOnRoomVacated() {
        ClientSession c = mockClient("s1", "alice", "room1");
        hub.joinRoom("room1", c);
        hub.leaveRoom("room1", c);
        verify(occupancyListener).onRoomVacated("room1");
    }

    @Test
    void leaveRoom_notLastClient_doesNotFireOnRoomVacated() {
        ClientSession alice = mockClient("s1", "alice", "room1");
        ClientSession bob   = mockClient("s2", "bob",   "room1");
        hub.joinRoom("room1", alice);
        hub.joinRoom("room1", bob);
        hub.leaveRoom("room1", alice);
        verify(occupancyListener, never()).onRoomVacated("room1");
    }

    @Test
    void localRoomIds_reflectsCurrentlyOccupiedRooms() {
        hub.joinRoom("room1", mockClient("s1", "alice", "room1"));
        hub.joinRoom("room2", mockClient("s2", "bob",   "room2"));
        assertThat(hub.localRoomIds()).containsExactlyInAnyOrder("room1", "room2");

        hub.leaveRoom("room1", hub.getClients("room1").iterator().next());
        assertThat(hub.localRoomIds()).containsExactly("room2");
    }

    // ── broadcast ─────────────────────────────────────────────────────────────

    @Test
    void broadcast_sendsToAllClientsInRoom() {
        ClientSession alice = mockClient("s1", "alice", "room1");
        ClientSession bob   = mockClient("s2", "bob",   "room1");
        hub.joinRoom("room1", alice);
        hub.joinRoom("room1", bob);

        hub.broadcast("room1", "{\"type\":\"message\"}");

        verify(alice).send("{\"type\":\"message\"}");
        verify(bob  ).send("{\"type\":\"message\"}");
    }

    @Test
    void broadcast_doesNotSendToClientsInDifferentRoom() {
        ClientSession alice = mockClient("s1", "alice", "room1");
        ClientSession bob   = mockClient("s2", "bob",   "room2");
        hub.joinRoom("room1", alice);
        hub.joinRoom("room2", bob);

        hub.broadcast("room1", "hello");

        verify(alice).send("hello");
        verify(bob, never()).send(anyString());
    }

    @Test
    void broadcast_nonExistentRoom_doesNotThrow() {
        // Should log a warning and return without error
        hub.broadcast("no-such-room", "hello");
    }

    @Test
    void getClients_returnsAllSessionsInRoom() {
        ClientSession alice = mockClient("s1", "alice", "room1");
        ClientSession bob   = mockClient("s2", "bob",   "room1");
        hub.joinRoom("room1", alice);
        hub.joinRoom("room1", bob);

        assertThat(hub.getClients("room1")).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void getClients_emptyRoom_returnsEmpty() {
        assertThat(hub.getClients("ghost")).isEmpty();
    }
}
