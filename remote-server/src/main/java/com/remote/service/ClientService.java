package com.remote.service;

import com.remote.entity.Client;
import com.remote.repository.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端管理业务逻辑
 */
@Service
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    /**
     * 注册或更新客户端信息
     */
    public Client registerClient(String clientId, String hostname, String ipAddress, int vncPort,
                                 String vncPassword, String vncMode) {
        Optional<Client> opt = clientRepository.findByClientId(clientId);
        Client client;
        if (opt.isPresent()) {
            client = opt.get();
            client.setHostname(hostname);
            client.setIpAddress(ipAddress);
            client.setVncPort(vncPort);
            // 仅在服务端从未设置过（NULL）时，用客户端上报值回填；
            // 管理员已设置过的密码/模式保持不变
            if (client.getVncPassword() == null) {
                client.setVncPassword(vncPassword);
            }
            if (client.getVncMode() == null) {
                client.setVncMode(vncMode != null ? vncMode : "control");
            }
        } else {
            client = new Client();
            client.setClientId(clientId);
            client.setHostname(hostname);
            client.setIpAddress(ipAddress);
            client.setVncPort(vncPort);
            client.setVncPassword(vncPassword);
            client.setVncMode(vncMode != null ? vncMode : "control");
        }
        client.setStatus(1);
        client.setLastHeartbeat(LocalDateTime.now());
        return clientRepository.save(client);
    }

    /**
     * 更新心跳时间
     */
    public void updateHeartbeat(String clientId) {
        Optional<Client> opt = clientRepository.findByClientId(clientId);
        if (opt.isPresent()) {
            Client client = opt.get();
            client.setLastHeartbeat(LocalDateTime.now());
            client.setStatus(1);
            clientRepository.save(client);
        }
    }

    /**
     * 设置客户端离线
     */
    public void setClientOffline(String clientId) {
        Optional<Client> opt = clientRepository.findByClientId(clientId);
        if (opt.isPresent()) {
            Client client = opt.get();
            client.setStatus(0);
            clientRepository.save(client);
            log.info("客户端已标记为离线: {}", clientId);
        }
    }

    /**
     * 获取所有客户端
     */
    public List<Client> findAll() {
        return clientRepository.findAll();
    }

    /**
     * 关键字搜索 + 分页
     * 按 hostname / ipAddress / clientId 模糊匹配，按最近心跳倒序
     */
    public Page<Client> search(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "lastHeartbeat"));

        Specification<Client> spec = (root, query, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) {
                return cb.conjunction();
            }
            String like = "%" + keyword.trim().toLowerCase() + "%";
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(root.get("hostname")), like));
            predicates.add(cb.like(cb.lower(root.get("ipAddress")), like));
            predicates.add(cb.like(cb.lower(root.get("clientId")), like));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
        return clientRepository.findAll(spec, pageable);
    }

    /**
     * 根据 clientId 查找
     */
    public Optional<Client> findByClientId(String clientId) {
        return clientRepository.findByClientId(clientId);
    }

    /**
     * 手动添加客户端
     */
    public Client addClient(Client client) {
        return clientRepository.save(client);
    }

    /**
     * 更新客户端 VNC 设置（密码 + 控制/浏览模式）
     */
    public Client updateVncSettings(String clientId, String vncPassword, String vncMode) {
        Optional<Client> opt = clientRepository.findByClientId(clientId);
        if (!opt.isPresent()) {
            return null;
        }
        Client client = opt.get();
        client.setVncPassword(vncPassword);
        if (vncMode != null) {
            client.setVncMode(vncMode);
        }
        return clientRepository.save(client);
    }

    /**
     * 删除客户端
     */
    public void deleteClient(Long id) {
        clientRepository.deleteById(id);
    }

    /**
     * 定时任务：扫描心跳超时的客户端，标记为离线
     * 每30秒执行一次，超过45秒未心跳视为离线
     */
    @Scheduled(fixedRate = 30000)
    public void checkHeartbeatTimeout() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(45);
        List<Client> timeoutClients = clientRepository.findByStatusAndLastHeartbeatBefore(1, threshold);
        for (Client client : timeoutClients) {
            client.setStatus(0);
            clientRepository.save(client);
            log.info("心跳超时，客户端已离线: clientId={}", client.getClientId());
        }
    }
}
