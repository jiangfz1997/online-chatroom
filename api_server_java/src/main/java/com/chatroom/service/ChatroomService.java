package com.chatroom.service;

import com.chatroom.dto.CreateChatroomRequest;
import com.chatroom.exception.NotFoundException;
import com.chatroom.model.Chatroom;
import com.chatroom.repository.ChatroomRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ChatroomService {

    private final ChatroomRepository chatroomRepository;

    public ChatroomService(ChatroomRepository chatroomRepository) {
        this.chatroomRepository = chatroomRepository;
    }

    /**
     * Creates a new chatroom with a generated UUID.
     * The creator is automatically added as the first member.
     */
    public Chatroom createChatroom(String createdBy, CreateChatroomRequest req) {
        Chatroom chatroom = new Chatroom(
                UUID.randomUUID().toString(),
                req.getName(),
                req.isPrivate(),
                createdBy,
                Instant.now().toString(),
                new ArrayList<>(List.of(createdBy))
        );

        chatroomRepository.createChatroom(chatroom);
        log.info("Chatroom created: room=[{}] by=[{}]", chatroom.getRoomId(), createdBy);
        return chatroom;
    }

    /**
     * Adds a user to an existing chatroom.
     * Throws NotFoundException if the chatroom does not exist.
     * Throws IllegalArgumentException if the user is already a member.
     */
    public void joinChatroom(String username, String roomId) {
        Chatroom chatroom = chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));

        if (chatroom.getMembers().contains(username)) {
            throw new IllegalArgumentException("User already in chatroom");
        }

        chatroomRepository.addUserToRoom(roomId, username);
        log.info("User joined chatroom: room=[{}] user=[{}]", roomId, username);
    }

    /**
     * Removes a user from a chatroom.
     * Throws NotFoundException if the chatroom does not exist.
     */
    public void exitChatroom(String username, String roomId) {
        chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));

        chatroomRepository.removeUserFromRoom(roomId, username);
        log.info("User exited chatroom: room=[{}] user=[{}]", roomId, username);
    }

    /** Returns all chatrooms the user belongs to. */
    public List<Chatroom> getUserChatrooms(String username) {
        return chatroomRepository.findByUsername(username);
    }

    /**
     * Returns a single chatroom by ID.
     * Throws NotFoundException if the chatroom does not exist.
     */
    public Chatroom getChatroomByRoomId(String roomId) {
        return chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));
    }
}
