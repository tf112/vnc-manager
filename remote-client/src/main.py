"""
RemoteVNC Platform - Python 客户端入口
"""
import os
import sys
import signal
import logging
import threading
import socket

import yaml

# 将 src 目录加入 Python 路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from communication.ws_client import WebSocketClient
from vnc.server_ctl import VncServerManager

# 日志配置
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("main")


def load_config(config_path: str = None) -> dict:
    """加载配置文件"""
    if config_path is None:
        config_path = os.path.join(os.path.dirname(__file__), "config.yaml")
    
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def get_hostname() -> str:
    """获取主机名"""
    return socket.gethostname()


def main():
    """主入口"""
    logger.info("=" * 50)
    logger.info("RemoteVNC Client 启动中...")
    logger.info("=" * 50)

    # 加载配置
    config = load_config()
    logger.info("配置加载完成")

    # 启动内置 VNC Server
    vnc_manager = VncServerManager(config.get("vnc", {}))
    vnc_port = vnc_manager.start()
    if vnc_port:
        logger.info(f"内置 VNC Server 已启动，端口: {vnc_port}")
    else:
        logger.warning("VNC Server 启动失败，将继续运行（仅通信功能）")
        vnc_port = config.get("vnc", {}).get("port", 5900)

    # 创建 WebSocket 客户端
    ws_client = WebSocketClient(
        config=config,
        vnc_port=vnc_port,
        hostname=get_hostname(),
        vnc_manager=vnc_manager
    )

    # 信号处理：优雅退出
    def signal_handler(sig, frame):
        logger.info("收到退出信号，正在关闭...")
        ws_client.stop()
        vnc_manager.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # 启动 WebSocket 连接（阻塞运行，内含重连逻辑）
    logger.info("启动 WebSocket 连接...")
    ws_client.run_forever_with_reconnect()


if __name__ == "__main__":
    main()
