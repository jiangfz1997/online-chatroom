package com.chatroom.persist.repository;

import com.chatroom.persist.metrics.PersistMetrics;
import com.chatroom.persist.model.RawMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes chat messages to the DynamoDB "Messages" table.
 * Schema: PK=room_id (S), SK=timestamp (S), sender (S), text (S).
 *
 * SK is stored as "{timestamp}#{id}" rather than a bare timestamp: two distinct messages
 * can legitimately share the same millisecond, and a bare-timestamp SK would silently
 * overwrite the earlier one on PutItem. The id suffix guarantees SK uniqueness while the
 * timestamp prefix keeps range queries (getMessagesBefore) ordered exactly as before.
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
        String sortKey = msg.getTimestamp() + "#" + msg.getId();
        Map<String, AttributeValue> item = new HashMap<>(Map.of(
                "room_id",   AttributeValue.fromS(msg.getRoomId()),
                "timestamp", AttributeValue.fromS(sortKey),
                "sender",    AttributeValue.fromS(msg.getSender()),
                "text",      AttributeValue.fromS(msg.getText())
        ));
        // Not indexed/queried — carried through only so the persisted row records what seq
        // it had at write time (see RawMessage.seq); absent for pre-P3 messages.
        if (msg.getSeq() != null) {
            item.put("seq", AttributeValue.fromN(String.valueOf(msg.getSeq())));
        }
        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE)
                .item(item)
                .build();
        metrics.recordDynamoWrite(() -> dynamo.putItem(request));
        log.info("Saved message: room=[{}] sender=[{}] ts=[{}] id=[{}]",
                msg.getRoomId(), msg.getSender(), msg.getTimestamp(), msg.getId());
    }
}
