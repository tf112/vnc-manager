package com.remote.config;

import com.remote.proxy.VncProxyHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * VNC 代理 WebSocket 配置
 * 注册 VNC 代理处理器，路径: /vnc/proxy/{clientId}
 *
 * 前端 noVNC 连接示例:
 *   ws://localhost:8080/vnc/proxy/client-001
 */
@Configuration
public class VncProxyWebSocketConfig implements WebSocketConfigurer {

    private final VncProxyHandler vncProxyHandler;

    public VncProxyWebSocketConfig(VncProxyHandler vncProxyHandler) {
        this.vncProxyHandler = vncProxyHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(vncProxyHandler, "/vnc/proxy/*")
                .setAllowedOrigins("*");
    }

    /**
     * 配置 WebSocket 容器参数
     * ZRLE 帧可达 200KB+，需要足够大的二进制缓冲区
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainerFactory() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(8192);
        factory.setMaxBinaryMessageBufferSize(512 * 1024);  // 512KB 二进制缓冲区
        factory.setMaxSessionIdleTimeout(300000L);          // 5 分钟空闲超时
        return factory;
    }
}
