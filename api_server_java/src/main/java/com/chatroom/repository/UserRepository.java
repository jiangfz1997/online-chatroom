package com.chatroom.repository;

import com.chatroom.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class UserRepository {
    private static final String TABLE_NAME = "Users";

    private final DynamoDbClient dynamoDbClient;

    public UserRepository(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public void createUser(User user){
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("username",    AttributeValue.fromS(user.getUsername()));
        item.put("password",    AttributeValue.fromS(user.getPassword()));
        putIfPresent(item, "displayName", user.getDisplayName());
        putIfPresent(item, "avatarSeed",  user.getAvatarSeed());
        putIfPresent(item, "bio",         user.getBio());
        putIfPresent(item, "createdAt",   user.getCreatedAt());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                // Reject the write if this username already exists
                .conditionExpression("attribute_not_exists(username)")
                .build();

        long t0 = System.nanoTime();
        dynamoDbClient.putItem(request);
        log.debug("DynamoDB putItem Users[{}] took {} ms", user.getUsername(), elapsedMs(t0));
    }

    public Optional<User> findByUsername(String username) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "username", AttributeValue.fromS(username)
                ))
                .build();

        long t0 = System.nanoTime();
        GetItemResponse response = dynamoDbClient.getItem(request);
        log.debug("DynamoDB getItem Users[{}] took {} ms (found={})",
                username, elapsedMs(t0), response.hasItem());

        if (!response.hasItem()) {
            return Optional.empty();
        }

        return Optional.of(fromAttributeMap(response.item()));
    }

    /**
     * Batch-fetches profiles for a set of usernames (used by the chatroom
     * member list). Returns users with profile defaults applied on read.
     */
    public List<User> findProfiles(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, AttributeValue>> keys = usernames.stream()
                .distinct()
                .map(u -> Map.of("username", AttributeValue.fromS(u)))
                .collect(Collectors.toList());

        BatchGetItemRequest request = BatchGetItemRequest.builder()
                .requestItems(Map.of(
                        TABLE_NAME, KeysAndAttributes.builder().keys(keys).build()
                ))
                .build();

        long t0 = System.nanoTime();
        BatchGetItemResponse response = dynamoDbClient.batchGetItem(request);
        log.debug("DynamoDB batchGetItem Users x{} took {} ms", keys.size(), elapsedMs(t0));

        return response.responses()
                .getOrDefault(TABLE_NAME, Collections.emptyList())
                .stream()
                .map(this::fromAttributeMap)
                .collect(Collectors.toList());
    }

    /** Updates the editable profile fields; only non-null values are written. */
    public void updateProfile(String username, String displayName, String bio, String avatarSeed) {
        List<String> sets = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();

        addSet(sets, names, values, "displayName", displayName);
        addSet(sets, names, values, "bio", bio);
        addSet(sets, names, values, "avatarSeed", avatarSeed);

        if (sets.isEmpty()) {
            return;
        }

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("username", AttributeValue.fromS(username)))
                .updateExpression("SET " + String.join(", ", sets))
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();

        long t0 = System.nanoTime();
        dynamoDbClient.updateItem(request);
        log.debug("DynamoDB updateItem Users[{}] profile took {} ms", username, elapsedMs(t0));
    }

    /** Rewrites the stored password hash (used by the lazy plaintext→bcrypt upgrade on login). */
    public void updatePassword(String username, String newPasswordHash) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("username", AttributeValue.fromS(username)))
                .updateExpression("SET password = :pw")
                .expressionAttributeValues(Map.of(":pw", AttributeValue.fromS(newPasswordHash)))
                .build();

        long t0 = System.nanoTime();
        dynamoDbClient.updateItem(request);
        log.debug("DynamoDB updateItem Users[{}] password took {} ms", username, elapsedMs(t0));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps a DynamoDB item to a User, filling profile defaults for records that
     * predate the profile fields (displayName/avatarSeed fall back to username).
     */
    private User fromAttributeMap(Map<String, AttributeValue> item) {
        String username = item.get("username").s();
        return User.builder()
                .username(username)
                .password(readString(item, "password", null))
                .displayName(readString(item, "displayName", username))
                .avatarSeed(readString(item, "avatarSeed", username))
                .bio(readString(item, "bio", ""))
                .createdAt(readString(item, "createdAt", null))
                .build();
    }

    private static String readString(Map<String, AttributeValue> item, String key, String fallback) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : fallback;
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String key, String value) {
        if (value != null && !value.isEmpty()) {
            item.put(key, AttributeValue.fromS(value));
        }
    }

    private static void addSet(List<String> sets, Map<String, String> names,
                               Map<String, AttributeValue> values, String field, String value) {
        if (value == null) {
            return;
        }
        // Use expression-attribute-name placeholders to stay safe against reserved words.
        sets.add("#" + field + " = :" + field);
        names.put("#" + field, field);
        values.put(":" + field, AttributeValue.fromS(value));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
