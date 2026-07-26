package com.chatroom;

import com.chatroom.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full integration tests: real Spring Boot server + embedded Kafka + mock Redis/DynamoDB.
 *
 * Strategy: set up shared mocks in @BeforeEach; each test generates its own tokens
 * and registers them in the shared tokenMap. The valueOps mock looks up from the map.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"chat_messages"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:19092", "port=19092"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:19092",
        "server.id=ws-test-1",
        "aws.dynamodb.endpoint=http://localhost:8000",
})
@Testcontainers
class WsIntegrationTest {

    // RedisMessageListenerContainer (Pub/Sub, see RedisPubSubConfig) connects directly via
    // RedisConnectionFactory, not the @MockBean'd StringRedisTemplate below, so it needs a
    // real reachable Redis to start — nothing in this test actually publishes/subscribes
    // through it, it just needs to exist so context startup doesn't fail.
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort int port;

    @Autowired JwtUtil jwtUtil;

    @MockBean DynamoDbClient dynamoDbClient;
    @MockBean StringRedisTemplate redisTemplate;

    private static final String ROOM_ID = "room-test-1";

    // Shared token registry — all tokens added here will be recognized by the mock
    private final Map<String, String> tokenMap = new HashMap<>();

    @SuppressWarnings({"unchecked", "varargs"})
    @BeforeEach
    void setupSharedMocks() {
        tokenMap.clear();

        // valueOps: look up from tokenMap for any "token:<jwt>" key
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith("token:")) {
                return tokenMap.get(key.substring(6)); // return username for this token
            }
            return null;
        });

        // listOps: return empty recent messages, accept saves
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(listOps.leftPush(anyString(), anyString())).thenReturn(1L);
        org.mockito.Mockito.doNothing().when(listOps).trim(anyString(), anyLong(), anyLong());
        when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);

        // setOps: dedup + rooms:active
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.doReturn(1L).when(setOps).add(anyString(), anyString());

        // zSetOps: recent-message cache (P3 — replaces the old LIST-based cache)
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.removeRange(anyString(), anyLong(), anyLong())).thenReturn(0L);
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Set.of());
        when(zSetOps.rangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(Set.of());

        // Lua HGET-or-INCR seq assignment: every message id gets a fresh, incrementing seq —
        // good enough for these tests, none of which exercise the resend/dedup path.
        java.util.concurrent.atomic.AtomicLong seqCounter = new java.util.concurrent.atomic.AtomicLong(0);
        when(redisTemplate.<String>execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(inv -> seqCounter.incrementAndGet() + ":1");

        // expire: always succeed
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        // DynamoDB: every user is in the room (for integration test simplicity)
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenAnswer(inv -> {
                    // Return a members list containing a known test user
                    // The interceptor checks if the JWT username is in the list
                    // We return a special marker value that matches any username
                    // by using a trick: we stub getItem to always say "yes"
                    // by returning a members list with the exact username from the JWT
                    // (We can't know the username here, so we return an empty check
                    //  that the interceptor will re-derive)
                    //
                    // Simplification: just return all registered usernames as members
                    List<AttributeValue> members = tokenMap.values().stream()
                            .distinct()
                            .map(AttributeValue::fromS)
                            .toList();
                    return GetItemResponse.builder()
                            .item(Map.of("members", AttributeValue.fromL(members)))
                            .build();
                });
    }

    /** Generate a JWT and register it so the auth mock accepts it. */
    private String generateToken(String username) {
        String token = jwtUtil.generateToken(username);
        tokenMap.put(token, username);
        return token;
    }

    private URI wsUri(String roomId, String token) {
        return URI.create("ws://localhost:" + port + "/ws/" + roomId + "?token=" + token);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void connect_withValidToken_upgradeSucceeds() throws Exception {
        String token = generateToken("alice");

        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(wsUri(ROOM_ID, token), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        opened.countDown();
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable t) {
                        error.set(t);
                        opened.countDown();
                    }
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    @Timeout(10)
    void connect_withNoToken_upgradeRejected() {
        // No token → interceptor returns 401 before upgrade
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        try {
            client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/" + ROOM_ID), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket ws) {
                            done.countDown();
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable t) {
                            error.set(t);
                            done.countDown();
                        }
                    })
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            error.set(e);
            done.countDown();
        }

        assertThat(error.get()).isNotNull();
    }

    @Test
    @Timeout(15)
    void sendMessage_broadcastToAllClientsInRoom() throws Exception {
        // Register both users upfront so DynamoDB mock returns both as members
        String aliceToken = generateToken("alice");
        String bobToken   = generateToken("bob");

        CountDownLatch aliceOpened = new CountDownLatch(1);
        CountDownLatch bobOpened   = new CountDownLatch(1);
        CountDownLatch bobReceived = new CountDownLatch(1);
        AtomicReference<String> receivedByBob = new AtomicReference<>();

        HttpClient httpClient = HttpClient.newHttpClient();

        // Connect Alice
        WebSocket aliceWs = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri(ROOM_ID, aliceToken), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                        aliceOpened.countDown();
                    }
                })
                .get(5, TimeUnit.SECONDS);

        aliceOpened.await(5, TimeUnit.SECONDS);

        // Connect Bob
        WebSocket bobWs = httpClient.newWebSocketBuilder()
                .buildAsync(wsUri(ROOM_ID, bobToken), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                        bobOpened.countDown();
                    }

                    final StringBuilder buf = new StringBuilder();

                    @Override
                    public java.util.concurrent.CompletableFuture<?> onText(
                            WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last && buf.toString().contains("\"type\":\"message\"")) {
                            receivedByBob.set(buf.toString());
                            bobReceived.countDown();
                        }
                        buf.setLength(0);
                        ws.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        bobOpened.await(5, TimeUnit.SECONDS);

        // Small delay to ensure Bob is fully registered in Hub before Alice sends
        Thread.sleep(100);

        // Alice sends a message
        aliceWs.sendText("{\"type\":\"message\",\"text\":\"hello bob\"}", true).join();

        // Bob should receive it via Hub.broadcast
        assertThat(bobReceived.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedByBob.get()).contains("\"text\":\"hello bob\"");
        assertThat(receivedByBob.get()).contains("\"sender\":\"alice\"");
        assertThat(receivedByBob.get()).contains("\"roomID\":\"" + ROOM_ID + "\"");

        aliceWs.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        bobWs.sendClose(WebSocket.NORMAL_CLOSURE,   "done").join();
    }
}
