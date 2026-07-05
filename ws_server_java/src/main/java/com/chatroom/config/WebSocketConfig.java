package com.chatroom.config;

import com.chatroom.auth.WsAuthInterceptor;
import com.chatroom.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final WsAuthInterceptor interceptor;

    public WebSocketConfig(ChatWebSocketHandler handler, WsAuthInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/**")
                .addInterceptors(interceptor)
                // Allow all origins for dev; tighten this in production
                .setAllowedOriginPatterns("*");
    }
}
