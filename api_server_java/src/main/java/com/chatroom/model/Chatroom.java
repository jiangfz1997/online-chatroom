package com.chatroom.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Chatroom {

    @JsonProperty("room_id")
    private String roomId;

    @JsonProperty("name")
    private String roomName;

    // Lombok generates isPrivate() for boolean fields, which Jackson maps to "private".
    // @JsonProperty on the field forces the correct key for both serialization and deserialization.
    @JsonProperty("is_private")
    private boolean isPrivate;

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("created_at")
    private String createdAt;

    private List<String> members;
}