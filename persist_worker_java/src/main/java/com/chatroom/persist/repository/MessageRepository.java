package com.chatroom.persist.repository;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/**
 * Writes chat messages to the DynamoDB "Messages" table.
 * Schema: PK=room_id (S), SK=timestamp (S), sender (S), text (S).
 */
@Slf4j
@Repository
public class MessageRepository {

    private static final String TABLE = "Messages";

    private final DynamoDbClient dynamo;
    private final PersistMetrics metrics;

    public MessageRepository(DynamoDbClient dynamo, PersistMetrics metrics) {
        this.dynamo = dynamo;
        this.metrics = metrics;
    }

    public void save(RawMessage msg) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        "room_id",   AttributeValue.fromS(msg.getRoomId()),
                        "timestamp", AttributeValue.fromS(msg.getTimestamp()),
                        "sender",    AttributeValue.fromS(msg.getSender()),
                        "text",      AttributeValue.fromS(msg.getText())
                ))
                .build();
        metrics.recordDynamoWrite(() -> dynamo.putItem(request));
        log.info("Saved message: room=[{}] sender=[{}] ts=[{}]",
                msg.getRoomId(), msg.getSender(), msg.getTimestamp());
    }
}
