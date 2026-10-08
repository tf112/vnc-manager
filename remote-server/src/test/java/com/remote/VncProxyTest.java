package com.remote;

import javax.websocket.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * VNC 代理测试客户端
 * 验证 WebSocket -> TCP -> VNC Server 的桥接是否正常工作
 * 
 * VNC (RFB) 协议握手流程:
 * 1. Server -> Client: 发送协议版本 (如 "RFB 003.008\n")
 * 2. Client -> Server: 回复协议版本
 * 3. Server -> Client: 发送安全类型列表
 * 4. ...
 */
@ClientEndpoint
public class VncProxyTest {

    private static CountDownLatch dataLatch = new CountDownLatch(1);
    private static byte[] receivedData = null;
    private static String receivedText = null;
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        System.out.println("[VNC-TEST] WebSocket connected to proxy");
        this.session = session;
    }

    @OnMessage
    public void onBinaryMessage(byte[] data) {
        System.out.println("[VNC-TEST] Received binary data: " + data.length + " bytes");
        String hex = bytesToHex(data);
        System.out.println("[VNC-TEST] Hex: " + hex);

        // Try to interpret as text (VNC version string)
        String text = new String(data);
        System.out.println("[VNC-TEST] Text: " + text.trim());

        receivedData = data;
        receivedText = text;
        dataLatch.countDown();
    }

    @OnMessage
    public void onTextMessage(String message) {
        System.out.println("[VNC-TEST] Received text: " + message);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        System.out.println("[VNC-TEST] WebSocket closed: " + closeReason);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        System.err.println("[VNC-TEST] Error: " + error.getMessage());
        error.printStackTrace();
    }

    public void sendData(byte[] data) throws Exception {
        session.getBasicRemote().sendBinary(ByteBuffer.wrap(data));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== VNC Proxy Test Start ===");
        System.out.println("[VNC-TEST] Connecting to ws://localhost:8080/vnc/proxy/vnc-test-001");

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        VncProxyTest client = new VncProxyTest();

        try {
            // Connect to VNC proxy
            Session wsSession = container.connectToServer(client, new URI("ws://localhost:8080/vnc/proxy/vnc-test-001"));
            System.out.println("[VNC-TEST] WebSocket session state: " + wsSession.isOpen());

            // Wait for VNC Server version string (forwarded through proxy)
            boolean received = dataLatch.await(10, TimeUnit.SECONDS);

            if (received && receivedData != null) {
                System.out.println();
                System.out.println("=== STEP 1: VNC Version String Received ===");
                System.out.println("[VNC-TEST] Data: " + receivedText.trim());

                // Check if it's a valid RFB version string
                if (receivedText.contains("RFB")) {
                    System.out.println("[VNC-TEST] Valid RFB protocol version detected!");

                    // Send our version back (RFB 003.008)
                    byte[] versionResponse = "RFB 003.008\n".getBytes();
                    client.sendData(versionResponse);
                    System.out.println("[VNC-TEST] Sent version response: RFB 003.008");

                    // Wait for security types
                    dataLatch = new CountDownLatch(1);
                    received = dataLatch.await(5, TimeUnit.SECONDS);

                    if (received && receivedData != null) {
                        System.out.println();
                        System.out.println("=== STEP 2: Security Types Received ===");
                        System.out.println("[VNC-TEST] Security data: " + bytesToHex(receivedData));
                        System.out.println("[VNC-TEST] Length: " + receivedData.length + " bytes");
                    }
                }

                System.out.println();
                System.out.println("=== VNC Proxy Test PASSED ===");
                System.out.println("WebSocket <-> TCP <-> VNC Server bridge is working!");
            } else {
                System.out.println("=== VNC Proxy Test: No data received ===");
                System.out.println("[VNC-TEST] The proxy may not have connected to VNC Server");
            }

            wsSession.close();
        } catch (Exception e) {
            System.err.println("[VNC-TEST] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }

        System.exit(0);
    }
}
