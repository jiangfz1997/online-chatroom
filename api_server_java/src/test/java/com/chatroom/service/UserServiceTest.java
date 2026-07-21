package com.chatroom.service;

import com.chatroom.dto.LoginRequest;
import com.chatroom.dto.RegisterRequest;
import com.chatroom.exception.UnauthorizedException;
import com.chatroom.model.User;
import com.chatroom.repository.UserRepository;
import com.chatroom.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UserService userService;

    // ── register ──────────────────────────────────────────────

    @Test
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("john");
        req.setPassword("123");

        // doNothing: createUser returns void, just verify it was called
        doNothing().when(userRepository).createUser(any(User.class));

        assertThatCode(() -> userService.register(req))
                .doesNotThrowAnyException();

        verify(userRepository, times(1)).createUser(any(User.class));
    }

    // ── login ─────────────────────────────────────────────────

    @Test
    void login_success() {
        LoginRequest req = new LoginRequest();
        req.setUsername("john");
        req.setPassword("123");

        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(new User("john", "123")));
        when(jwtUtil.generateToken("john"))
                .thenReturn("fake-token");
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        String token = userService.login(req);

        assertThat(token).isEqualTo("fake-token");
        verify(redisTemplate.opsForValue(), times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void login_userNotFound_throwsUnauthorized() {
        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("123");

        when(userRepository.findByUsername("ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Username does not exist");
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        LoginRequest req = new LoginRequest();
        req.setUsername("john");
        req.setPassword("wrong");

        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(new User("john", "123")));

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Wrong password");
    }
}
