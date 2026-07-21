package com.chatroom.controller;

import com.chatroom.dto.AddMemberRequest;
import com.chatroom.dto.CreateChatroomRequest;
import com.chatroom.dto.ExitChatroomRequest;
import com.chatroom.dto.JoinChatroomRequest;
import com.chatroom.model.Chatroom;
import com.chatroom.model.Message;
import com.chatroom.model.User;
import com.chatroom.repository.MessageRepository;
import com.chatroom.service.ChatroomService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/chatrooms")
public class ChatroomController {

    private final ChatroomService chatroomService;
    private final MessageRepository messageRepository;
    private final String wsHost;

    public ChatroomController(ChatroomService chatroomService,
                              MessageRepository messageRepository,
                              @Value("${ws.host}") String wsHost) {
        this.chatroomService = chatroomService;
        this.messageRepository = messageRepository;
        this.wsHost = wsHost;
    }

    // POST /api/chatrooms
    @PostMapping
    public ResponseEntity<?> createChatroom(
            Authentication auth,
            @Valid @RequestBody CreateChatroomRequest req) {

        log.info("Create chatroom request: name=[{}] by=[{}]", req.getName(), auth.getName());
        Chatroom chatroom = chatroomService.createChatroom(auth.getName(), req);
        return ResponseEntity.ok(chatroom);
    }

    // POST /api/chatrooms/join
    @PostMapping("/join")
    public ResponseEntity<?> joinChatroom(
            Authentication auth,
            @Valid @RequestBody JoinChatroomRequest req) {

        log.info("Join chatroom request: room=[{}] by=[{}]", req.getChatroomId(), auth.getName());
        chatroomService.joinChatroom(auth.getName(), req.getChatroomId());
        return ResponseEntity.ok(Map.of("message", "Joined chatroom successfully"));
    }

    // POST /api/chatrooms/exit
    @PostMapping("/exit")
    public ResponseEntity<?> exitChatroom(
            Authentication auth,
            @Valid @RequestBody ExitChatroomRequest req) {

        log.info("Exit chatroom request: room=[{}] by=[{}]", req.getChatroomId(), auth.getName());
        chatroomService.exitChatroom(auth.getName(), req.getChatroomId());
        return ResponseEntity.ok(Map.of("message", "Exited chatroom successfully"));
    }

    // GET /api/chatrooms  (returns { rooms: [...] })
    @GetMapping
    public ResponseEntity<?> getUserChatrooms(Authentication auth) {
        return ResponseEntity.ok(Map.of("rooms", chatroomService.getUserChatrooms(auth.getName())));
    }

    // GET /api/chatrooms/user/{username}  (frontend compatibility — same as GET /api/chatrooms)
    @GetMapping("/user/{username}")
    public ResponseEntity<?> getUserChatroomsByPath(Authentication auth) {
        return ResponseEntity.ok(Map.of("rooms", chatroomService.getUserChatrooms(auth.getName())));
    }

    // GET /api/chatrooms/{roomId}  (private rooms return 404 to non-members)
    @GetMapping("/{roomId}")
    public ResponseEntity<Chatroom> getChatroomByRoomId(Authentication auth, @PathVariable String roomId) {
        return ResponseEntity.ok(chatroomService.getChatroomByRoomId(roomId, auth.getName()));
    }

    // GET /api/chatrooms/{roomId}/members  -> { members: [{username, display_name, avatar_seed}] }
    @GetMapping("/{roomId}/members")
    public ResponseEntity<?> getMembers(Authentication auth, @PathVariable String roomId) {
        List<Map<String, Object>> members = chatroomService.getMembers(roomId, auth.getName())
                .stream()
                .map(this::toMemberProfile)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("members", members));
    }

    // POST /api/chatrooms/{roomId}/members  (creator-only invite; makes private rooms usable)
    @PostMapping("/{roomId}/members")
    public ResponseEntity<?> addMember(
            Authentication auth,
            @PathVariable String roomId,
            @Valid @RequestBody AddMemberRequest req) {

        log.info("Add member request: room=[{}] user=[{}] by=[{}]", roomId, req.getUsername(), auth.getName());
        chatroomService.addMember(auth.getName(), roomId, req.getUsername());
        return ResponseEntity.ok(Map.of("message", "Member added successfully"));
    }

    /** Serializes a member for the client (username + public profile fields, no password). */
    private Map<String, Object> toMemberProfile(User user) {
        return Map.of(
                "username", user.getUsername(),
                "display_name", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                "avatar_seed", user.getAvatarSeed() != null ? user.getAvatarSeed() : user.getUsername()
        );
    }

    // GET /api/chatrooms/{roomId}/messages?before=<timestamp>&limit=<n>
    @GetMapping("/{roomId}/messages")
    public ResponseEntity<?> getChatroomMessages(
            @PathVariable String roomId,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit) {

        if (before == null || before.isBlank()) {
            before = Instant.now().toString();
        }
        if (limit <= 0) {
            limit = 20;
        }

        List<Message> messages = messageRepository.getMessagesBefore(roomId, before, limit);
        return ResponseEntity.ok(Map.of("messages", messages));
    }

    // GET /api/chatrooms/{roomId}/enter
    @GetMapping("/{roomId}/enter")
    public ResponseEntity<?> enterChatRoom(
            Authentication auth,
            @PathVariable String roomId) {

        String wsUrl = wsHost + "/ws/" + roomId + "?username=" + auth.getName();
        return ResponseEntity.ok(Map.of(
                "room_id", roomId,
                "ws_url",  wsUrl
        ));
    }
}
