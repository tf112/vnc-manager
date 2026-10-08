package com.remote.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remote.entity.Client;
import com.remote.service.ClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;

/**
 * 客户端 WebSocket 通信处理器
 * 处理客户端注册、心跳、指令接收等
 */
@Component
public class ClientWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientWebSocketHandler.class);

    private final WebSocketSessionManager sessionManager;
    private final ClientService clientService;
    private final ObjectMapper objectMapper;

    /** 当前 handler 关联的 clientId（每个 handler 实例对应一个连接，但 Spring 默认单例，
     *  所以用 session attributes 存储 clientId） */
    private static final String ATTR_CLIENT_ID = "clientId";

    public ClientWebSocketHandler(WebSocketSessionManager sessionManager,
                                  ClientService clientService,
                                  ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.clientService = clientService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("客户端 WebSocket 连接建立: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到客户端消息: {}", payload);

        try {
            JsonNode json = objectMapper.readTree(payload);
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "register":
                    handleRegister(session, json);
                    break;
                case "ping":
                    handlePing(session);
                    break;
                case "result":
                    handleResult(session, json);
                    break;
                default:
                    log.warn("未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理客户端消息异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理客户端注册消息
     */
    private void handleRegister(WebSocketSession session, JsonNode json) throws Exception {
        String clientId = json.get("clientId").asText();
        int vncPort = json.has("vncPort") ? json.get("vncPort").asInt() : 5900;
        String hostname = json.has("hostname") ? json.get("hostname").asText() : "unknown";
        String vncPassword = (json.has("vncPassword") && !json.get("vncPassword").isNull())
                ? json.get("vncPassword").asText() : "";
        String vncMode = (json.has("vncMode") && !json.get("vncMode").isNull())
                ? json.get("vncMode").asText() : "control";

        // 保存 session 映射
        session.getAttributes().put(ATTR_CLIENT_ID, clientId);
        sessionManager.addSession(clientId, session);

        // 更新或创建客户端记录
        Client client = clientService.registerClient(clientId, hostname, getClientIp(session), vncPort,
                vncPassword, vncMode);

        log.info("客户端注册成功: clientId={}, hostname={}, vncPort={}", clientId, hostname, vncPort);

        // 回复注册成功，并携带服务端存储的 VNC 设置，客户端据此同步密码与模式
        String response = objectMapper.writeValueAsString(
                new java.util.HashMap<String, Object>() {{
                    put("type", "register_ack");
                    put("status", "success");
                    put("vncPassword", client.getVncPassword());
                    put("vncMode", client.getVncMode());
                }}
        );
        session.sendMessage(new TextMessage(response));
    }

    /**
     * 处理心跳消息
     */
    private void handlePing(WebSocketSession session) throws Exception {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
        if (clientId == null) {
            return;
        }

        // 更新心跳时间
        clientService.updateHeartbeat(clientId);

        // 回复 pong
        String response = objectMapper.writeValueAsString(
                new java.util.HashMap<String, Object>() {{
                    put("type", "pong");
                }}
        );
        session.sendMessage(new TextMessage(response));
    }

    /**
     * 处理任务结果消息
     */
    private void handleResult(WebSocketSession session, JsonNode json) {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
        String taskId = json.has("taskId") ? json.get("taskId").asText() : "";
        String status = json.has("status") ? json.get("status").asText() : "";

        log.info("收到任务结果: clientId={}, taskId={}, status={}", clientId, taskId, status);

        if ("success".equals(status)) {
            String fileName = json.has("fileName") ? json.get("fileName").asText() : "";
            long fileSize = json.has("fileSize") ? json.get("fileSize").asLong() : 0;
            log.info("任务完成: fileName={}, fileSize={}", fileName, fileSize);
        } else {
            String error = json.has("error") ? json.get("error").asText() : "unknown";
            log.warn("任务失败: taskId={}, error={}", taskId, error);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
        log.info("客户端 WebSocket 连接关闭: clientId={}, status={}", clientId, status);

        if (clientId != null) {
            sessionManager.removeSession(session);
            clientService.setClientOffline(clientId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输异常: sessionId={}, error={}", session.getId(), exception.getMessage());
        if (session.isOpen()) {
            session.close();
        }
    }

    /**
     * 获取客户端 IP 地址
     */
    private String getClientIp(WebSocketSession session) {
        if (session.getRemoteAddress() != null) {
            return session.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }
}
