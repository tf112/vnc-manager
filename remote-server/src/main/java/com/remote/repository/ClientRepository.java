package com.remote.repository;

import com.remote.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long>, JpaSpecificationExecutor<Client> {

    Optional<Client> findByClientId(String clientId);

    List<Client> findByStatus(Integer status);

    /** 查找心跳超时（lastHeartbeat早于指定时间）且状态为在线的客户端 */
    List<Client> findByStatusAndLastHeartbeatBefore(Integer status, LocalDateTime threshold);
}
