package com.chatroom.repository;

import com.chatroom.model.HistoryMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads messages from the DynamoDB "Messages" table.
 * Schema: PK=room_id (S), SK=timestamp (S), sender (S), text (S).
 * Mirrors Go's getMessagesFromDynamo in client.go.
 *
 * SK is written as "{timestamp}#{id}" (see persist-worker's MessageRepository) to keep it
 * unique across messages that land in the same millisecond. fromMap() strips the "#{id}"
 * suffix back off before handing the timestamp to callers/frontend; rows written before
 * this change have no suffix and pass through unchanged.
 */
@Slf4j
@Repository
public class MessageRepository {

    private static final String TABLE = "Messages";

    private final DynamoDbClient dynamo;

    public MessageRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    /**
     * Returns up to {@code limit} messages with timestamp < before, newest-first.
     * Uses #ts alias because "timestamp" is a DynamoDB reserved word.
     */
    public List<HistoryMessage> getMessagesBefore(String roomId, String before, int limit) {
        log.info("Fetching messages from DynamoDB | room={} before={} limit={}", roomId, before, limit);
        try {
            QueryResponse response = dynamo.query(QueryRequest.builder()
                    .tableName(TABLE)
                    .keyConditionExpression("room_id = :rid AND #ts < :before")
                    .expressionAttributeNames(Map.of("#ts", "timestamp"))
                    .expressionAttributeValues(Map.of(
                            ":rid",    AttributeValue.fromS(roomId),
                            ":before", AttributeValue.fromS(before)))
                    .limit(limit)
                    .scanIndexForward(false)
                    .build());

            return response.items().stream()
                    .map(this::fromMap)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("DynamoDB query failed for room [{}]: {}", roomId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private HistoryMessage fromMap(Map<String, AttributeValue> item) {
        String sortKey = item.get("timestamp").s();
        int idSeparator = sortKey.indexOf('#');
        String timestamp = idSeparator >= 0 ? sortKey.substring(0, idSeparator) : sortKey;

        return new HistoryMessage(
                item.get("room_id").s(),
                timestamp,
                item.get("sender").s(),
                item.get("text").s()
        );
    }

    /**
     * Check if a user is in a room by reading the "members" list from Chatrooms table.
     * Mirrors Go auth.IsUserInRoom which reads "users" field from "chatrooms" table.
     * Java api-server writes to "Chatrooms" with field "members".
     */
    public boolean isUserInRoom(String roomId, String username) {
        log.info("Checking room membership: user=[{}] room=[{}]", username, roomId);
        try {
            GetItemRequest request = GetItemRequest.builder()
                    .tableName("Chatrooms")
                    .key(Map.of("roomId", AttributeValue.fromS(roomId)))
                    .projectionExpression("#m")
                    .expressionAttributeNames(Map.of("#m", "members"))
                    .build();
            var response = dynamo.getItem(request);

            if (!response.hasItem() || response.item().isEmpty()) {
                log.warn("Room [{}] not found in Chatrooms table", roomId);
                return false;
            }

            AttributeValue membersAttr = response.item().get("members");
            if (membersAttr == null || membersAttr.l() == null) return false;

            boolean found = membersAttr.l().stream()
                    .anyMatch(av -> username.equals(av.s()));
            log.info("User [{}] in room [{}]: {}", username, roomId, found);
            return found;
        } catch (Exception e) {
            log.error("DynamoDB getItem failed for room [{}]: {}", roomId, e.getMessage());
            return false;
        }
    }
}
