-- RemoteVNC Platform 数据库初始化脚本
-- MySQL 8.0+

CREATE DATABASE IF NOT EXISTS remote_vnc DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE remote_vnc;

-- 客户端机器表
CREATE TABLE IF NOT EXISTS t_client (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(64) UNIQUE NOT NULL COMMENT '客户端唯一标识',
    hostname VARCHAR(128),
    ip_address VARCHAR(64),
    vnc_port INT DEFAULT 5900,
    os_type VARCHAR(32),
    vnc_password VARCHAR(64) COMMENT 'VNC密码(null=未设置,空=无密码)',
    vnc_mode VARCHAR(16) DEFAULT 'control' COMMENT 'VNC模式: control=控制, view=浏览',
    status TINYINT DEFAULT 0 COMMENT '0:离线, 1:在线',
    last_heartbeat DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端机器表';

-- 文件记录表
CREATE TABLE IF NOT EXISTS t_record_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    file_path VARCHAR(512) COMMENT '服务端存储物理路径',
    file_size BIGINT,
    file_type TINYINT COMMENT '1:截图, 2:录屏',
    task_id VARCHAR(64) COMMENT '关联的下发任务ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_client (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='录屏截图文件记录表';
