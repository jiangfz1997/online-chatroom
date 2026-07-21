package com.chatroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Editable profile fields. All optional — only non-null fields are updated,
 * so the client can send a partial update.
 */
@Data
public class UpdateProfileRequest {

    @JsonProperty("display_name")
    private String displayName;

    private String bio;

    @JsonProperty("avatar_seed")
    private String avatarSeed;
}
