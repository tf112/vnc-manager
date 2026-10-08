package com.remote;

import javax.websocket.*;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 客户端测试工具
 * 测试客户端注册、心跳、指令接收
 */
@ClientEndpoint
public class WebSocketTestClient {

    private static CountDownLatch latch = new CountDownLatch(2); // register_ack + pong
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        System.out.println("[TEST] WebSocket connected!");
        this.session = session;
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println("[TEST] Received: " + message);
        latch.countDown();
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        System.out.println("[TEST] WebSocket closed: " + closeReason);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        System.err.println("[TEST] Error: " + error.getMessage());
    }

    public void sendMessage(String message) throws Exception {
        System.out.println("[TEST] Sending: " + message);
        session.getBasicRemote().sendText(message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WebSocket Test Start ===");

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        WebSocketTestClient client = new WebSocketTestClient();

        // Connect
        container.connectToServer(client, new URI("ws://localhost:8080/ws/client"));
        Thread.sleep(500);

        // Test 1: Register
        client.sendMessage("{\"type\":\"register\",\"clientId\":\"test-client-001\",\"vncPort\":5900,\"hostname\":\"TestPC-Java\"}");
        Thread.sleep(500);

        // Test 2: Ping
        client.sendMessage("{\"type\":\"ping\"}");
        Thread.sleep(500);

        // Wait for responses
        boolean received = latch.await(5, TimeUnit.SECONDS);

        // Close
        client.session.close();
        Thread.sleep(500);

        if (received) {
            System.out.println("=== WebSocket Test PASSED ===");
        } else {
            System.out.println("=== WebSocket Test: Some responses not received ===");
        }

        System.exit(0);
    }
}
