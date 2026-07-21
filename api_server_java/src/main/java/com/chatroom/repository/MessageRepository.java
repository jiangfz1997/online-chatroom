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
     * Returns up to {@code limit} messages in a room with timestamp < before,
     * ordered newest-first (ScanIndexForward=false matches Go version).
     */
    public List<Message> getMessagesBefore(String roomId, String before, int limit) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(TABLE)
                // #ts avoids collision with the reserved word "timestamp"
                .keyConditionExpression("room_id = :rid AND #ts < :before")
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":rid",    AttributeValue.fromS(roomId),
                        ":before", AttributeValue.fromS(before)
                ))
                .limit(limit)
                .scanIndexForward(false)
                .build());

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
                item.get("text").s()
        );
    }
}
