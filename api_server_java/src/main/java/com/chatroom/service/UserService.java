package com.chatroom.service;

import com.chatroom.dto.LoginRequest;
import com.chatroom.dto.RegisterRequest;
import com.chatroom.dto.UpdateProfileRequest;
import com.chatroom.exception.NotFoundException;
import com.chatroom.exception.UnauthorizedException;
import com.chatroom.model.User;
import com.chatroom.repository.UserRepository;
import com.chatroom.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
public class UserService {
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       JwtUtil jwtUtil,
                       StringRedisTemplate redisTemplate,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user with a BCrypt-hashed password and default profile
     * (displayName + avatarSeed default to the username).
     * Throws IllegalArgumentException if the username is already taken.
     */
    public void register(RegisterRequest req) {
        User user = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getUsername())
                .avatarSeed(req.getUsername())
                .bio("")
                .createdAt(Instant.now().toString())
                .build();
        try {
            userRepository.createUser(user);
            log.info("User registered: username=[{}]", req.getUsername());
        } catch (ConditionalCheckFailedException e) {
            // DynamoDB condition "attribute_not_exists(username)" failed —
            // the username is already taken
            throw new IllegalArgumentException("Username already exists");
        }
    }

    /**
     * Authenticates a user and returns a signed JWT token.
     * Verifies the password against the stored BCrypt hash; legacy plaintext
     * passwords are verified directly and lazily upgraded to a hash on success.
     * Stores the token in Redis for active session tracking and logout support.
     */
    public String login(LoginRequest req) {
        Optional<User> result = userRepository.findByUsername(req.getUsername());

        if (result.isEmpty()) {
            throw new UnauthorizedException("Username does not exist");
        }

        User user = result.get();
        if (!verifyPassword(req.getUsername(), req.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("Wrong password");
        }

        String token = jwtUtil.generateToken(req.getUsername());

        // Store token in Redis to support active logout and per-request revocation.
        // Key format: "token:<jwt>" -> username
        redisTemplate.opsForValue().set(
                "token:" + token,
                req.getUsername(),
                Duration.ofHours(24)
        );

        log.info("User logged in: username=[{}]", req.getUsername());
        return token;
    }

    /**
     * Invalidates a session by deleting its token from Redis. After this,
     * JwtAuthFilter's Redis check rejects the token even though its signature
     * is still valid until natural expiry.
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete("token:" + token);
        }
    }

    /** Returns the user's profile, or 404 if the user no longer exists. */
    public User getProfile(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Updates the caller's editable profile fields (displayName / bio / avatarSeed). */
    public User updateProfile(String username, UpdateProfileRequest req) {
        userRepository.updateProfile(username, req.getDisplayName(), req.getBio(), req.getAvatarSeed());
        log.info("Profile updated: username=[{}]", username);
        return getProfile(username);
    }

    /**
     * Verifies a raw password against the stored value. If the stored value is a
     * BCrypt hash, uses the encoder; otherwise treats it as a legacy plaintext
     * password and, on a match, upgrades it to a hash in place.
     */
    private boolean verifyPassword(String username, String raw, String stored) {
        if (stored == null) {
            return false;
        }
        if (isBcryptHash(stored)) {
            return passwordEncoder.matches(raw, stored);
        }
        // Legacy plaintext record — verify directly and upgrade to a hash.
        if (stored.equals(raw)) {
            userRepository.updatePassword(username, passwordEncoder.encode(raw));
            log.info("Upgraded legacy plaintext password to hash: username=[{}]", username);
            return true;
        }
        return false;
    }

    private static boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
