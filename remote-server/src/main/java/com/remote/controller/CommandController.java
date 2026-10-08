package com.remote.controller;

import com.remote.service.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 指令下发 REST API
 */
@RestController
@RequestMapping("/api/v1/command")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    /**
     * 发送指令到客户端
     * Body: {"clientId":"xxx", "cmd":"start_record", "params":{"duration":10}}
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        String clientId = (String) body.get("clientId");
        String cmd = (String) body.get("cmd");
        String taskId = (String) body.getOrDefault("taskId", "TASK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");

        Map<String, Object> result = new HashMap<>();

        if (clientId == null || cmd == null) {
            result.put("code", 400);
            result.put("msg", "clientId 和 cmd 不能为空");
            return ResponseEntity.ok(result);
        }

        boolean success = commandService.sendCommand(clientId, cmd, taskId, params);
        if (success) {
            result.put("code", 200);
            result.put("msg", "指令发送成功");
            result.put("taskId", taskId);
        } else {
            result.put("code", 500);
            result.put("msg", "客户端不在线或发送失败");
        }
        return ResponseEntity.ok(result);
    }
}
