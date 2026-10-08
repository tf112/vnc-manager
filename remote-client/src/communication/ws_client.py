"""
WebSocket 客户端通信模块
负责：与服务端的长连接、注册、心跳、指令接收与分发、断线重连
"""
import json
import time
import logging
import threading

import websocket

from capture.screenshot import take_screenshot
from capture.recorder import start_recording, stop_recording
from upload.file_uploader import FileUploader

logger = logging.getLogger("ws_client")


class WebSocketClient:
    """WebSocket 长连接客户端，支持自动重连（指数退避）"""

    def __init__(self, config: dict, vnc_port: int, hostname: str, vnc_manager=None):
        self.config = config
        self.vnc_port = vnc_port
        self.hostname = hostname
        self.vnc_manager = vnc_manager

        server_config = config.get("server", {})
        self.ws_url = server_config.get("ws_url", "ws://localhost:8080/ws/client")
        self.http_url = server_config.get("http_url", "http://localhost:8080")

        client_config = config.get("client", {})
        self.client_id = client_config.get("client_id", "client-001")

        heartbeat_config = config.get("heartbeat", {})
        self.heartbeat_interval = heartbeat_config.get("interval", 15)

        reconnect_config = config.get("reconnect", {})
        self.reconnect_initial_delay = reconnect_config.get("initial_delay", 1)
        self.reconnect_max_delay = reconnect_config.get("max_delay", 60)

        recording_config = config.get("recording", {})
        self.max_record_duration = recording_config.get("max_duration", 3600)
        self.default_record_fps = recording_config.get("default_fps", 10)

        self.ws = None
        self._running = True
        self._heartbeat_thread = None
        self._command_handlers = {}

        # 文件上传器
        self._uploader = FileUploader(self.http_url, self.client_id)

        # 注册默认的指令处理器
        self._register_default_handlers()

    def _register_default_handlers(self):
        """注册默认指令处理器"""
        self._command_handlers["start_record"] = self._on_start_record
        self._command_handlers["stop_record"] = self._on_stop_record
        self._command_handlers["screenshot"] = self._on_screenshot
        self._command_handlers["update_vnc_config"] = self._on_update_vnc_config

    def _on_start_record(self, params: dict, task_id: str):
        """开始录屏指令处理"""
        logger.info(f"收到开始录屏指令: taskId={task_id}, params={params}")
        duration = params.get("duration", 0)  # 0=由 max_duration 控制
        fps = params.get("fps", self.default_record_fps)

        # 注册完成回调：录屏停止后（无论何种原因）自动上传并上报结果
        def on_recording_complete(file_result: dict, completed_task_id: str):
            logger.info(f"录屏完成，开始上传: {file_result['file_name']}")
            upload_result = self._uploader.upload_recording(
                file_result["file_path"], completed_task_id
            )
            if upload_result:
                self._send_task_result(completed_task_id, "success",
                                       file_result["file_name"],
                                       file_result["file_size"])
            else:
                self._send_task_result(completed_task_id, "fail", error="录屏文件上传失败")

        result = start_recording(
            task_id=task_id,
            duration=duration,
            fps=fps,
            max_duration=self.max_record_duration,
            on_complete=on_recording_complete
        )
        if result:
            logger.info(f"录屏已启动: {result['file_name']}, "
                        f"最大时长={self.max_record_duration}s")
        else:
            logger.error("录屏启动失败")
            self._send_task_result(task_id, "fail", error="录屏启动失败")

    def _on_stop_record(self, params: dict, task_id: str):
        """停止录屏指令处理（手动中断）"""
        logger.info(f"收到停止录屏指令: taskId={task_id}")
        # stop_recording() 内部会自动触发 on_complete 回调进行上传
        file_result = stop_recording()
        if file_result:
            logger.info(f"录屏手动停止: {file_result['file_name']}")
            # 回调会处理上传和结果上报，这里无需重复
        else:
            self._send_task_result(task_id, "fail", error="没有正在运行的录屏任务")

    def _on_screenshot(self, params: dict, task_id: str):
        """截图指令处理"""
        logger.info(f"收到截图指令: taskId={task_id}")
        result = take_screenshot(task_id=task_id)
        if result:
            upload_result = self._uploader.upload_screenshot(
                result["file_path"], task_id
            )
            if upload_result:
                self._send_task_result(task_id, "success",
                                       result["file_name"],
                                       result["file_size"])
            else:
                self._send_task_result(task_id, "fail", error="文件上传失败")
        else:
            self._send_task_result(task_id, "fail", error="截图失败")

    def _on_update_vnc_config(self, params: dict, task_id: str):
        """更新 VNC 配置指令处理（密码 + 控制/浏览模式）"""
        logger.info(f"收到更新 VNC 配置指令: taskId={task_id}")
        password = params.get("password", "")
        mode = params.get("mode", "control")
        view_only = (mode == "view")

        if not self.vnc_manager:
            self._send_task_result(task_id, "fail", error="VNC Server 未初始化")
            return

        ok = self.vnc_manager.update_config(password=password, view_only=view_only)
        if ok:
            logger.info(f"VNC 配置已更新: view_only={view_only}")
            self._send_task_result(task_id, "success")
        else:
            self._send_task_result(task_id, "fail", error="VNC Server 未运行")

    def _apply_vnc_config(self, data: dict):
        """应用服务端在注册确认时下发的 VNC 配置

        vncPassword 为 None 表示服务端未设置过密码，保持客户端本地配置；
        为空字符串表示显式清除密码。
        """
        if not self.vnc_manager:
            return
        if "vncPassword" not in data and "vncMode" not in data:
            return
        password = data.get("vncPassword")
        mode = data.get("vncMode") or "control"
        view_only = (mode == "view")
        self.vnc_manager.update_config(password=password, view_only=view_only)

    def run_forever_with_reconnect(self):
        """带自动重连的 WebSocket 长连接"""
        delay = self.reconnect_initial_delay

        while self._running:
            try:
                logger.info(f"正在连接服务端: {self.ws_url}")
                self.ws = websocket.WebSocketApp(
                    self.ws_url,
                    on_open=self._on_open,
                    on_message=self._on_message,
                    on_error=self._on_error,
                    on_close=self._on_close
                )
                self.ws.run_forever()
            except Exception as e:
                logger.error(f"WebSocket 连接异常: {e}")

            if not self._running:
                break

            # 指数退避重连
            logger.info(f"将在 {delay} 秒后重连...")
            time.sleep(delay)
            delay = min(delay * 2, self.reconnect_max_delay)

    def _on_open(self, ws):
        """连接建立后发送注册消息"""
        logger.info("WebSocket 连接已建立")
        # 重置重连延迟
        # 发送注册消息（附带当前 VNC 密码与模式，供服务端首次注册时初始化）
        register_msg = {
            "type": "register",
            "clientId": self.client_id,
            "vncPort": self.vnc_port,
            "hostname": self.hostname,
            "vncPassword": self._vnc_password(),
            "vncMode": self._vnc_mode()
        }
        self.send(register_msg)

        # 启动心跳线程
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._heartbeat_thread.start()

    def _vnc_password(self) -> str:
        """获取当前 VNC 密码（空字符串表示无密码）"""
        if self.vnc_manager:
            return self.vnc_manager.password or ""
        return ""

    def _vnc_mode(self) -> str:
        """获取当前 VNC 模式"""
        if self.vnc_manager and self.vnc_manager.view_only:
            return "view"
        return "control"

    def _on_message(self, ws, message):
        """收到服务端消息"""
        logger.debug(f"收到消息: {message}")
        try:
            data = json.loads(message)
            msg_type = data.get("type", "")

            if msg_type == "register_ack":
                logger.info(f"注册成功: {data}")
                self._apply_vnc_config(data)
            elif msg_type == "pong":
                logger.debug("收到 pong 响应")
            elif "cmd" in data:
                # 指令消息
                self._handle_command(data)
            else:
                logger.warning(f"未知消息类型: {msg_type}")
        except json.JSONDecodeError:
            logger.error(f"消息解析失败: {message}")

    def _on_error(self, ws, error):
        """连接错误"""
        logger.error(f"WebSocket 错误: {error}")

    def _on_close(self, ws, close_status_code, close_msg):
        """连接关闭"""
        logger.info(f"WebSocket 连接关闭: code={close_status_code}, msg={close_msg}")

    def _handle_command(self, data: dict):
        """处理服务端下发的指令"""
        cmd = data.get("cmd", "")
        task_id = data.get("taskId", "")
        params = data.get("params", {})

        handler = self._command_handlers.get(cmd)
        if handler:
            # 在独立线程中执行指令，避免阻塞心跳
            threading.Thread(target=handler, args=(params, task_id), daemon=True).start()
        else:
            logger.warning(f"未知指令: {cmd}")

    def _heartbeat_loop(self):
        """心跳发送循环"""
        while self._running and self.ws and self.ws.sock and self.ws.sock.connected:
            try:
                self.send({"type": "ping"})
                logger.debug("发送心跳 ping")
            except Exception as e:
                logger.error(f"发送心跳失败: {e}")
                break
            time.sleep(self.heartbeat_interval)

    def _send_task_result(self, task_id: str, status: str,
                           file_name: str = None, file_size: int = 0,
                           error: str = None):
        """向服务端发送任务结果"""
        msg = {
            "type": "result",
            "taskId": task_id,
            "status": status
        }
        if file_name:
            msg["fileName"] = file_name
        if file_size:
            msg["fileSize"] = file_size
        if error:
            msg["error"] = error
        self.send(msg)
        logger.info(f"任务结果已上报: taskId={task_id}, status={status}")

    def send(self, data: dict):
        """发送 JSON 消息"""
        if self.ws and self.ws.sock and self.ws.sock.connected:
            self.ws.send(json.dumps(data))
        else:
            logger.warning("WebSocket 未连接，消息发送失败")

    def stop(self):
        """停止客户端"""
        self._running = False
        if self.ws:
            self.ws.close()
        logger.info("客户端已停止")
