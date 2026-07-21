package com.chatroom.controller;

import com.chatroom.dto.LoginRequest;
import com.chatroom.dto.RegisterRequest;
import com.chatroom.dto.UpdateProfileRequest;
import com.chatroom.model.User;
import com.chatroom.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Registers a new user.
     * Returns 409 if the username is already taken.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        log.info("Register attempt: username=[{}]", req.getUsername());
        userService.register(req);
        return ResponseEntity.ok(Map.of("message", "sign up successfully"));
    }

    /**
     * Authenticates a user and returns a JWT token.
     * Returns 401 if credentials are invalid.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        log.info("Login attempt: username=[{}]", req.getUsername());
        String token = userService.login(req);
        return ResponseEntity.ok(Map.of(
                "message", "login success",
                "username", req.getUsername(),
                "token", token
        ));
    }

    /**
     * Invalidates the caller's token (server-side logout).
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        userService.logout(extractToken(authHeader));
        return ResponseEntity.ok(Map.of("message", "logout success"));
    }

    /**
     * Returns the authenticated user's profile.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMe(Authentication auth) {
        return ResponseEntity.ok(toProfile(userService.getProfile(auth.getName())));
    }

    /**
     * Updates the authenticated user's editable profile fields.
     */
    @PutMapping("/me")
    public ResponseEntity<?> updateMe(Authentication auth, @RequestBody UpdateProfileRequest req) {
        log.info("Profile update: username=[{}]", auth.getName());
        return ResponseEntity.ok(toProfile(userService.updateProfile(auth.getName(), req)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Serializes a user for the client, deliberately omitting the password. */
    private Map<String, Object> toProfile(User user) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", user.getUsername());
        body.put("display_name", user.getDisplayName());
        body.put("avatar_seed", user.getAvatarSeed());
        body.put("bio", user.getBio());
        body.put("created_at", user.getCreatedAt());
        return body;
    }

    private static String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
