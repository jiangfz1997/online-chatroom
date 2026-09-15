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
 * Schema: PK=room_id (S), SK=seq (N), sender (S), text (S), timestamp (S).
 *
 * SK is the per-room seq assigned atomically at consume time (see RedisMessageService), not
 * the client-received timestamp — seq reflects true Kafka-consumption order, so history stays
 * correctly ordered even when a room's members are spread across ws-server instances, where
 * each instance's own clock (or just submission jitter) can disagree with arrival order.
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
     * Returns up to {@code limit} messages with seq < beforeSeq, newest-first. A null
     * beforeSeq means "start from the newest message" (no upper bound on the key condition).
     */
    public List<HistoryMessage> getMessagesBefore(String roomId, Long beforeSeq, int limit) {
        log.info("Fetching messages from DynamoDB | room={} beforeSeq={} limit={}", roomId, beforeSeq, limit);
        try {
            QueryRequest.Builder query = QueryRequest.builder()
                    .tableName(TABLE)
                    .limit(limit)
                    .scanIndexForward(false);

            if (beforeSeq != null) {
                query.keyConditionExpression("room_id = :rid AND seq < :before")
                        .expressionAttributeValues(Map.of(
                                ":rid",    AttributeValue.fromS(roomId),
                                ":before", AttributeValue.fromN(String.valueOf(beforeSeq))));
            } else {
                query.keyConditionExpression("room_id = :rid")
                        .expressionAttributeValues(Map.of(":rid", AttributeValue.fromS(roomId)));
            }

            QueryResponse response = dynamo.query(query.build());

            return response.items().stream()
                    .map(this::fromMap)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("DynamoDB query failed for room [{}]: {}", roomId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private HistoryMessage fromMap(Map<String, AttributeValue> item) {
        return new HistoryMessage(
                item.get("room_id").s(),
                item.get("timestamp").s(),
                item.get("sender").s(),
                item.get("text").s(),
                Long.parseLong(item.get("seq").n())
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
