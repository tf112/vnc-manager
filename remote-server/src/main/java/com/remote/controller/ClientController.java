package com.remote.controller;

import com.remote.entity.Client;
import com.remote.service.ClientService;
import com.remote.service.CommandService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端管理 REST API
 */
@RestController
@RequestMapping("/api/v1/client")
public class ClientController {

    private final ClientService clientService;
    private final CommandService commandService;

    public ClientController(ClientService clientService, CommandService commandService) {
        this.clientService = clientService;
        this.commandService = commandService;
    }

    /**
     * 获取客户端列表
     * 支持关键字模糊搜索(keyword)和分页(page,size)，page 从 1 开始
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Client> pageResult = clientService.search(keyword, page, size);
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
     * 根据 clientId 获取客户端详情
     */
    @GetMapping("/{clientId}")
    public ResponseEntity<Map<String, Object>> getByClientId(@PathVariable String clientId) {
        Map<String, Object> result = new HashMap<>();
        return clientService.findByClientId(clientId)
                .map(client -> {
                    result.put("code", 200);
                    result.put("data", client);
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> {
                    result.put("code", 404);
                    result.put("msg", "客户端不存在");
                    return ResponseEntity.ok(result);
                });
    }

    /**
     * 更新客户端 VNC 设置（密码 + 控制/浏览模式）
     */
    @PutMapping("/{clientId}/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@PathVariable String clientId,
                                                              @RequestBody Map<String, Object> body) {
        String vncPassword = body.get("vncPassword") != null ? (String) body.get("vncPassword") : "";
        String vncMode = body.get("vncMode") != null ? (String) body.get("vncMode") : "control";

        Client client = clientService.updateVncSettings(clientId, vncPassword, vncMode);
        Map<String, Object> result = new HashMap<>();
        if (client == null) {
            result.put("code", 404);
            result.put("msg", "客户端不存在");
            return ResponseEntity.ok(result);
        }

        // 若客户端在线，推送新配置到 Python 客户端
        Map<String, Object> params = new HashMap<>();
        params.put("password", vncPassword);
        params.put("mode", vncMode);
        boolean pushed = commandService.sendCommand(
                clientId,
                "update_vnc_config",
                "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                params);

        result.put("code", 200);
        result.put("msg", pushed ? "设置已保存并下发" : "设置已保存（客户端离线，将在上线后同步）");
        return ResponseEntity.ok(result);
    }

    /**
     * 手动添加客户端
     */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Client client) {
        Client saved = clientService.addClient(client);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", saved);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除客户端
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        clientService.deleteClient(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "删除成功");
        return ResponseEntity.ok(result);
    }
}
