package com.chatroom.controller;

import com.chatroom.dto.CreateChatroomRequest;
import com.chatroom.dto.ExitChatroomRequest;
import com.chatroom.dto.JoinChatroomRequest;
import com.chatroom.model.Chatroom;
import com.chatroom.model.Message;
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

    // GET /api/chatrooms/{roomId}
    @GetMapping("/{roomId}")
    public ResponseEntity<Chatroom> getChatroomByRoomId(@PathVariable String roomId) {
        return ResponseEntity.ok(chatroomService.getChatroomByRoomId(roomId));
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
