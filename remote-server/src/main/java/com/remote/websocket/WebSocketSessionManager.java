package com.remote.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Session 管理器
 * 维护 clientId -> WebSocketSession 的映射关系
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    /** clientId -> WebSocketSession */
    private final ConcurrentHashMap<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    /** WebSocketSession.getId() -> clientId (反向映射，用于断开时清理) */
    private final ConcurrentHashMap<String, String> sessionIdToClientIdMap = new ConcurrentHashMap<>();

    /**
     * 注册客户端 Session
     */
    public void addSession(String clientId, WebSocketSession session) {
        // 如果该 clientId 已有旧 session，先关闭
        WebSocketSession oldSession = sessionMap.get(clientId);
        if (oldSession != null && oldSession.isOpen()) {
            try {
                oldSession.close();
            } catch (IOException e) {
                log.warn("关闭旧 Session 时异常: {}", e.getMessage());
            }
        }
        sessionMap.put(clientId, session);
        sessionIdToClientIdMap.put(session.getId(), clientId);
        log.info("注册 Session: clientId={}, sessionId={}", clientId, session.getId());
    }

    /**
     * 移除客户端 Session
     */
    public void removeSession(WebSocketSession session) {
        String clientId = sessionIdToClientIdMap.remove(session.getId());
        if (clientId != null) {
            sessionMap.remove(clientId);
            log.info("移除 Session: clientId={}, sessionId={}", clientId, session.getId());
        }
    }

    /**
     * 根据 clientId 获取 Session
     */
    public WebSocketSession getSession(String clientId) {
        return sessionMap.get(clientId);
    }

    /**
     * 判断客户端是否在线
     */
    public boolean isOnline(String clientId) {
        WebSocketSession session = sessionMap.get(clientId);
        return session != null && session.isOpen();
    }
}
