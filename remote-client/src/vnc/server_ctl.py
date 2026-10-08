"""
VNC Server 管理模块
封装内置 VncServer，提供与旧接口兼容的 start/stop 方法
"""
import logging

from vnc.server import VncServer

logger = logging.getLogger("vnc_manager")


class VncServerManager:
    """内置 VNC Server 管理器（替代外部 TigerVNC/TightVNC 子进程）"""

    def __init__(self, config: dict):
        self.config = config
        self._server = None

    def start(self) -> int:
        """
        启动内置 VNC Server
        返回实际监听端口，失败返回 None
        """
        port = self.config.get("port", 5900)
        max_port = self.config.get("max_port", 5905)
        password = self.config.get("password", "") or None
        display_name = self.config.get("display_name", "RemoteVNC")
        view_only = self.config.get("view_only", False)

        self._server = VncServer(
            port=port,
            password=password,
            max_port=max_port,
            display_name=display_name,
            view_only=view_only
        )

        result = self._server.start()
        if result:
            logger.info(f"内置 VNC Server 已启动，端口: {result}")
        else:
            logger.error("内置 VNC Server 启动失败")
        return result

    def update_config(self, password: str = None, view_only: bool = None) -> bool:
        """动态更新 VNC 配置（无需重启，仅影响新连接）

        password 为 None 表示保持原密码；传空字符串表示清除密码。
        view_only 为 None 表示保持原模式。
        """
        if not self._server:
            return False
        if password is not None:
            self._server.set_password(password)
        if view_only is not None:
            self._server.set_view_only(view_only)
        return True

    def stop(self):
        """停止内置 VNC Server"""
        if self._server:
            self._server.stop()
            self._server = None

    @property
    def actual_port(self) -> int:
        """获取实际监听端口"""
        if self._server:
            return self._server.actual_port
        return None

    @property
    def password(self) -> str:
        """获取当前 VNC 密码（None 表示无密码）"""
        if self._server:
            return self._server.password
        return None

    @property
    def view_only(self) -> bool:
        """获取当前是否为浏览模式"""
        if self._server:
            return self._server.view_only
        return False
