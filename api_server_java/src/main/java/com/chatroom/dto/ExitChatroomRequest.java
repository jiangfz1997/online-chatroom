package com.chatroom.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExitChatroomRequest {

    @NotBlank(message = "chatroomId is required")
    private String chatroomId;
}
