"""
文件上传模块
将截图/录屏文件上传到服务端
"""
import os
import logging

import requests

logger = logging.getLogger("file_uploader")


class FileUploader:
    """文件上传器"""

    def __init__(self, http_url: str, client_id: str):
        """
        Args:
            http_url: 服务端 HTTP 地址 (如 http://localhost:8080)
            client_id: 客户端唯一标识
        """
        self.http_url = http_url.rstrip("/")
        self.client_id = client_id

    def upload(self, file_path: str, task_id: str, file_type: int) -> dict:
        """
        上传文件到服务端
        
        Args:
            file_path: 文件物理路径
            task_id: 关联的任务ID
            file_type: 文件类型 (1:截图, 2:录屏)
            
        Returns:
            dict: 服务端响应，成功时包含 fileId 和 url
        """
        if not os.path.exists(file_path):
            logger.error(f"文件不存在: {file_path}")
            return None

        upload_url = f"{self.http_url}/api/v1/file/upload"
        file_name = os.path.basename(file_path)
        file_size = os.path.getsize(file_path)

        try:
            with open(file_path, "rb") as f:
                files = {"file": (file_name, f)}
                data = {
                    "clientId": self.client_id,
                    "taskId": task_id,
                    "fileType": str(file_type)
                }

                logger.info(f"开始上传文件: {file_name}, 大小: {file_size} bytes")
                response = requests.post(upload_url, data=data, files=files, timeout=300)

                if response.status_code == 200:
                    result = response.json()
                    if result.get("code") == 200:
                        logger.info(f"文件上传成功: {file_name}")
                        return result.get("data")
                    else:
                        logger.error(f"文件上传失败: {result.get('msg')}")
                else:
                    logger.error(f"文件上传HTTP错误: {response.status_code}")

        except requests.exceptions.Timeout:
            logger.error(f"文件上传超时: {file_name}")
        except requests.exceptions.ConnectionError:
            logger.error(f"文件上传连接失败: 无法连接到 {self.http_url}")
        except Exception as e:
            logger.error(f"文件上传异常: {e}")

        return None

    def upload_screenshot(self, file_path: str, task_id: str) -> dict:
        """上传截图文件"""
        return self.upload(file_path, task_id, file_type=1)

    def upload_recording(self, file_path: str, task_id: str) -> dict:
        """上传录屏文件"""
        return self.upload(file_path, task_id, file_type=2)
