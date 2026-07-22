package com.chatroom.persist.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Matches the JSON envelope written by the ws-server when a message is broadcast. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawMessage {
    private String id;
    private String sender;
    private String text;

    @JsonProperty("roomID")
    private String roomId;

    @JsonProperty("sentAt")
    private String timestamp;
}
