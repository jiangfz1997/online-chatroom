package com.chatroom.repository;

import com.chatroom.model.Message;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class MessageRepository {

    private static final String TABLE = "Messages";

    private final DynamoDbClient dynamoDbClient;

    public MessageRepository(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Returns up to {@code limit} messages in a room with seq < before, newest-first
     * (ScanIndexForward=false). seq — not the client-received timestamp — is the sort key:
     * it's assigned centrally at consume time in true Kafka-consumption order, so it stays
     * correctly ordered even when a room's members are spread across ws-server instances,
     * each with its own clock. A null before means "start from the newest message".
     */
    public List<Message> getMessagesBefore(String roomId, Long before, int limit) {
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(TABLE)
                .limit(limit)
                .scanIndexForward(false);

        if (before != null) {
            query.keyConditionExpression("room_id = :rid AND seq < :before")
                    .expressionAttributeValues(Map.of(
                            ":rid",    AttributeValue.fromS(roomId),
                            ":before", AttributeValue.fromN(String.valueOf(before))
                    ));
        } else {
            query.keyConditionExpression("room_id = :rid")
                    .expressionAttributeValues(Map.of(":rid", AttributeValue.fromS(roomId)));
        }

        QueryResponse response = dynamoDbClient.query(query.build());

        if (response.items().isEmpty()) {
            return Collections.emptyList();
        }

        return response.items().stream()
                .map(this::fromAttributeMap)
                .collect(Collectors.toList());
    }

    private Message fromAttributeMap(Map<String, AttributeValue> item) {
        return new Message(
                item.get("room_id").s(),
                item.get("timestamp").s(),
                item.get("sender").s(),
                item.get("text").s(),
                Long.parseLong(item.get("seq").n())
        );
    }
}
