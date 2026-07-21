package com.chatroom.service;

import com.chatroom.dto.CreateChatroomRequest;
import com.chatroom.exception.NotFoundException;
import com.chatroom.model.Chatroom;
import com.chatroom.repository.ChatroomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatroomServiceTest {

    @Mock
    private ChatroomRepository chatroomRepository;

    @InjectMocks
    private ChatroomService chatroomService;

    // ── createChatroom ────────────────────────────────────────────────────────

    @Test
    void createChatroom_success() {
        CreateChatroomRequest req = new CreateChatroomRequest();
        req.setName("general");
        req.setPrivate(false);

        Chatroom result = chatroomService.createChatroom("alice", req);

        // returned chatroom has correct fields
        assertThat(result.getRoomName()).isEqualTo("general");
        assertThat(result.getCreatedBy()).isEqualTo("alice");
        assertThat(result.isPrivate()).isFalse();
        assertThat(result.getRoomId()).isNotBlank();
        assertThat(result.getCreatedAt()).isNotBlank();
        assertThat(result.getMembers()).containsExactly("alice");

        // repository was called once with that chatroom
        ArgumentCaptor<Chatroom> captor = ArgumentCaptor.forClass(Chatroom.class);
        verify(chatroomRepository, times(1)).createChatroom(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("alice");
    }

    // ── joinChatroom ──────────────────────────────────────────────────────────

    @Test
    void joinChatroom_success() {
        Chatroom existing = chatroom("room-1", List.of("alice"));
        when(chatroomRepository.findByRoomId("room-1")).thenReturn(Optional.of(existing));

        chatroomService.joinChatroom("bob", "room-1");

        verify(chatroomRepository, times(1)).addUserToRoom("room-1", "bob");
    }

    @Test
    void joinChatroom_roomNotFound_throwsNotFound() {
        when(chatroomRepository.findByRoomId("no-such-room")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatroomService.joinChatroom("bob", "no-such-room"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Chatroom not found");
    }

    @Test
    void joinChatroom_alreadyMember_throwsIllegalArgument() {
        Chatroom existing = chatroom("room-1", List.of("alice", "bob"));
        when(chatroomRepository.findByRoomId("room-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> chatroomService.joinChatroom("bob", "room-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User already in chatroom");

        verify(chatroomRepository, never()).addUserToRoom(any(), any());
    }

    // ── exitChatroom ──────────────────────────────────────────────────────────

    @Test
    void exitChatroom_success() {
        Chatroom existing = chatroom("room-1", List.of("alice", "bob"));
        when(chatroomRepository.findByRoomId("room-1")).thenReturn(Optional.of(existing));

        chatroomService.exitChatroom("bob", "room-1");

        verify(chatroomRepository, times(1)).removeUserFromRoom("room-1", "bob");
    }

    @Test
    void exitChatroom_roomNotFound_throwsNotFound() {
        when(chatroomRepository.findByRoomId("no-such-room")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatroomService.exitChatroom("bob", "no-such-room"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Chatroom not found");
    }

    // ── getUserChatrooms ──────────────────────────────────────────────────────

    @Test
    void getUserChatrooms_returnsList() {
        List<Chatroom> rooms = List.of(
                chatroom("room-1", List.of("alice")),
                chatroom("room-2", List.of("alice", "bob"))
        );
        when(chatroomRepository.findByUsername("alice")).thenReturn(rooms);

        List<Chatroom> result = chatroomService.getUserChatrooms("alice");

        assertThat(result).hasSize(2);
        verify(chatroomRepository, times(1)).findByUsername("alice");
    }

    // ── getChatroomByRoomId ───────────────────────────────────────────────────

    @Test
    void getChatroomByRoomId_success() {
        Chatroom existing = chatroom("room-1", List.of("alice"));
        when(chatroomRepository.findByRoomId("room-1")).thenReturn(Optional.of(existing));

        Chatroom result = chatroomService.getChatroomByRoomId("room-1");

        assertThat(result.getRoomId()).isEqualTo("room-1");
    }

    @Test
    void getChatroomByRoomId_notFound_throwsNotFound() {
        when(chatroomRepository.findByRoomId("no-such-room")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatroomService.getChatroomByRoomId("no-such-room"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Chatroom not found");
    }

    // ── test helper ───────────────────────────────────────────────────────────

    private Chatroom chatroom(String roomId, List<String> members) {
        return new Chatroom(roomId, "Test Room", false, "alice", "2024-01-01T00:00:00Z",
                new ArrayList<>(members));
    }
}
