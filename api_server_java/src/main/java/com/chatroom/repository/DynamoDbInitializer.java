package com.chatroom.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Component
public class DynamoDbInitializer {
    private final DynamoDbClient dynamoDbClient;

    public DynamoDbInitializer(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @PostConstruct
    public void createTables(){
        createUserTable();
        createChatroomsTable();
        createUserChatroomsTable();
        createMessagesTable();
    }

    private void createUserTable() {
        try{
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName("Users")
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("username")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("username")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException e) {
            // Table already exists, ignore
        }
    }

    private void createChatroomsTable() {
        try{
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName("Chatrooms")
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("roomId")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("roomId")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException e) {
            // Table already exists, ignore
        }
    }

    private void createMessagesTable() {
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName("Messages")
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("room_id")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("timestamp")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("room_id")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("timestamp")
                                    .keyType(KeyType.RANGE)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException e) {
            // Table already exists, ignore
        }
    }

    private void createUserChatroomsTable() {
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName("UserChatrooms")
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("username")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("username")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException e) {
            // Table already exists, ignore
        }
    }
}
