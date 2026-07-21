package com.chatroom.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    private String username;
    private String password;

    // Profile fields (Milestone 1). Older records won't have these — the
    // repository fills sensible defaults on read (displayName/avatarSeed = username).
    private String displayName;
    private String avatarSeed;
    private String bio;
    private String createdAt;

    /**
     * Convenience constructor for the common username+password case
     * (registration input, credential checks, tests). Profile fields are
     * populated separately.
     */
    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
