package com.chatroom.repository;

import com.chatroom.model.Chatroom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class ChatroomRepository {

    private static final String TABLE_CHATROOMS     = "Chatrooms";
    private static final String TABLE_USER_CHATROOMS = "UserChatrooms";

    private final DynamoDbClient dynamoDbClient;

    public ChatroomRepository(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Persists a new chatroom and records the creator in the reverse index.
     * Throws ConditionalCheckFailedException if roomId already exists.
     */
    public void createChatroom(Chatroom chatroom) {
        long t0 = System.nanoTime();
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .item(toAttributeMap(chatroom))
                .conditionExpression("attribute_not_exists(roomId)")
                .build());
        log.debug("DynamoDB putItem Chatrooms[{}] took {} ms", chatroom.getRoomId(), elapsedMs(t0));

        // Record creator in the reverse index so they see this room in their list
        addRoomToUserIndex(chatroom.getCreatedBy(), chatroom.getRoomId());
    }

    /** Returns the chatroom, or empty if not found. */
    public Optional<Chatroom> findByRoomId(String roomId) {
        long t0 = System.nanoTime();
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .key(Map.of("roomId", AttributeValue.fromS(roomId)))
                .build());
        log.debug("DynamoDB getItem Chatrooms[{}] took {} ms (found={})",
                roomId, elapsedMs(t0), response.hasItem());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromAttributeMap(response.item()));
    }

    /**
     * Adds a username to the chatroom's members list and updates the reverse index.
     * Uses list_append so concurrent joins don't overwrite each other.
     */
    public void addUserToRoom(String roomId, String username) {
        long t0 = System.nanoTime();
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .key(Map.of("roomId", AttributeValue.fromS(roomId)))
                .updateExpression("SET members = list_append(if_not_exists(members, :empty), :newUser)")
                .expressionAttributeValues(Map.of(
                        ":newUser", AttributeValue.fromL(List.of(AttributeValue.fromS(username))),
                        ":empty",   AttributeValue.fromL(Collections.emptyList())
                ))
                .build());
        log.debug("DynamoDB updateItem Chatrooms[{}] addUser took {} ms", roomId, elapsedMs(t0));

        addRoomToUserIndex(username, roomId);
    }

    /**
     * Removes a username from the chatroom's members list and updates the reverse index.
     * DynamoDB does not support removing list elements by value, so we read-then-write.
     */
    public void removeUserFromRoom(String roomId, String username) {
        Optional<Chatroom> opt = findByRoomId(roomId);
        if (opt.isEmpty()) {
            return;
        }

        Chatroom chatroom = opt.get();
        List<String> updated = chatroom.getMembers().stream()
                .filter(u -> !u.equals(username))
                .collect(Collectors.toList());
        chatroom.setMembers(updated);

        long t0 = System.nanoTime();
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .item(toAttributeMap(chatroom))
                .build());
        log.debug("DynamoDB putItem Chatrooms[{}] removeUser took {} ms", roomId, elapsedMs(t0));

        removeRoomFromUserIndex(username, roomId);
    }

    /**
     * Returns all chatrooms the user belongs to.
     * Looks up the reverse index (O(1)) then batch-fetches chatroom details.
     */
    public List<Chatroom> findByUsername(String username) {
        GetItemResponse indexResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_USER_CHATROOMS)
                .key(Map.of("username", AttributeValue.fromS(username)))
                .build());

        if (!indexResponse.hasItem() || !indexResponse.item().containsKey("roomIds")) {
            return Collections.emptyList();
        }

        Set<String> roomIds = indexResponse.item().get("roomIds").ss()
                .stream().collect(Collectors.toSet());

        if (roomIds.isEmpty()) {
            return Collections.emptyList();
        }

        // BatchGetItem fetches up to 100 items in one round-trip
        List<Map<String, AttributeValue>> keys = roomIds.stream()
                .map(id -> Map.of("roomId", AttributeValue.fromS(id)))
                .collect(Collectors.toList());

        long t0 = System.nanoTime();
        BatchGetItemResponse batchResponse = dynamoDbClient.batchGetItem(
                BatchGetItemRequest.builder()
                        .requestItems(Map.of(
                                TABLE_CHATROOMS, KeysAndAttributes.builder().keys(keys).build()
                        ))
                        .build());
        log.debug("DynamoDB batchGetItem Chatrooms x{} for user[{}] took {} ms",
                keys.size(), username, elapsedMs(t0));

        return batchResponse.responses()
                .getOrDefault(TABLE_CHATROOMS, Collections.emptyList())
                .stream()
                .map(this::fromAttributeMap)
                .collect(Collectors.toList());
    }

    // ── reverse index helpers ─────────────────────────────────────────────────

    /**
     * Adds a roomId to the user's set in UserChatrooms.
     * ADD on a StringSet is atomic and idempotent.
     */
    private void addRoomToUserIndex(String username, String roomId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_USER_CHATROOMS)
                .key(Map.of("username", AttributeValue.fromS(username)))
                .updateExpression("ADD roomIds :roomId")
                .expressionAttributeValues(Map.of(
                        ":roomId", AttributeValue.fromSs(List.of(roomId))
                ))
                .build());
    }

    /**
     * Removes a roomId from the user's set in UserChatrooms.
     * DELETE on a StringSet is atomic and idempotent.
     */
    private void removeRoomFromUserIndex(String username, String roomId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_USER_CHATROOMS)
                .key(Map.of("username", AttributeValue.fromS(username)))
                .updateExpression("DELETE roomIds :roomId")
                .expressionAttributeValues(Map.of(
                        ":roomId", AttributeValue.fromSs(List.of(roomId))
                ))
                .build());
    }

    // ── attribute map converters ──────────────────────────────────────────────

    private Map<String, AttributeValue> toAttributeMap(Chatroom c) {
        List<AttributeValue> memberAttrs = c.getMembers() == null
                ? Collections.emptyList()
                : c.getMembers().stream()
                        .map(AttributeValue::fromS)
                        .collect(Collectors.toList());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("roomId",    AttributeValue.fromS(c.getRoomId()));
        item.put("roomName",  AttributeValue.fromS(c.getRoomName()));
        item.put("isPrivate", AttributeValue.fromBool(c.isPrivate()));
        item.put("createdBy", AttributeValue.fromS(c.getCreatedBy()));
        item.put("createdAt", AttributeValue.fromS(c.getCreatedAt()));
        item.put("members",   AttributeValue.fromL(memberAttrs));
        if (c.getDescription() != null && !c.getDescription().isEmpty()) {
            item.put("description", AttributeValue.fromS(c.getDescription()));
        }
        return item;
    }

    private Chatroom fromAttributeMap(Map<String, AttributeValue> item) {
        List<String> members = item.containsKey("members")
                ? item.get("members").l().stream()
                        .map(AttributeValue::s)
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return Chatroom.builder()
                .roomId(item.get("roomId").s())
                .roomName(item.get("roomName").s())
                .description(item.containsKey("description") ? item.get("description").s() : null)
                .isPrivate(item.containsKey("isPrivate") && item.get("isPrivate").bool())
                .createdBy(item.get("createdBy").s())
                .createdAt(item.get("createdAt").s())
                .members(members)
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
