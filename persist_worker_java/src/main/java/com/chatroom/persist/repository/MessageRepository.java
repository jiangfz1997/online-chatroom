package com.chatroom.persist.repository;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/**
 * Writes chat messages to the DynamoDB "Messages" table.
 * Schema: PK=room_id (S), SK=seq (N), message_id (S), sender (S), text (S), timestamp (S).
 *
 * SK is the per-room seq assigned by the Redis Lua script at consume time (see
 * RedisMessageService), not the client-received timestamp. seq is centrally assigned in
 * Kafka-consumption order, so it stays correctly ordered even when a room's members are
 * spread across ws-server instances; timestamp is stamped per-instance before the message
 * even reaches Kafka, so two instances' clocks (or just submission jitter) can disagree
 * with the true arrival order. timestamp is kept as a plain attribute for display, not as
 * part of the key.
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

    /**
     * Writes the message, refusing to overwrite a different message already stored under the
     * same (room_id, seq). A retry of the same message (same message_id) still succeeds, so the
     * at-least-once persist loop stays idempotent. A different message_id at that seq means the
     * seq counter was reused (e.g. Redis lost it and restarted from 1) — that must never
     * silently replace existing history.
     *
     * @throws SeqConflictException if another message already occupies this seq
     */
    public void save(RawMessage msg) {
        Map<String, AttributeValue> item = Map.of(
                "room_id",    AttributeValue.fromS(msg.getRoomId()),
                "seq",        AttributeValue.fromN(String.valueOf(msg.getSeq())),
                "message_id", AttributeValue.fromS(msg.getId()),
                "sender",     AttributeValue.fromS(msg.getSender()),
                "text",       AttributeValue.fromS(msg.getText()),
                "timestamp",  AttributeValue.fromS(msg.getTimestamp())
        );
        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE)
                .item(item)
                .conditionExpression("attribute_not_exists(seq) OR message_id = :mid")
                .expressionAttributeValues(Map.of(":mid", AttributeValue.fromS(msg.getId())))
                .build();
        try {
            metrics.recordDynamoWrite(() -> dynamo.putItem(request));
        } catch (ConditionalCheckFailedException e) {
            throw new SeqConflictException(msg.getRoomId(), msg.getSeq(), msg.getId());
        }
        log.info("Saved message: room=[{}] sender=[{}] ts=[{}] id=[{}]",
                msg.getRoomId(), msg.getSender(), msg.getTimestamp(), msg.getId());
    }
}
