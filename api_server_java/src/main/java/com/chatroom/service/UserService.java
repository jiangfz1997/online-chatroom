package com.chatroom.service;

import com.chatroom.dto.LoginRequest;
import com.chatroom.dto.RegisterRequest;
import com.chatroom.exception.UnauthorizedException;
import com.chatroom.model.User;
import com.chatroom.repository.UserRepository;
import com.chatroom.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
public class UserService {
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public UserService(UserRepository userRepository,
                       JwtUtil jwtUtil,
                       StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;

    }

    /**
     * Registers a new user.
     * Throws IllegalArgumentException if the username is already taken.
     */
    public void register(RegisterRequest req) {
        try {
            userRepository.createUser(new User(req.getUsername(), req.getPassword()));
            log.info("User registered: username=[{}]", req.getUsername());
        } catch (ConditionalCheckFailedException e) {
            // DynamoDB condition "attribute_not_exists(username)" failed —
            // the username is already taken
            throw new IllegalArgumentException("Username already exists");
        }
    }

    /**
     * Authenticates a user and returns a signed JWT token.
     * Stores the token in Redis for active session tracking and logout support.
     * Throws IllegalArgumentException if credentials are invalid.
     */
    public String login(LoginRequest req) {
        Optional<User> result = userRepository.findByUsername(req.getUsername());

        if (result.isEmpty()) {
            throw new UnauthorizedException("Username does not exist");
        }

        User user = result.get();
        if (!user.getPassword().equals(req.getPassword())) {
            throw new UnauthorizedException("Wrong password");
        }

        String token = jwtUtil.generateToken(req.getUsername());

        // Store token in Redis to support active logout.
        // Key format matches the Go version: "token:<jwt>" -> username
        redisTemplate.opsForValue().set(
                "token:" + token,
                req.getUsername(),
                Duration.ofHours(24)
        );

        log.info("User logged in: username=[{}]", req.getUsername());
        return token;
    }

}
