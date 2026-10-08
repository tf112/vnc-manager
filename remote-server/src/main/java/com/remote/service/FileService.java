package com.remote.service;

import com.remote.entity.RecordFile;
import com.remote.repository.RecordFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.criteria.Predicate;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件管理业务逻辑
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    @Value("${file.upload-dir}")
    private String uploadDir;

    private final RecordFileRepository recordFileRepository;

    public FileService(RecordFileRepository recordFileRepository) {
        this.recordFileRepository = recordFileRepository;
    }

    /**
     * 上传文件并保存记录
     */
    public RecordFile uploadFile(String clientId, String taskId, Integer fileType, MultipartFile file) throws IOException {
        // 创建存储目录
        File dir = new File(uploadDir + File.separator + clientId);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成唯一文件名
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf("."));
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;
        String fullPath = dir.getAbsolutePath() + File.separator + storedName;

        // 保存文件
        file.transferTo(new File(fullPath));
        log.info("文件上传成功: {}", fullPath);

        // 保存记录
        RecordFile record = new RecordFile();
        record.setClientId(clientId);
        record.setFileName(originalName);
        record.setFilePath(fullPath);
        record.setFileSize(file.getSize());
        record.setFileType(fileType);
        record.setTaskId(taskId);

        return recordFileRepository.save(record);
    }

    /**
     * 查询客户端的所有文件记录
     */
    public List<RecordFile> findByClientId(String clientId) {
        return recordFileRepository.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /**
     * 查询所有文件记录
     */
    public List<RecordFile> findAll() {
        return recordFileRepository.findAll();
    }

    /**
     * 关键字搜索 + 分页
     * 按文件名 / 客户端ID 模糊匹配，按创建时间倒序
     */
    public Page<RecordFile> search(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<RecordFile> spec = (root, query, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) {
                return cb.conjunction();
            }
            String like = "%" + keyword.trim().toLowerCase() + "%";
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(root.get("fileName")), like));
            predicates.add(cb.like(cb.lower(root.get("clientId")), like));
            return cb.or(predicates.toArray(new Predicate[0]));
        };
        return recordFileRepository.findAll(spec, pageable);
    }

    /**
     * 删除文件记录及物理文件
     */
    public void deleteFile(Long id) {
        recordFileRepository.findById(id).ifPresent(record -> {
            File file = new File(record.getFilePath());
            if (file.exists()) {
                boolean deleted = file.delete();
                log.info("删除物理文件: {}, 结果: {}", record.getFilePath(), deleted);
            }
            recordFileRepository.deleteById(id);
        });
    }
}
