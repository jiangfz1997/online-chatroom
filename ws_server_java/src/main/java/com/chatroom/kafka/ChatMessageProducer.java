package com.chatroom.kafka;

import com.chatroom.metrics.WsMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes chat messages to the Kafka topic.
 * Attaches a "serverID" header so consumers can skip messages they already broadcast locally.
 * Mirrors Go's kafka.Producer.SendMessage with serverID header.
 */
@Slf4j
@Component
public class ChatMessageProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final String serverId;
    private final WsMetrics metrics;

    public ChatMessageProducer(KafkaTemplate<String, String> kafkaTemplate,
                               @Value("${kafka.topic}") String topic,
                               @Value("${server.id}") String serverId,
                               WsMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.serverId = serverId;
        this.metrics = metrics;
    }

    /**
     * Publish a JSON message to the chat_messages topic.
     * Key = roomId (Kafka uses this for partition routing, keeps room messages ordered).
     *
     * @return the send's future, so the caller (ChatWebSocketHandler) can tell the
     *         original sender whether the message actually reached Kafka — success/failure
     *         here doesn't block on it, this is on top of the same completion this method
     *         already logs internally.
     */
    public CompletableFuture<SendResult<String, String>> send(String roomId, String json) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, roomId, json);
        record.headers().add(new RecordHeader("serverID", serverId.getBytes(StandardCharsets.UTF_8)));

        Timer.Sample sample = metrics.startKafkaSend();
        return kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    metrics.stopKafkaSend(sample);
                    if (ex != null) {
                        log.error("Kafka send failed for room [{}]: {}", roomId, ex.getMessage());
                    } else {
                        log.debug("Kafka message sent for room [{}] offset={}", roomId,
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
