package com.chatroom.repository;

import com.chatroom.model.Chatroom;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.stream.Collectors;

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
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .item(toAttributeMap(chatroom))
                .conditionExpression("attribute_not_exists(roomId)")
                .build());

        // Record creator in the reverse index so they see this room in their list
        addRoomToUserIndex(chatroom.getCreatedBy(), chatroom.getRoomId());
    }

    /** Returns the chatroom, or empty if not found. */
    public Optional<Chatroom> findByRoomId(String roomId) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .key(Map.of("roomId", AttributeValue.fromS(roomId)))
                .build());

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
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .key(Map.of("roomId", AttributeValue.fromS(roomId)))
                .updateExpression("SET members = list_append(if_not_exists(members, :empty), :newUser)")
                .expressionAttributeValues(Map.of(
                        ":newUser", AttributeValue.fromL(List.of(AttributeValue.fromS(username))),
                        ":empty",   AttributeValue.fromL(Collections.emptyList())
                ))
                .build());

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

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(TABLE_CHATROOMS)
                .item(toAttributeMap(chatroom))
                .build());

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

        BatchGetItemResponse batchResponse = dynamoDbClient.batchGetItem(
                BatchGetItemRequest.builder()
                        .requestItems(Map.of(
                                TABLE_CHATROOMS, KeysAndAttributes.builder().keys(keys).build()
                        ))
                        .build());

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

        return Map.of(
                "roomId",    AttributeValue.fromS(c.getRoomId()),
                "roomName",  AttributeValue.fromS(c.getRoomName()),
                "isPrivate", AttributeValue.fromBool(c.isPrivate()),
                "createdBy", AttributeValue.fromS(c.getCreatedBy()),
                "createdAt", AttributeValue.fromS(c.getCreatedAt()),
                "members",   AttributeValue.fromL(memberAttrs)
        );
    }

    private Chatroom fromAttributeMap(Map<String, AttributeValue> item) {
        List<String> members = item.containsKey("members")
                ? item.get("members").l().stream()
                        .map(AttributeValue::s)
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return new Chatroom(
                item.get("roomId").s(),
                item.get("roomName").s(),
                item.containsKey("isPrivate") && item.get("isPrivate").bool(),
                item.get("createdBy").s(),
                item.get("createdAt").s(),
                members
        );
    }
}
