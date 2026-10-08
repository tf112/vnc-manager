package com.remote;

import javax.websocket.*;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 综合测试：保持 WebSocket 连接，验证指令下发
 */
@ClientEndpoint
public class WebSocketCommandTest {

    private static CountDownLatch cmdLatch = new CountDownLatch(1);
    private static String receivedCommand = null;
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        System.out.println("[CMD-TEST] Connected");
        this.session = session;
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println("[CMD-TEST] Received: " + message);
        if (message.contains("\"cmd\"")) {
            receivedCommand = message;
            cmdLatch.countDown();
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        System.out.println("[CMD-TEST] Closed");
    }

    public void sendMessage(String message) throws Exception {
        session.getBasicRemote().sendText(message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Command Test Start ===");

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        WebSocketCommandTest client = new WebSocketCommandTest();
        container.connectToServer(client, new URI("ws://localhost:8080/ws/client"));
        Thread.sleep(300);

        // Register
        client.sendMessage("{\"type\":\"register\",\"clientId\":\"test-client-001\",\"vncPort\":5900,\"hostname\":\"TestPC-CmdTest\"}");
        Thread.sleep(300);

        // Send ping to update heartbeat
        client.sendMessage("{\"type\":\"ping\"}");
        Thread.sleep(300);

        System.out.println("[CMD-TEST] Client registered and online. Waiting for command from HTTP API...");
        System.out.println("[CMD-TEST] (Now run: curl -X POST http://localhost:8080/api/v1/command/send ...)");

        // Wait for command (up to 30 seconds)
        boolean received = cmdLatch.await(30, TimeUnit.SECONDS);

        if (received) {
            System.out.println("[CMD-TEST] Command received: " + receivedCommand);

            // Send result back
            client.sendMessage("{\"type\":\"result\",\"taskId\":\"TASK-TEST-002\",\"status\":\"success\",\"fileName\":\"screenshot.png\",\"fileSize\":1024}");
            Thread.sleep(300);
            System.out.println("=== Command Test PASSED ===");
        } else {
            System.out.println("=== Command Test: No command received within timeout ===");
        }

        client.session.close();
        System.exit(0);
    }
}
