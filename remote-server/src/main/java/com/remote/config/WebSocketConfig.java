package com.remote.config;

import com.remote.websocket.ClientWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册客户端通信 Handler
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ClientWebSocketHandler clientWebSocketHandler;

    public WebSocketConfig(ClientWebSocketHandler clientWebSocketHandler) {
        this.clientWebSocketHandler = clientWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 客户端长连接通道
        registry.addHandler(clientWebSocketHandler, "/ws/client")
                .setAllowedOrigins("*");
    }
}
