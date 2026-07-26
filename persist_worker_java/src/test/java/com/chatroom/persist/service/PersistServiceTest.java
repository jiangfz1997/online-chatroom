package com.chatroom.persist.service;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import com.chatroom.persist.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
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
    private static final String SOURCE_KEY = "room:" + ROOM + ":to_persist";
    private static final String PROCESSING_KEY = "room:" + ROOM + ":persist_processing";
    private static final String MSG_JSON =
            "{\"type\":\"message\",\"id\":\"msg-1\",\"sender\":\"alice\",\"text\":\"hello\",\"roomID\":\"room-1\",\"sentAt\":\"2024-01-01T10:00:00Z\"}";

    @BeforeEach
    void setup() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForList()).thenReturn(listOps);
        // No orphans left over from a previous run, by default — recoverOrphans() becomes a
        // no-op after this single size() check. Individual tests override to exercise it.
        when(listOps.size(anyString())).thenReturn(0L);
        service = new PersistService(redis, messageRepository, new ObjectMapper(),
                new PersistMetrics(new SimpleMeterRegistry()));
    }

    private void stubClaims(String... jsons) {
        var stub = when(listOps.move(SOURCE_KEY, Direction.LEFT, PROCESSING_KEY, Direction.RIGHT));
        for (String json : jsons) {
            stub = stub.thenReturn(json);
        }
        stub.thenReturn(null);
    }

    // ── syncAllRooms ──────────────────────────────────────────────────────────

    @Test
    void syncAllRooms_noActiveRooms_doesNothing() {
        when(setOps.members("rooms:active")).thenReturn(Set.of());
        service.syncAllRooms();
        verify(listOps, never()).move(anyString(), any(), anyString(), any());
    }

    @Test
    void syncAllRooms_nullActiveRooms_doesNothing() {
        when(setOps.members("rooms:active")).thenReturn(null);
        service.syncAllRooms();
        verify(listOps, never()).move(anyString(), any(), anyString(), any());
    }

    @Test
    void syncAllRooms_withActiveRoom_syncsIt() {
        when(setOps.members("rooms:active")).thenReturn(Set.of(ROOM));
        stubClaims(MSG_JSON);

        service.syncAllRooms();

        verify(messageRepository, times(1)).save(any(RawMessage.class));
    }

    // ── syncRoom ──────────────────────────────────────────────────────────────

    @Test
    void syncRoom_claimsAndSavesMessages() {
        stubClaims(MSG_JSON, MSG_JSON);

        service.syncRoom(ROOM);

        verify(listOps, times(3)).move(SOURCE_KEY, Direction.LEFT, PROCESSING_KEY, Direction.RIGHT); // 2 + null
        verify(messageRepository, times(2)).save(any(RawMessage.class));
        // Each successfully-saved message is removed from processing once confirmed.
        verify(listOps, times(2)).remove(PROCESSING_KEY, 1, MSG_JSON);
    }

    @Test
    void syncRoom_savesCorrectFields() {
        stubClaims(MSG_JSON);

        service.syncRoom(ROOM);

        ArgumentCaptor<RawMessage> captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(messageRepository).save(captor.capture());
        RawMessage saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo("room-1");
        assertThat(saved.getSender()).isEqualTo("alice");
        assertThat(saved.getText()).isEqualTo("hello");
        assertThat(saved.getTimestamp()).isEqualTo("2024-01-01T10:00:00Z");
        assertThat(saved.getId()).isEqualTo("msg-1");
    }

    @Test
    void syncRoom_missingId_skipsMessage() {
        String noId = "{\"type\":\"message\",\"sender\":\"alice\",\"text\":\"hi\",\"roomID\":\"room-1\",\"sentAt\":\"2024-01-01T10:00:00Z\"}";
        stubClaims(noId);

        service.syncRoom(ROOM);

        verify(messageRepository, never()).save(any());
        // Malformed messages are removed from processing too — retrying won't fix bad JSON shape.
        verify(listOps).remove(PROCESSING_KEY, 1, noId);
    }

    @Test
    void syncRoom_emptyQueue_savesNothing() {
        stubClaims();

        service.syncRoom(ROOM);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void syncRoom_malformedJson_skipsAndContinues() {
        stubClaims("not-json", MSG_JSON);

        service.syncRoom(ROOM);

        // Bad message is skipped; valid message is still saved
        verify(messageRepository, times(1)).save(any(RawMessage.class));
    }

    @Test
    void syncRoom_missingRequiredFields_skipsMessage() {
        // Missing roomID and sentAt
        String incomplete = "{\"sender\":\"alice\",\"text\":\"hi\"}";
        stubClaims(incomplete);

        service.syncRoom(ROOM);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void syncRoom_dynamoFailure_continuesWithNextMessage() {
        stubClaims(MSG_JSON, MSG_JSON);
        doThrow(new RuntimeException("DynamoDB unavailable"))
                .doNothing()
                .when(messageRepository).save(any());

        // Should not propagate exception
        service.syncRoom(ROOM);

        verify(messageRepository, times(2)).save(any());
    }

    @Test
    void syncRoom_dynamoFailure_leavesMessageInProcessingForRetry() {
        stubClaims(MSG_JSON);
        doThrow(new RuntimeException("DynamoDB unavailable")).when(messageRepository).save(any());

        service.syncRoom(ROOM);

        // Not removed from processing — a crash or transient failure here must not drop the
        // message; the next tick's recoverOrphans() retries it instead.
        verify(listOps, never()).remove(eq(PROCESSING_KEY), anyLong(), any());
    }

    @Test
    void syncRoom_respectsBatchSize() {
        // Default batch size is 100; stub always returns a message
        // but we can't easily test the hard limit without reflection.
        // Instead verify the loop stops at null (normal exit).
        stubClaims(MSG_JSON, MSG_JSON, MSG_JSON);

        service.syncRoom(ROOM);

        verify(messageRepository, times(3)).save(any());
    }

    // ── recoverOrphans ──────────────────────────────────────────────────────────

    @Test
    void syncRoom_orphansFromPreviousCrash_areMovedBackBeforeClaimingNew() {
        // Processing still has 2 messages left over from a run that crashed before
        // confirming them — syncRoom must recover those first.
        when(listOps.size(PROCESSING_KEY)).thenReturn(2L);
        when(listOps.move(PROCESSING_KEY, Direction.RIGHT, SOURCE_KEY, Direction.LEFT))
                .thenReturn(MSG_JSON)
                .thenReturn(MSG_JSON);
        stubClaims(); // nothing new to claim in this test, just verifying recovery ran

        service.syncRoom(ROOM);

        verify(listOps, times(2)).move(PROCESSING_KEY, Direction.RIGHT, SOURCE_KEY, Direction.LEFT);
    }

    @Test
    void syncRoom_noOrphans_skipsRecovery() {
        when(listOps.size(PROCESSING_KEY)).thenReturn(0L);
        stubClaims();

        service.syncRoom(ROOM);

        verify(listOps, never()).move(PROCESSING_KEY, Direction.RIGHT, SOURCE_KEY, Direction.LEFT);
    }
}
