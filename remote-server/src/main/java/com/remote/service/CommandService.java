package com.remote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remote.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * 指令下发服务：通过 WebSocket 向客户端发送指令
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public CommandService(WebSocketSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 向指定客户端发送指令
     * @param clientId 客户端标识
     * @param cmd 指令类型 (start_record / stop_record / screenshot)
     * @param taskId 任务ID
     * @param params 附加参数
     * @return 是否发送成功
     */
    public boolean sendCommand(String clientId, String cmd, String taskId, Map<String, Object> params) {
        if (!sessionManager.isOnline(clientId)) {
            log.warn("客户端不在线，无法发送指令: clientId={}", clientId);
            return false;
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("cmd", cmd);
            message.put("taskId", taskId);
            message.put("params", params != null ? params : new HashMap<>());

            String json = objectMapper.writeValueAsString(message);
            WebSocketSession session = sessionManager.getSession(clientId);
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(json));
                log.info("指令已发送: clientId={}, cmd={}, taskId={}", clientId, cmd, taskId);
                return true;
            }
        } catch (Exception e) {
            log.error("发送指令异常: {}", e.getMessage(), e);
        }
        return false;
    }
}
