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

    @Test
    void save_callsPutItemWithCorrectAttributes() {
        RawMessage msg = new RawMessage();
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
        assertThat(req.item().get("timestamp")).isEqualTo(AttributeValue.fromS("2024-01-01T10:00:00Z"));
        assertThat(req.item().get("sender")).isEqualTo(AttributeValue.fromS("alice"));
        assertThat(req.item().get("text")).isEqualTo(AttributeValue.fromS("hello"));
    }

    @Test
    void save_usesCorrectTableName() {
        RawMessage msg = new RawMessage();
        msg.setRoomId("r");
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
        msg.setRoomId("r"); msg.setTimestamp("t"); msg.setSender("s"); msg.setText("x");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> repository.save(msg));
    }
}
