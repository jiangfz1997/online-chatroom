package com.chatroom.service;

import com.chatroom.dto.LoginRequest;
import com.chatroom.dto.RegisterRequest;
import com.chatroom.exception.UnauthorizedException;
import com.chatroom.model.User;
import com.chatroom.repository.UserRepository;
import com.chatroom.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    // ── register ──────────────────────────────────────────────

    @Test
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("john");
        req.setPassword("123");

        when(passwordEncoder.encode("123")).thenReturn("$2a$hashed");
        doNothing().when(userRepository).createUser(any(User.class));

        assertThatCode(() -> userService.register(req))
                .doesNotThrowAnyException();

        // password is stored hashed, and profile defaults are set to the username
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).createUser(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("$2a$hashed");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("john");
        assertThat(captor.getValue().getAvatarSeed()).isEqualTo("john");
    }

    // ── login ─────────────────────────────────────────────────

    @Test
    void login_success_withHashedPassword() {
        LoginRequest req = new LoginRequest();
        req.setUsername("john");
        req.setPassword("123");

        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(new User("john", "$2a$hashed")));
        when(passwordEncoder.matches("123", "$2a$hashed")).thenReturn(true);
        when(jwtUtil.generateToken("john")).thenReturn("fake-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = userService.login(req);

        assertThat(token).isEqualTo("fake-token");
        verify(valueOperations, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void login_legacyPlaintextPassword_verifiesAndUpgrades() {
        LoginRequest req = new LoginRequest();
        req.setUsername("john");
        req.setPassword("123");

        // stored value is legacy plaintext (not a bcrypt hash)
        when(userRepository.findByUsername("john"))
                .thenReturn(Optional.of(new User("john", "123")));
        when(passwordEncoder.encode("123")).thenReturn("$2a$upgraded");
        when(jwtUtil.generateToken("john")).thenReturn("fake-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = userService.login(req);

        assertThat(token).isEqualTo("fake-token");
        // the plaintext record is lazily upgraded to a hash
        verify(userRepository, times(1)).updatePassword("john", "$2a$upgraded");
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
                .thenReturn(Optional.of(new User("john", "$2a$hashed")));
        when(passwordEncoder.matches("wrong", "$2a$hashed")).thenReturn(false);

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Wrong password");
    }
}
