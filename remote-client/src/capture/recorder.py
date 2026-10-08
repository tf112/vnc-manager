"""
录屏模块
使用 mss 截帧 + imageio-ffmpeg H264 编码录制全屏，保存为 MP4 文件
支持：最大时长限制、超时自动停止、完成后回调通知
"""
import os
import time
import logging
import threading
import queue
from datetime import datetime

import numpy as np

logger = logging.getLogger("recorder")

# 录屏保存目录
RECORDING_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "output", "recordings")

# 全局录屏器引用（用于停止）
_current_recorder = None
_recorder_lock = threading.Lock()

# 当前任务上下文
_current_task_id = None
_on_complete_callback = None  # 录屏完成后的回调函数


class ScreenRecorder:
    """
    屏幕录制器：mss 截帧 + imageio-ffmpeg H264 编码
    架构：capture_thread (mss) → frame_queue → writer_thread (imageio/ffmpeg)
    """

    def __init__(self, output_path: str, fps: int = 10, crf: int = 23):
        self.output_path = output_path
        self.fps = fps
        self.crf = crf
        self._recording = False
        self._frame_queue = queue.Queue(maxsize=fps * 2)
        self._capture_thread = None
        self._writer_thread = None
        self._stop_event = threading.Event()

    def start(self):
        """开始录制"""
        try:
            self._stop_event.clear()
            self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
            self._writer_thread = threading.Thread(target=self._writer_loop, daemon=True)
            self._capture_thread.start()
            self._writer_thread.start()
            self._recording = True
            logger.info(f"录屏开始 (H264): {self.output_path}, fps={self.fps}, crf={self.crf}")
        except Exception as e:
            logger.error(f"录屏启动失败: {e}")
            self._recording = False

    def _capture_loop(self):
        """截帧线程：持续用 mss 截取屏幕并放入队列"""
        import mss
        interval = 1.0 / self.fps
        try:
            with mss.mss() as sct:
                monitor = sct.monitors[1]  # 主显示器
                while not self._stop_event.is_set():
                    st = time.perf_counter()
                    img = sct.grab(monitor)
                    # BGRA → RGB (imageio 需要 RGB 格式)
                    frame = np.array(img)[:, :, :3][:, :, ::-1]
                    try:
                        self._frame_queue.put(frame, timeout=interval)
                    except queue.Full:
                        pass  # 丢弃帧以保持实时性
                    elapsed = time.perf_counter() - st
                    sleep_time = interval - elapsed
                    if sleep_time > 0:
                        time.sleep(sleep_time)
        except Exception as e:
            logger.error(f"截帧线程异常: {e}")
        finally:
            # 发送结束信号
            self._frame_queue.put(None)

    def _writer_loop(self):
        """写入线程：从队列取帧并用 imageio-ffmpeg H264 编码写入"""
        import imageio
        writer = None
        try:
            writer = imageio.get_writer(
                self.output_path,
                fps=self.fps,
                codec="h264",
                output_params=["-crf", str(self.crf), "-preset", "ultrafast",
                               "-movflags", "+faststart"],
                macro_block_size=1
            )
            while True:
                frame = self._frame_queue.get()
                if frame is None:
                    break
                writer.append_data(frame)
        except Exception as e:
            logger.error(f"写入线程异常: {e}")
        finally:
            if writer:
                writer.close()

    def stop(self) -> bool:
        """停止录制"""
        if not self._recording:
            return False
        try:
            self._stop_event.set()
            # 等待截帧线程结束
            if self._capture_thread:
                self._capture_thread.join(timeout=5)
            # 等待写入线程处理完队列中的帧
            if self._writer_thread:
                self._writer_thread.join(timeout=10)
            self._recording = False
            logger.info(f"录屏停止: {self.output_path}")
            return True
        except Exception as e:
            logger.error(f"录屏停止失败: {e}")
            self._recording = False
            return False

    @property
    def is_recording(self) -> bool:
        return self._recording


def _invoke_callback(file_result: dict, task_id: str):
    """
    在独立线程中调用完成回调，避免阻塞录屏停止流程
    """
    global _on_complete_callback
    cb = _on_complete_callback
    _on_complete_callback = None
    if cb:
        threading.Thread(target=cb, args=(file_result, task_id), daemon=True).start()


def start_recording(task_id: str = None, duration: int = 0, fps: int = 10,
                    max_duration: int = 3600, on_complete=None) -> dict:
    """
    启动录屏任务

    Args:
        task_id: 任务ID
        duration: 指定时长（秒），>0 时到期自动停止；0 表示手动停止
        fps: 帧率
        max_duration: 最大录屏时长上限（秒），无论 duration 如何，到达后强制停止
        on_complete: 录屏完成后的回调函数 fn(file_result, task_id)

    Returns:
        dict: {"file_path": str, "file_name": str} 或 None
    """
    global _current_recorder, _current_task_id, _on_complete_callback

    os.makedirs(RECORDING_DIR, exist_ok=True)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    if task_id:
        file_name = f"recording_{task_id}_{timestamp}.mp4"
    else:
        file_name = f"recording_{timestamp}.mp4"

    file_path = os.path.join(RECORDING_DIR, file_name)

    with _recorder_lock:
        if _current_recorder and _current_recorder.is_recording:
            logger.warning("已有录屏任务正在运行")
            return None

        recorder = ScreenRecorder(file_path, fps=fps)
        recorder.start()

        if not recorder.is_recording:
            return None

        _current_recorder = recorder
        _current_task_id = task_id
        _on_complete_callback = on_complete

    # 计算实际生效的自动停止时长
    effective_duration = 0
    if duration > 0:
        # 指定了时长，取 min(duration, max_duration)
        effective_duration = min(duration, max_duration)
    else:
        # 未指定时长，使用 max_duration 作为上限
        effective_duration = max_duration

    if effective_duration > 0:
        def auto_stop_timer():
            time.sleep(effective_duration)
            # 超时后强制停止
            with _recorder_lock:
                if _current_recorder and _current_recorder.is_recording:
                    logger.info(f"录屏达到最大时长限制 ({effective_duration}s)，自动停止")
                    result = _do_stop_recording()
                    if result:
                        _invoke_callback(result, _current_task_id or task_id)

        threading.Thread(target=auto_stop_timer, daemon=True).start()
        logger.info(f"录屏自动停止定时器已设置: {effective_duration}s")

    return {
        "file_path": file_path,
        "file_name": file_name
    }


def _do_stop_recording() -> dict:
    """
    内部方法：执行停止并返回文件信息（调用者需已持有锁或在安全上下文中）
    """
    global _current_recorder

    recorder = _current_recorder
    _current_recorder = None

    if recorder and recorder.stop():
        file_path = recorder.output_path
        if os.path.exists(file_path):
            file_size = os.path.getsize(file_path)
            file_name = os.path.basename(file_path)
            logger.info(f"录屏完成: {file_path}, 大小: {file_size} bytes")
            return {
                "file_path": file_path,
                "file_name": file_name,
                "file_size": file_size
            }
    return None


def stop_recording() -> dict:
    """
    停止当前录屏任务（手动调用）
    停止后自动触发回调（如果已注册）

    Returns:
        dict: {"file_path": str, "file_name": str, "file_size": int} 或 None
    """
    global _current_recorder, _current_task_id

    with _recorder_lock:
        if not _current_recorder:
            logger.warning("没有正在运行的录屏任务")
            return None

        task_id = _current_task_id
        result = _do_stop_recording()
        _current_task_id = None

    if result:
        _invoke_callback(result, task_id)

    return result
