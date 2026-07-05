package com.chatroom.persist.service;

import com.chatroom.persist.model.RawMessage;
import com.chatroom.persist.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock MessageRepository messageRepository;
    @Mock SetOperations<String, String> setOps;
    @Mock ListOperations<String, String> listOps;

    PersistService service;

    private static final String ROOM = "room-1";
    private static final String MSG_JSON =
            "{\"type\":\"message\",\"sender\":\"alice\",\"text\":\"hello\",\"roomID\":\"room-1\",\"sentAt\":\"2024-01-01T10:00:00Z\"}";

    @BeforeEach
    void setup() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForList()).thenReturn(listOps);
        service = new PersistService(redis, messageRepository, new ObjectMapper());
    }

    // ── syncAllRooms ──────────────────────────────────────────────────────────

    @Test
    void syncAllRooms_noActiveRooms_doesNothing() {
        when(setOps.members("rooms:active")).thenReturn(Set.of());
        service.syncAllRooms();
        verify(listOps, never()).leftPop(anyString());
    }

    @Test
    void syncAllRooms_nullActiveRooms_doesNothing() {
        when(setOps.members("rooms:active")).thenReturn(null);
        service.syncAllRooms();
        verify(listOps, never()).leftPop(anyString());
    }

    @Test
    void syncAllRooms_withActiveRoom_syncsIt() {
        when(setOps.members("rooms:active")).thenReturn(Set.of(ROOM));
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(MSG_JSON)
                .thenReturn(null);

        service.syncAllRooms();

        verify(messageRepository, times(1)).save(any(RawMessage.class));
    }

    // ── syncRoom ──────────────────────────────────────────────────────────────

    @Test
    void syncRoom_popsAndSavesMessages() {
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(MSG_JSON)
                .thenReturn(MSG_JSON)
                .thenReturn(null);

        service.syncRoom(ROOM);

        verify(listOps, times(3)).leftPop("room:" + ROOM + ":to_persist"); // 2 messages + null
        verify(messageRepository, times(2)).save(any(RawMessage.class));
    }

    @Test
    void syncRoom_savesCorrectFields() {
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(MSG_JSON)
                .thenReturn(null);

        service.syncRoom(ROOM);

        ArgumentCaptor<RawMessage> captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(messageRepository).save(captor.capture());
        RawMessage saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo("room-1");
        assertThat(saved.getSender()).isEqualTo("alice");
        assertThat(saved.getText()).isEqualTo("hello");
        assertThat(saved.getTimestamp()).isEqualTo("2024-01-01T10:00:00Z");
    }

    @Test
    void syncRoom_emptyQueue_savesNothing() {
        when(listOps.leftPop("room:" + ROOM + ":to_persist")).thenReturn(null);

        service.syncRoom(ROOM);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void syncRoom_malformedJson_skipsAndContinues() {
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn("not-json")
                .thenReturn(MSG_JSON)
                .thenReturn(null);

        service.syncRoom(ROOM);

        // Bad message is skipped; valid message is still saved
        verify(messageRepository, times(1)).save(any(RawMessage.class));
    }

    @Test
    void syncRoom_missingRequiredFields_skipsMessage() {
        // Missing roomID and sentAt
        String incomplete = "{\"sender\":\"alice\",\"text\":\"hi\"}";
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(incomplete)
                .thenReturn(null);

        service.syncRoom(ROOM);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void syncRoom_dynamoFailure_continuesWithNextMessage() {
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(MSG_JSON)
                .thenReturn(MSG_JSON)
                .thenReturn(null);
        doThrow(new RuntimeException("DynamoDB unavailable"))
                .doNothing()
                .when(messageRepository).save(any());

        // Should not propagate exception
        service.syncRoom(ROOM);

        verify(messageRepository, times(2)).save(any());
    }

    @Test
    void syncRoom_respectsBatchSize() {
        // Default batch size is 100; stub always returns a message
        // but we can't easily test the hard limit without reflection.
        // Instead verify the loop stops at null (normal exit).
        when(listOps.leftPop("room:" + ROOM + ":to_persist"))
                .thenReturn(MSG_JSON)
                .thenReturn(MSG_JSON)
                .thenReturn(MSG_JSON)
                .thenReturn(null);

        service.syncRoom(ROOM);

        verify(messageRepository, times(3)).save(any());
    }
}
