package com.remote.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 客户端机器实体
 */
@Data
@Entity
@Table(name = "t_client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", unique = true, nullable = false, length = 64)
    private String clientId;

    @Column(name = "hostname", length = 128)
    private String hostname;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "vnc_port")
    private Integer vncPort = 5900;

    @Column(name = "os_type", length = 32)
    private String osType;

    /** VNC 密码（null=未设置，空字符串=无密码） */
    @Column(name = "vnc_password", length = 64)
    private String vncPassword;

    /** VNC 模式: control=控制, view=浏览 */
    @Column(name = "vnc_mode", length = 16)
    private String vncMode = "control";

    /** 0:离线, 1:在线 */
    @Column(name = "status")
    private Integer status = 0;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
