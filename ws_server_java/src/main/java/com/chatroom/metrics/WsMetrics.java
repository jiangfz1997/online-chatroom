package com.chatroom.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single Micrometer facade for ws-server, exposed via Actuator at /actuator/prometheus.
 * Every hot-path metric named in the M2a instrumentation-first plan lives here so call
 * sites (Hub, ChatWebSocketHandler, RedisMessageService, ChatMessage{Producer,Consumer})
 * stay free of metrics wiring boilerplate.
 */
@Component
public class WsMetrics {

    private final AtomicInteger activeSessions = new AtomicInteger(0);

    private final Counter messagesIn;
    private final Counter messagesOut;
    private final Timer broadcastTimer;
    private final Timer redisRttTimer;
    private final Timer kafkaSendTimer;
    private final Timer kafkaConsumeTimer;

    public WsMetrics(MeterRegistry registry) {
        registry.gauge("ws.sessions.active", activeSessions);

        this.messagesIn = Counter.builder("ws.messages.in")
                .description("Chat messages received from WebSocket clients")
                .register(registry);
        this.messagesOut = Counter.builder("ws.messages.out")
                .description("Chat messages delivered to WebSocket clients (local broadcast + cross-instance fan-in)")
                .register(registry);
        this.broadcastTimer = Timer.builder("ws.broadcast.duration")
                .description("Time to fan a message out to all local clients in a room")
                .publishPercentileHistogram()
                .register(registry);
        this.redisRttTimer = Timer.builder("ws.redis.rtt")
                .description("Round-trip time of a single saveMessage() call to Redis")
                .publishPercentileHistogram()
                .register(registry);
        this.kafkaSendTimer = Timer.builder("ws.kafka.send.duration")
                .description("Time from Kafka producer send() to broker ack/error")
                .publishPercentileHistogram()
                .register(registry);
        this.kafkaConsumeTimer = Timer.builder("ws.kafka.consume.duration")
                .description("Time to process one consumed Kafka record (Redis save + routing dispatch)")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void sessionJoined() {
        activeSessions.incrementAndGet();
    }

    public void sessionLeft() {
        activeSessions.updateAndGet(v -> Math.max(0, v - 1));
    }

    public void messageReceived() {
        messagesIn.increment();
    }

    /** Times the local fan-out and counts one "out" per recipient it was attempted for. */
    public void recordBroadcast(int recipientCount, Runnable fanOut) {
        broadcastTimer.record(fanOut);
        if (recipientCount > 0) {
            messagesOut.increment(recipientCount);
        }
    }

    public void recordRedisRtt(Runnable saveMessage) {
        redisRttTimer.record(saveMessage);
    }

    public Timer.Sample startKafkaSend() {
        return Timer.start();
    }

    public void stopKafkaSend(Timer.Sample sample) {
        sample.stop(kafkaSendTimer);
    }

    public Timer.Sample startKafkaConsume() {
        return Timer.start();
    }

    public void stopKafkaConsume(Timer.Sample sample) {
        sample.stop(kafkaConsumeTimer);
    }
}
