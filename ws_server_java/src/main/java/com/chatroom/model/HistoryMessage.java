package com.chatroom.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Matches the DynamoDB Messages table schema and Go's dynamodb.Message struct. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryMessage {
    @JsonProperty("room_id")
    private String roomId;

    private String timestamp;
    private String sender;
    private String text;
}
