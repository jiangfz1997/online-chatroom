package com.chatroom.config;

import com.chatroom.redis.InstanceMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to its own per-instance Redis Pub/Sub channel, used by
 * RedisRoutingService on other instances to deliver messages precisely instead of
 * broadcasting to every ws-server instance.
 */
@Configuration
public class RedisPubSubConfig {

    @Value("${server.id}")
    private String serverId;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            InstanceMessageListener instanceMessageListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(instanceMessageListener, new ChannelTopic("instance:" + serverId + ":messages"));
        return container;
    }
}
