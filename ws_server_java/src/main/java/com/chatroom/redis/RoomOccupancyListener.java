package com.chatroom.redis;

/**
 * Notified by {@link com.chatroom.ws.Hub} whenever a room transitions between having
 * zero and having at least one locally-connected client on this instance.
 */
public interface RoomOccupancyListener {

    /** Fired when a room goes from absent/empty to having its first local client. */
    void onRoomOccupied(String roomId);

    /** Fired when a room's last local client disconnects and the room becomes empty. */
    void onRoomVacated(String roomId);
}
