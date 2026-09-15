package com.chatroom.persist.repository;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageRepositoryTest {

    @Mock DynamoDbClient dynamo;
    MessageRepository repository;

    @BeforeEach
    void setup() {
        repository = new MessageRepository(dynamo, new PersistMetrics(new SimpleMeterRegistry()));
    }

    private RawMessage message(String id, String roomId, String timestamp, String sender, String text, long seq) {
        RawMessage msg = new RawMessage();
        msg.setId(id);
        msg.setRoomId(roomId);
        msg.setTimestamp(timestamp);
        msg.setSender(sender);
        msg.setText(text);
        msg.setSeq(seq);
        return msg;
    }

    @Test
    void save_callsPutItemWithCorrectAttributes() {
        RawMessage msg = message("msg-1", "room-1", "2024-01-01T10:00:00Z", "alice", "hello", 7L);

        repository.save(msg);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());

        PutItemRequest req = captor.getValue();
        assertThat(req.tableName()).isEqualTo("Messages");
        assertThat(req.item().get("room_id")).isEqualTo(AttributeValue.fromS("room-1"));
        // seq is the sort key now — a single-source, centrally-assigned counter, not a
        // client/instance timestamp — so it stays correctly ordered across ws-server instances.
        assertThat(req.item().get("seq")).isEqualTo(AttributeValue.fromN("7"));
        assertThat(req.item().get("timestamp")).isEqualTo(AttributeValue.fromS("2024-01-01T10:00:00Z"));
        assertThat(req.item().get("sender")).isEqualTo(AttributeValue.fromS("alice"));
        assertThat(req.item().get("text")).isEqualTo(AttributeValue.fromS("hello"));
    }

    @Test
    void save_sameTimestampDifferentSeq_producesDistinctSortKeys() {
        // Two messages landing in the same millisecond used to be disambiguated by appending
        // the message id to the timestamp SK. Now they're naturally distinct because seq
        // itself is unique per message — no suffix trick needed.
        RawMessage first = message("id-a", "room-1", "2024-01-01T10:00:00Z", "alice", "first", 1L);
        RawMessage second = message("id-b", "room-1", "2024-01-01T10:00:00Z", "bob", "second", 2L);

        repository.save(first);
        repository.save(second);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo, org.mockito.Mockito.times(2)).putItem(captor.capture());

        var seqs = captor.getAllValues().stream()
                .map(req -> req.item().get("seq"))
                .toList();
        assertThat(seqs).containsExactly(AttributeValue.fromN("1"), AttributeValue.fromN("2"));
    }

    @Test
    void save_usesCorrectTableName() {
        RawMessage msg = message("id", "r", "t", "s", "x", 1L);

        repository.save(msg);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("Messages");
    }

    @Test
    void save_dynamoException_propagates() {
        org.mockito.Mockito.doThrow(new RuntimeException("DynamoDB error"))
                .when(dynamo).putItem(any(PutItemRequest.class));

        RawMessage msg = message("id", "r", "t", "s", "x", 1L);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> repository.save(msg));
    }
}
