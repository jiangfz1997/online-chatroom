package com.chatroom;

import com.chatroom.kafka.ChatMessageProducer;
import com.chatroom.ws.ClientSession;
import com.chatroom.ws.Hub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Proves the actual point of the routing-table rework: a message published on one
 * ws-server instance is delivered ONLY to the instance(s) that have a local client in
 * the target room, not fanned out to every instance in the (shared) Kafka consumer
 * group.
 *
 * Spins up three real, independent Spring application contexts (three "ws-server
 * instances", each with its own Hub / RedisRoutingService) sharing one embedded Kafka
 * broker and one real Redis (via Testcontainers — routing table SADD/SMEMBERS and
 * Pub/Sub PUBLISH/SUBSCRIBE can't be verified against a mocked Redis).
 */
@ExtendWith(SpringExtension.class)
@Testcontainers
@EmbeddedKafka(partitions = 3, topics = {"chat_messages"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:19096", "port=19096"})
class CrossInstanceRoutingIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String ROOM_ID = "room-cross-1";
    private static final String UNRELATED_ROOM_ID = "room-cross-2";

    ConfigurableApplicationContext senderInstance;
    ConfigurableApplicationContext instanceWithClient;
    ConfigurableApplicationContext instanceWithoutClient;

    @BeforeEach
    void startInstances() {
        senderInstance        = buildInstance("ws-test-sender");
        instanceWithClient    = buildInstance("ws-test-with-client");
        instanceWithoutClient = buildInstance("ws-test-without-client");
    }

    @AfterEach
    void stopInstances() {
        instanceWithoutClient.close();
        instanceWithClient.close();
        senderInstance.close();
    }

    private ConfigurableApplicationContext buildInstance(String serverId) {
        // Use command-line-style args (highest-precedence property source), not
        // SpringApplicationBuilder#properties(...) (which registers "default
        // properties" — the LOWEST precedence source, so it would silently lose to
        // the localhost:6379 / localhost:9094 defaults baked into application.properties).
        return new SpringApplicationBuilder(WsServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--server.id=" + serverId,
                        "--kafka.group.id=ws-server-group-cross-instance-test",
                        "--kafka.topic.partitions=3",
                        "--spring.kafka.bootstrap-servers=localhost:19096",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--aws.dynamodb.endpoint=http://localhost:8000"
                );
    }

    private static ClientSession mockClient(String sessionId, String username, String roomId,
                                            CountDownLatch receivedLatch) {
        ClientSession client = mock(ClientSession.class);
        when(client.getSessionId()).thenReturn(sessionId);
        when(client.getUsername()).thenReturn(username);
        when(client.getRoomId()).thenReturn(roomId);
        if (receivedLatch != null) {
            doAnswer(inv -> {
                receivedLatch.countDown();
                return null;
            }).when(client).send(anyString());
        }
        return client;
    }

    @Test
    @Timeout(30)
    void message_routedOnlyToInstanceWithLocalClientInThatRoom() throws Exception {
        Hub hubWithClient    = instanceWithClient.getBean(Hub.class);
        Hub hubWithoutClient = instanceWithoutClient.getBean(Hub.class);
        ChatMessageProducer producer = senderInstance.getBean(ChatMessageProducer.class);
        StringRedisTemplate redis = senderInstance.getBean(StringRedisTemplate.class);

        CountDownLatch bobReceived = new CountDownLatch(1);
        ClientSession bob = mockClient("bob-session", "bob", ROOM_ID, bobReceived);
        hubWithClient.joinRoom(ROOM_ID, bob);

        // A client on the "without-client" instance, but in an unrelated room — proves
        // that even if this instance ends up owning the Kafka partition for the message
        // (shared consumer group), it forwards via Redis instead of broadcasting locally.
        ClientSession carol = mockClient("carol-session", "carol", UNRELATED_ROOM_ID, null);
        hubWithoutClient.joinRoom(UNRELATED_ROOM_ID, carol);

        // Routing table write happens synchronously inside joinRoom (onRoomOccupied),
        // so it should already be visible via the shared Redis.
        Set<String> routedInstances = redis.opsForSet().members("room:" + ROOM_ID + ":instances");
        assertThat(routedInstances).containsExactly("ws-test-with-client");

        String json = String.format(
                "{\"type\":\"message\",\"sender\":\"alice\",\"text\":\"hi\",\"roomID\":\"%s\",\"sentAt\":\"%s\"}",
                ROOM_ID, Instant.now());
        producer.send(ROOM_ID, json);

        assertThat(bobReceived.await(15, TimeUnit.SECONDS))
                .as("bob (local client in the target room) should receive the message")
                .isTrue();
        verify(bob, timeout(1000)).send(contains("\"text\":\"hi\""));

        // Give any stray delivery to the unrelated instance a chance to arrive, then
        // confirm carol (different room, same instance) never saw it.
        Thread.sleep(500);
        verify(carol, never()).send(anyString());
    }
}
