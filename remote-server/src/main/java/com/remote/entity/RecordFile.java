package com.remote.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 录屏/截图文件记录实体
 */
@Data
@Entity
@Table(name = "t_record_file")
public class RecordFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "file_name")
    private String fileName;

    /** 服务端存储物理路径 */
    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    /** 1:截图, 2:录屏 */
    @Column(name = "file_type")
    private Integer fileType;

    /** 关联的下发任务ID */
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
