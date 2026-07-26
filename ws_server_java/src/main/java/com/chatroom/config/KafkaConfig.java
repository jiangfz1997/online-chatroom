package com.chatroom.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // All ws-server instances share one consumer group so Kafka's partition assignment
    // spreads message processing across instances instead of fanning out to everyone.
    // The instance that ends up owning a partition routes messages via Redis (see
    // RedisRoutingService) to whichever instances actually need them.
    @Value("${kafka.group.id}")
    private String groupId;

    @Value("${kafka.topic}")
    private String topic;

    @Value("${kafka.topic.partitions:3}")
    private int topicPartitions;

    @Bean
    public NewTopic chatMessagesTopic() {
        // NOTE: this only applies when the topic doesn't exist yet. If chat_messages
        // was already auto-created with 1 partition, it must be repartitioned manually
        // via `kafka-topics.sh --alter --topic chat_messages --partitions N` — Spring
        // will not resize an existing topic.
        return TopicBuilder.name(topic)
                .partitions(topicPartitions)
                .replicas(1)
                .build();
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Manual ack (below, via AckMode.RECORD): only commit an offset once consume()
        // actually returns without throwing, so a failure leaves the record uncommitted
        // and eligible for the error handler's retry/redelivery instead of being silently
        // skipped by a timer-driven auto-commit that doesn't know processing failed.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Retries with backoff before giving up (tmp_doc/04 Position C): today a failed
        // record (e.g. Redis briefly down) gets a couple of instant retries then is
        // silently skipped. Blocking this partition for up to ~30s while retrying gives
        // a brief outage a real chance to recover instead of being treated as permanent.
        // If it's still failing after 5 attempts, publish to "chat_messages.DLT" (the
        // recoverer's default naming) instead of vanishing with no trace.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate());
        var errorHandler = new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(5));
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        // Prevents duplicate records landing in the log if a retry above races with the
        // broker actually having received a prior attempt.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
