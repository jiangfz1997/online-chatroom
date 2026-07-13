package com.chatroom.repository;

import com.chatroom.model.User;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;
import java.util.Optional;

@Repository
public class UserRepository {
    private static final String TABLE_NAME = "Users";

    private final DynamoDbClient dynamoDbClient;

    public UserRepository(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public void createUser(User user){
        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(Map.of(
                        "username", AttributeValue.fromS(user.getUsername()),
                        "password", AttributeValue.fromS(user.getPassword())
                ))
                // Reject the write if this username already exists
                .conditionExpression("attribute_not_exists(username)")
                .build();

        dynamoDbClient.putItem(request);

    }

    public Optional<User> findByUsername(String username) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "username", AttributeValue.fromS(username)
                ))
                .build();

        GetItemResponse response = dynamoDbClient.getItem(request);

        if (!response.hasItem()) {
            return Optional.empty();
        }

        User user = new User(
                response.item().get("username").s(),
                response.item().get("password").s()
        );
        return Optional.of(user);
    }

}
