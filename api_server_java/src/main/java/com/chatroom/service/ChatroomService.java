package com.chatroom.service;

import com.chatroom.dto.CreateChatroomRequest;
import com.chatroom.exception.ForbiddenException;
import com.chatroom.exception.NotFoundException;
import com.chatroom.model.Chatroom;
import com.chatroom.model.User;
import com.chatroom.repository.ChatroomRepository;
import com.chatroom.repository.UserRepository;
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
    private final UserRepository userRepository;

    public ChatroomService(ChatroomRepository chatroomRepository, UserRepository userRepository) {
        this.chatroomRepository = chatroomRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a new chatroom with a generated UUID.
     * The creator is automatically added as the first member.
     */
    public Chatroom createChatroom(String createdBy, CreateChatroomRequest req) {
        Chatroom chatroom = Chatroom.builder()
                .roomId(UUID.randomUUID().toString())
                .roomName(req.getName())
                .description(req.getDescription())
                .isPrivate(req.isPrivate())
                .createdBy(createdBy)
                .createdAt(Instant.now().toString())
                .members(new ArrayList<>(List.of(createdBy)))
                .build();

        chatroomRepository.createChatroom(chatroom);
        log.info("Chatroom created: room=[{}] by=[{}]", chatroom.getRoomId(), createdBy);
        return chatroom;
    }

    /**
     * Adds a user to an existing chatroom.
     * Throws NotFoundException if the chatroom does not exist.
     * Throws IllegalArgumentException if the user is already a member.
     * Throws ForbiddenException if the room is private (private rooms are join-by-invite).
     */
    public void joinChatroom(String username, String roomId) {
        Chatroom chatroom = chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));

        if (chatroom.getMembers().contains(username)) {
            throw new IllegalArgumentException("User already in chatroom");
        }

        if (chatroom.isPrivate()) {
            throw new ForbiddenException("This chatroom is private");
        }

        chatroomRepository.addUserToRoom(roomId, username);
        log.info("User joined chatroom: room=[{}] user=[{}]", roomId, username);
    }

    /**
     * Adds a member to a chatroom on the creator's behalf (invite). This is the
     * only way to grow a private room's membership.
     * Throws ForbiddenException if the caller is not the room's creator.
     */
    public void addMember(String requester, String roomId, String targetUsername) {
        Chatroom chatroom = chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));

        if (!chatroom.getCreatedBy().equals(requester)) {
            throw new ForbiddenException("Only the room creator can add members");
        }
        if (chatroom.getMembers().contains(targetUsername)) {
            throw new IllegalArgumentException("User already in chatroom");
        }

        chatroomRepository.addUserToRoom(roomId, targetUsername);
        log.info("Member added: room=[{}] user=[{}] by=[{}]", roomId, targetUsername, requester);
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
     * Returns a single chatroom by ID for the given requester.
     * Private rooms are hidden from non-members: they get a 404 (not a 403) so a
     * search can't even confirm a private room exists.
     */
    public Chatroom getChatroomByRoomId(String roomId, String requester) {
        Chatroom chatroom = chatroomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new NotFoundException("Chatroom not found"));

        if (chatroom.isPrivate() && !chatroom.getMembers().contains(requester)) {
            throw new NotFoundException("Chatroom not found");
        }
        return chatroom;
    }

    /**
     * Returns the member profiles (username / displayName / avatarSeed) of a room
     * the requester belongs to. Non-members of a private room get a 404.
     */
    public List<User> getMembers(String roomId, String requester) {
        Chatroom chatroom = getChatroomByRoomId(roomId, requester);
        return userRepository.findProfiles(chatroom.getMembers());
    }
}
