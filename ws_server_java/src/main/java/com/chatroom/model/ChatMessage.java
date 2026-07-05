package com.chatroom.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    private String type;
    private String sender;
    private String text;

    // roomID matches the Go JSON field name ("roomID" not "roomId")
    @JsonProperty("roomID")
    private String roomId;

    private String sentAt;
}
