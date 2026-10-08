"""
截图模块
使用 mss 库截取全屏，保存为 PNG 文件
"""
import os
import time
import logging
from datetime import datetime

import mss

logger = logging.getLogger("screenshot")

# 截图保存目录
SCREENSHOT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "output", "screenshots")


def take_screenshot(task_id: str = None) -> dict:
    """
    截取全屏并保存为 PNG 文件
    
    Args:
        task_id: 任务ID，用于命名文件
        
    Returns:
        dict: {"file_path": str, "file_name": str, "file_size": int} 或 None
    """
    # 确保输出目录存在
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    if task_id:
        file_name = f"screenshot_{task_id}_{timestamp}.png"
    else:
        file_name = f"screenshot_{timestamp}.png"

    file_path = os.path.join(SCREENSHOT_DIR, file_name)

    try:
        with mss.mss() as sct:
            # 截取主屏幕 (monitor 1)
            monitor = sct.monitors[1]
            screenshot = sct.grab(monitor)

            # 保存为 PNG
            mss.tools.to_png(screenshot.rgb, screenshot.size, output=file_path)

        file_size = os.path.getsize(file_path)
        logger.info(f"截图完成: {file_path}, 大小: {file_size} bytes")

        return {
            "file_path": file_path,
            "file_name": file_name,
            "file_size": file_size
        }

    except Exception as e:
        logger.error(f"截图失败: {e}")
        return None
