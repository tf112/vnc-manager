package com.remote.controller;

import com.remote.entity.RecordFile;
import com.remote.service.FileService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理 REST API
 */
@RestController
@RequestMapping("/api/v1/file")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 文件上传接口
     * 客户端上传截图/录屏文件
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("clientId") String clientId,
            @RequestParam("taskId") String taskId,
            @RequestParam("fileType") Integer fileType,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> result = new HashMap<>();
        try {
            RecordFile record = fileService.uploadFile(clientId, taskId, fileType, file);
            result.put("code", 200);
            result.put("data", new HashMap<String, Object>() {{
                put("fileId", record.getId());
                put("url", "/api/v1/file/download/" + record.getId());
            }});
        } catch (IOException e) {
            result.put("code", 500);
            result.put("msg", "文件上传失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 文件下载
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) throws IOException {
        List<RecordFile> allFiles = fileService.findAll();
        for (RecordFile record : allFiles) {
            if (record.getId().equals(id)) {
                File file = new File(record.getFilePath());
                if (!file.exists()) {
                    return ResponseEntity.notFound().build();
                }
                byte[] data = Files.readAllBytes(Paths.get(record.getFilePath()));
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=\"" + record.getFileName() + "\"")
                        .body(data);
            }
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 获取文件列表
     * 支持关键字模糊搜索(keyword)和分页(page,size)，page 从 1 开始
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<RecordFile> pageResult = fileService.search(keyword, page, size);
        Map<String, Object> data = new HashMap<>();
        data.put("list", pageResult.getContent());
        data.put("total", pageResult.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定客户端的文件列表
     */
    @GetMapping("/list/{clientId}")
    public ResponseEntity<Map<String, Object>> listByClient(@PathVariable String clientId) {
        List<RecordFile> files = fileService.findByClientId(clientId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", files);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        fileService.deleteFile(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "删除成功");
        return ResponseEntity.ok(result);
    }
}
