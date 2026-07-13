package com.chatroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateChatroomRequest {

    @NotBlank(message = "name is required")
    private String name;

    @JsonProperty("is_private")
    private boolean isPrivate;
}