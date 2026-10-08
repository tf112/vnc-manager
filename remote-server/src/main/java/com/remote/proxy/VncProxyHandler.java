package com.remote.proxy;

import com.remote.entity.Client;
import com.remote.service.ClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VNC WebSocket 代理处理器
 * 核心功能：将前端 noVNC 的 WebSocket 连接桥接到目标客户端的 VNC Server (TCP)
 *
 * 数据流：
 *   前端 noVNC <--WebSocket--> 本代理 <--TCP SocketChannel--> 客户端 VNC Server
 */
@Component
public class VncProxyHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VncProxyHandler.class);

    /** 从 WebSocket URI 中提取 clientId 的正则 */
    private static final Pattern URI_PATTERN = Pattern.compile("/vnc/proxy/([^/?#]+)");

    /** 用于异步读取 TCP 数据的线程池 */
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vnc-tcp-reader");
        t.setDaemon(true);
        return t;
    });

    private final ClientService clientService;

    public VncProxyHandler(ClientService clientService) {
        this.clientService = clientService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        String clientId = extractClientId(wsSession);
        if (clientId == null) {
            log.error("无法从 URI 中提取 clientId: {}", wsSession.getUri());
            wsSession.close(CloseStatus.BAD_DATA);
            return;
        }

        // 查询客户端信息
        Optional<Client> opt = clientService.findByClientId(clientId);
        if (!opt.isPresent()) {
            log.error("客户端不存在: clientId={}", clientId);
            wsSession.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        Client client = opt.get();
        String targetHost = normalizeIp(client.getIpAddress());
        int targetPort = client.getVncPort();

        log.info("VNC代理连接建立: clientId={}, target={}:{}", clientId, targetHost, targetPort);

        // 建立到目标 VNC Server 的 TCP 连接
        SocketChannel tcpChannel;
        try {
            tcpChannel = SocketChannel.open();
            tcpChannel.configureBlocking(true);
            tcpChannel.connect(new InetSocketAddress(targetHost, targetPort));
            // 设置读取超时（避免无限阻塞）
            tcpChannel.socket().setSoTimeout(30000);
        } catch (IOException e) {
            log.error("无法连接到 VNC Server: {}:{}, error={}", targetHost, targetPort, e.getMessage());
            wsSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // 将 tcpChannel 存入 session attributes
        wsSession.getAttributes().put("tcpChannel", tcpChannel);
        wsSession.getAttributes().put("clientId", clientId);
        // 每个连接独立的发送锁，防止 TCP 读线程与 WebSocket 容器线程并发 sendMessage 导致帧数据损坏
        wsSession.getAttributes().put("sendLock", new Object());

        log.info("TCP 连接已建立: clientId={}, remote={}", clientId, tcpChannel.getRemoteAddress());

        // 启动异步线程：从 TCP 读取数据 -> 转发到 WebSocket
        executorService.submit(() -> readFromTcpAndForwardToWebSocket(wsSession, tcpChannel, clientId));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession wsSession, org.springframework.web.socket.BinaryMessage message) throws Exception {
        // WebSocket 收到 Binary 消息 -> 写入 TCP SocketChannel
        SocketChannel tcpChannel = (SocketChannel) wsSession.getAttributes().get("tcpChannel");
        if (tcpChannel == null || !tcpChannel.isConnected()) {
            return;
        }

        ByteBuffer buffer = message.getPayload();
        synchronized (tcpChannel) {
            while (buffer.hasRemaining()) {
                tcpChannel.write(buffer);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, org.springframework.web.socket.TextMessage message) throws Exception {
        // VNC 协议使用二进制，文本消息一般不转发，但记录日志
        log.debug("收到文本消息（忽略）: {}", message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
        String clientId = (String) wsSession.getAttributes().get("clientId");
        SocketChannel tcpChannel = (SocketChannel) wsSession.getAttributes().get("tcpChannel");

        log.info("VNC代理连接关闭: clientId={}, status={}", clientId, status);

        // 关闭 TCP 连接
        closeTcpChannel(tcpChannel, clientId);
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) throws Exception {
        String clientId = (String) wsSession.getAttributes().get("clientId");
        log.error("VNC代理传输异常: clientId={}, error={}", clientId, exception.getMessage());

        SocketChannel tcpChannel = (SocketChannel) wsSession.getAttributes().get("tcpChannel");
        closeTcpChannel(tcpChannel, clientId);

        if (wsSession.isOpen()) {
            wsSession.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 异步循环：从 TCP SocketChannel 读取数据 -> 通过 WebSocket 发送 Binary 消息
     * 每个连接在独立线程中运行
     */
    private void readFromTcpAndForwardToWebSocket(WebSocketSession wsSession, SocketChannel tcpChannel, String clientId) {
        // 使用 256KB 缓冲区，尽量让完整的 ZRLE 更新在一次读取中完成
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        long totalBytesForwarded = 0;
        int messageCount = 0;
        try {
            while (tcpChannel.isConnected() && wsSession.isOpen()) {
                buffer.clear();
                int bytesRead = tcpChannel.read(buffer);
                if (bytesRead == -1) {
                    log.info("TCP 连接已关闭 (EOF): clientId={}, totalForwarded={}, messages={}",
                            clientId, totalBytesForwarded, messageCount);
                    break;
                }
                if (bytesRead > 0) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    // 使用 synchronized 防止 TCP 读线程与 WebSocket 容器线程并发 sendMessage 导致帧数据损坏
                    Object sendLock = wsSession.getAttributes().get("sendLock");
                    if (sendLock == null) sendLock = wsSession;
                    synchronized (sendLock) {
                        wsSession.sendMessage(new org.springframework.web.socket.BinaryMessage(data));
                    }
                    totalBytesForwarded += data.length;
                    messageCount++;
                    // 记录前 10 条消息的前 20 字节，用于验证数据完整性
                    if (messageCount <= 10) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(20, data.length); i++) {
                            sb.append(String.format("%02x", data[i] & 0xFF));
                        }
                        log.info("WS msg #{}: {} bytes, first20=[{}]", messageCount, data.length, sb);
                    }
                }
            }
        } catch (IOException e) {
            if (wsSession.isOpen()) {
                log.debug("TCP 读取异常: clientId={}, totalForwarded={}, error={}",
                        clientId, totalBytesForwarded, e.getMessage());
            }
        } catch (Exception e) {
            log.error("TCP->WebSocket 转发异常: clientId={}, totalForwarded={}", clientId, totalBytesForwarded, e);
        } finally {
            closeTcpChannel(tcpChannel, clientId);
            if (wsSession.isOpen()) {
                try {
                    wsSession.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 安全关闭 TCP Channel
     */
    private void closeTcpChannel(SocketChannel channel, String clientId) {
        if (channel != null && channel.isConnected()) {
            try {
                channel.close();
                log.debug("TCP Channel 已关闭: clientId={}", clientId);
            } catch (IOException e) {
                log.warn("关闭 TCP Channel 异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 归一化 IP 地址：将 IPv6 回环地址转换为 IPv4 127.0.0.1
     */
    private String normalizeIp(String ip) {
        if (ip == null) return "127.0.0.1";
        // IPv6 回环地址的各种表示
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "::ffff:127.0.0.1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    /**
     * 从 WebSocket Session 的 URI 中提取 clientId
     * URI 格式: /vnc/proxy/{clientId}
     */
    private String extractClientId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        Matcher matcher = URI_PATTERN.matcher(path);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
