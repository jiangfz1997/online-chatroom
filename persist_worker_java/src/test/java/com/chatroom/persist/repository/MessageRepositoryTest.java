package com.chatroom.persist.repository;

import com.chatroom.persist.model.RawMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    @InjectMocks MessageRepository repository;

    @Test
    void save_callsPutItemWithCorrectAttributes() {
        RawMessage msg = new RawMessage();
        msg.setId("msg-1");
        msg.setRoomId("room-1");
        msg.setTimestamp("2024-01-01T10:00:00Z");
        msg.setSender("alice");
        msg.setText("hello");

        repository.save(msg);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());

        PutItemRequest req = captor.getValue();
        assertThat(req.tableName()).isEqualTo("Messages");
        assertThat(req.item().get("room_id")).isEqualTo(AttributeValue.fromS("room-1"));
        // SK is "{timestamp}#{id}" so two messages in the same room can never collide on SK
        assertThat(req.item().get("timestamp")).isEqualTo(AttributeValue.fromS("2024-01-01T10:00:00Z#msg-1"));
        assertThat(req.item().get("sender")).isEqualTo(AttributeValue.fromS("alice"));
        assertThat(req.item().get("text")).isEqualTo(AttributeValue.fromS("hello"));
    }

    @Test
    void save_sameTimestampDifferentId_producesDistinctSortKeys() {
        RawMessage first = new RawMessage();
        first.setId("id-a"); first.setRoomId("room-1"); first.setTimestamp("2024-01-01T10:00:00Z");
        first.setSender("alice"); first.setText("first");

        RawMessage second = new RawMessage();
        second.setId("id-b"); second.setRoomId("room-1"); second.setTimestamp("2024-01-01T10:00:00Z");
        second.setSender("bob"); second.setText("second");

        repository.save(first);
        repository.save(second);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo, org.mockito.Mockito.times(2)).putItem(captor.capture());

        var sortKeys = captor.getAllValues().stream()
                .map(req -> req.item().get("timestamp").s())
                .toList();
        assertThat(sortKeys).containsExactly(
                "2024-01-01T10:00:00Z#id-a",
                "2024-01-01T10:00:00Z#id-b");
    }

    @Test
    void save_usesCorrectTableName() {
        RawMessage msg = new RawMessage();
        msg.setId("id"); msg.setRoomId("r");
        msg.setTimestamp("t");
        msg.setSender("s");
        msg.setText("x");

        repository.save(msg);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("Messages");
    }

    @Test
    void save_dynamoException_propagates() {
        org.mockito.Mockito.doThrow(new RuntimeException("DynamoDB error"))
                .when(dynamo).putItem(any(PutItemRequest.class));

        RawMessage msg = new RawMessage();
        msg.setId("id"); msg.setRoomId("r"); msg.setTimestamp("t"); msg.setSender("s"); msg.setText("x");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> repository.save(msg));
    }
}
