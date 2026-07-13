package com.chatroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class JoinChatroomRequest {

    @JsonProperty("chatroom_id")
    @NotBlank(message = "chatroom_id is required")
    private String chatroomId;
}