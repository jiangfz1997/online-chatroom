package com.chatroom.persist.repository;

/**
 * A different message is already stored under this (room_id, seq). Retrying can never succeed,
 * so the caller should park the message instead of looping on it.
 */
public class SeqConflictException extends RuntimeException {

    public SeqConflictException(String roomId, long seq, String messageId) {
        super("seq " + seq + " in room [" + roomId + "] is already taken by another message; "
                + "refusing to overwrite it with message id=" + messageId);
    }
}
